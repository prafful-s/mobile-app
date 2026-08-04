package com.dept.contentbridge.core.services.impl;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.commons.lang3.StringUtils;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ValueMap;
import org.apache.sling.models.factory.ModelFactory;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.metatype.annotations.Designate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.dept.contentbridge.core.models.impl.GenericComponent;
import com.dept.contentbridge.core.schema.ContentBridgeComponent;
import com.dept.contentbridge.core.schema.SchemaConstants;
import com.dept.contentbridge.core.services.ContentBridgeConfiguration;
import com.dept.contentbridge.core.services.FormSchemaService;
import com.dept.contentbridge.core.services.PageJsonExporter;
import com.dept.contentbridge.core.services.UrlService;
import com.day.cq.wcm.api.Page;
import com.day.cq.wcm.api.PageManager;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

@Component(service = PageJsonExporter.class)
@Designate(ocd = ContentBridgeConfiguration.class)
public class PageJsonExporterImpl implements PageJsonExporter {

    private static final Logger LOG = LoggerFactory.getLogger(PageJsonExporterImpl.class);

    /** Guard against a cycle or a pathological tree while walking deeply nested content. */
    private static final int MAX_COMPONENT_DEPTH = 12;

    /** Values starting with this are asset references and get the site's publish host prefixed. */
    private static final String DAM_PREFIX = "/content/dam/";

    /** Component container locations, most specific first. See {@code resolveContainer}. */
    private static final List<String> CONTAINER_CANDIDATES = Arrays.asList(
            SchemaConstants.NN_CONTAINER,   // editable templates: root/container
            "root",                         // Edge Delivery, and simpler editable templates
            "par",                          // classic static templates
            "content/par");

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final List<String> IGNORED_PROPERTIES = Arrays.asList(
            "jcr:primaryType", "jcr:mixinTypes", "jcr:createdBy", "jcr:created",
            "jcr:lastModified", "jcr:lastModifiedBy", "cq:lastModified", "cq:lastModifiedBy");

    @Reference
    private UrlService urlService;

    @Reference
    private ModelFactory modelFactory;

    @Reference
    private FormSchemaService formSchemaService;

    private String[] allowedRoots;
    private String[] navPageNames;
    private Set<String> excludedPageNames;
    private boolean includeHomeScreen;
    private String authorHostSegment;
    private String publishHostSegment;
    private String productsFolderName;
    private String[] productFieldAliases;
    private int maxProducts;
    private int maxDepth;
    private int cacheMaxAge;

    @Activate
    protected void activate(final ContentBridgeConfiguration config) {
        this.allowedRoots = config.allowedRoots() == null ? new String[0] : config.allowedRoots().clone();
        this.navPageNames = config.navPageNames() == null ? new String[0] : config.navPageNames().clone();
        this.excludedPageNames = config.excludedPageNames() == null
                ? Collections.emptySet()
                : new HashSet<>(Arrays.asList(config.excludedPageNames()));
        this.includeHomeScreen = config.includeHomeScreen();
        this.authorHostSegment = StringUtils.defaultIfBlank(config.authorHostSegment(), "author");
        this.publishHostSegment = StringUtils.defaultIfBlank(config.publishHostSegment(), "publish");
        this.productsFolderName = StringUtils.defaultIfBlank(config.productsFolderName(), "products");
        this.productFieldAliases = config.productFieldAliases() == null
                ? new String[0] : config.productFieldAliases().clone();
        this.maxProducts = Math.max(1, config.maxProducts());
        this.maxDepth = Math.max(1, config.maxDepth());
        this.cacheMaxAge = Math.max(0, config.cacheMaxAge());
    }

    @Override
    public boolean isAllowed(final String path) {
        if (StringUtils.isBlank(path)) {
            return false;
        }
        for (final String root : allowedRoots) {
            if (StringUtils.equals(path, root) || StringUtils.startsWith(path, StringUtils.appendIfMissing(root, "/"))) {
                return true;
            }
        }
        return false;
    }

    @Override
    public int getCacheMaxAge() {
        return cacheMaxAge;
    }

    @Override
    public Map<String, Object> export(final Page requested) {
        return export(requested, null);
    }

    @Override
    public Map<String, Object> export(final Page requested, final String productsPath) {
        final Page appRoot = findAppRoot(requested);
        final Page envelopeSource = appRoot != null ? appRoot : requested;
        final ValueMap rootProps = envelopeSource.getProperties();

        final String appId = rootProps.get(SchemaConstants.PN_APP_ID, envelopeSource.getName());

        // Resolved once per export: every screen in one response belongs to the same site, so the
        // placeholders lookup should not be repeated per page.
        final String assetHost = new SiteHostResolver(authorHostSegment, publishHostSegment)
                .resolve(envelopeSource);

        final Map<String, Object> app = new LinkedHashMap<>();
        app.put(SchemaConstants.KEY_ID, appId);
        app.put(SchemaConstants.KEY_META, buildMeta(envelopeSource, appId, rootProps));
        app.put(SchemaConstants.KEY_SCREENS, buildScreens(envelopeSource, requested, assetHost));
        app.put(SchemaConstants.KEY_BRANDING, buildBranding(envelopeSource, assetHost));

        final Resource extras = childResource(envelopeSource, SchemaConstants.NN_EXTRAS);
        app.put(SchemaConstants.KEY_INTEGRATIONS,
                readJsonObject(extras, SchemaConstants.PN_INTEGRATIONS_JSON));
        app.put(SchemaConstants.KEY_PROFILE_VIEWER,
                readJsonObject(extras, SchemaConstants.PN_PROFILE_VIEWER_JSON));
        app.put(SchemaConstants.KEY_PRODUCTS,
                buildProducts(envelopeSource, extras, assetHost, productsPath));

        return app;
    }

    /* ------------------------------------------------------------------ meta */

    private Map<String, Object> buildMeta(final Page root, final String appId, final ValueMap props) {
        final Map<String, Object> meta = new LinkedHashMap<>();
        meta.put(SchemaConstants.KEY_ID, appId);
        meta.put("type", props.get(SchemaConstants.PN_APP_TYPE, SchemaConstants.DEFAULT_APP_TYPE));
        meta.put("title", props.get(SchemaConstants.PN_APP_TITLE,
                StringUtils.defaultIfBlank(root.getTitle(), root.getName())));
        meta.put("useCart", props.get(SchemaConstants.PN_USE_CART, false));

        final Map<String, Object> navigation = new LinkedHashMap<>();
        navigation.put("menu", buildMenu(root));
        navigation.put("loginScreen", props.get(SchemaConstants.PN_LOGIN_SCREEN, "login"));
        navigation.put("profileScreen", props.get(SchemaConstants.PN_PROFILE_SCREEN, "user-details"));
        navigation.put("authorizedMenu",
                Arrays.asList(props.get(SchemaConstants.PN_AUTHORIZED_MENU, new String[0])));
        navigation.put("registrationScreen", props.get(SchemaConstants.PN_REGISTRATION_SCREEN, "registration"));
        meta.put(SchemaConstants.KEY_NAVIGATION, navigation);

        return meta;
    }

    private List<String> buildMenu(final Page root) {
        final List<String> menu = new ArrayList<>();
        for (final Page page : screenPages(root)) {
            menu.add(screenKey(page));
        }
        return menu;
    }

    /* --------------------------------------------------------------- screens */

    private Map<String, Object> buildScreens(final Page root, final Page requested, final String assetHost) {
        final Map<String, Object> screens = new LinkedHashMap<>();
        final boolean wholeApp = requested == null || StringUtils.equals(root.getPath(), requested.getPath());
        final List<Page> pages = wholeApp ? screenPages(root) : Collections.singletonList(requested);
        for (final Page page : pages) {
            screens.put(screenKey(page), buildScreen(page, assetHost));
        }
        return screens;
    }

    /**
     * The single source of truth for which pages are screens - used to build both {@code screens} and
     * {@code meta.navigation.menu}, which is what keeps the two lists in step.
     *
     * <p>Preference is given to the authored navigation page. Listing child pages produces an
     * accidental menu - every page in JCR order, including nav and footer fragments - whereas the
     * navigation page is what an author actually curates. Sites with no navigation page keep the old
     * child-listing behaviour.</p>
     */
    private List<Page> screenPages(final Page root) {
        final Page navPage = resolveNavPage(root);
        if (navPage == null) {
            LOG.debug("No navigation page under {} - falling back to listing child pages", root.getPath());
            return collectScreens(root);
        }

        final PageManager pageManager = root.getPageManager();
        final List<Page> pages = new NavigationExtractor(pageManager)
                // No asset host needed: only the anchors matter here, not the images.
                .extract(navPage, buildComponents(navPage, null));

        if (pages.isEmpty()) {
            LOG.warn("Navigation page {} produced no resolvable links - falling back to child pages",
                    navPage.getPath());
            return collectScreens(root);
        }

        // A nav usually links home from the logo rather than listing it, so the home screen would
        // otherwise be absent from an app built entirely from this response.
        if (includeHomeScreen && pages.stream().noneMatch(p -> p.getPath().equals(root.getPath()))) {
            pages.add(0, root);
        }
        return pages;
    }

    /**
     * @return the authored navigation page, or null when the site has none
     */
    private Page resolveNavPage(final Page root) {
        final String explicit = root.getProperties().get(SchemaConstants.PN_NAV_PATH, String.class);
        if (StringUtils.isNotBlank(explicit)) {
            final Page page = root.getPageManager().getPage(explicit);
            if (page != null) {
                return page;
            }
            LOG.warn("navPath on {} points at '{}', which is not a page", root.getPath(), explicit);
        }
        for (final String name : navPageNames) {
            final Page candidate = root.getPageManager().getPage(root.getPath() + "/" + name);
            if (candidate != null && candidate.getContentResource() != null) {
                return candidate;
            }
        }
        return null;
    }

    private Map<String, Object> buildScreen(final Page page, final String assetHost) {
        final Map<String, Object> screen = new LinkedHashMap<>();

        final Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("name", screenKey(page));
        meta.put("title", StringUtils.defaultIfBlank(page.getTitle(), page.getName()));
        screen.put(SchemaConstants.KEY_META, meta);
        screen.put(SchemaConstants.KEY_CONTENT, buildComponents(page, assetHost));
        return screen;
    }

    private List<Map<String, Object>> buildComponents(final Page page, final String assetHost) {
        final List<Map<String, Object>> components = new ArrayList<>();
        final Resource container = resolveContainer(page);
        if (container != null) {
            collectComponents(container, components, 0);
        }
        if (StringUtils.isNotBlank(assetHost)) {
            for (final Map<String, Object> component : components) {
                final Object content = component.get(SchemaConstants.KEY_CONTENT);
                if (content instanceof Map) {
                    @SuppressWarnings("unchecked")
                    final Map<String, Object> payload = (Map<String, Object>) content;
                    prefixAssets(payload, assetHost);
                }
            }
        }
        return components;
    }

    /**
     * Prefixes DAM references with the site's publish host, in place.
     *
     * <p>Applied here rather than inside each model so that curated models and the generic fallback
     * behave identically, and so the host is resolved once per export instead of once per component.
     * Keyed on the value, not the property name, because projects use {@code image},
     * {@code imageRef}, {@code fileReference} and others interchangeably.</p>
     */
    private void prefixAssets(final Map<String, Object> payload, final String assetHost) {
        for (final Map.Entry<String, Object> entry : payload.entrySet()) {
            final Object value = entry.getValue();
            if (value instanceof String) {
                entry.setValue(prefixAsset((String) value, assetHost));
            } else if (value instanceof List) {
                final List<?> source = (List<?>) value;
                final List<Object> rewritten = new ArrayList<>(source.size());
                for (final Object item : source) {
                    rewritten.add(item instanceof String ? prefixAsset((String) item, assetHost) : item);
                }
                entry.setValue(rewritten);
            }
        }
    }

    private String prefixAsset(final String value, final String assetHost) {
        if (StringUtils.isBlank(assetHost) || !StringUtils.startsWith(value, DAM_PREFIX)) {
            return value;
        }
        return assetHost + value;
    }

    /**
     * Walks the component tree depth-first, in authoring order, flattening every structural node
     * into a single ordered list.
     *
     * <p>Hand-built ContentBridge pages are one level deep, but Edge Delivery content nests
     * {@code root > section > columns > row > col > block > item}. Structural nodes carry layout,
     * not content, so they are descended into rather than exported - which keeps the flat
     * {@code content[]} contract identical for both authoring models.</p>
     */
    private void collectComponents(final Resource parent, final List<Map<String, Object>> sink,
                                   final int depth) {
        if (depth > MAX_COMPONENT_DEPTH) {
            LOG.warn("Component nesting below {} exceeds {} levels - not descending further",
                    parent.getPath(), MAX_COMPONENT_DEPTH);
            return;
        }
        for (final Resource child : parent.getChildren()) {
            if (isSystemNode(child)) {
                continue;
            }
            final String resourceType = StringUtils.trimToEmpty(child.getResourceType());
            final boolean hasChildren = child.hasChildren();

            // Known layout containers, and the untyped row/col wrappers, never produce a component.
            if (isKnownContainerType(resourceType) || (hasChildren && isUntyped(resourceType))) {
                collectComponents(child, sink, depth + 1);
                continue;
            }

            // A registered model wins so curated components keep their exact shape; everything
            // else is projected generically, which is what lets the API serve an arbitrary site.
            ContentBridgeComponent model = adaptStrictly(child);
            if (model == null) {
                model = new GenericComponent(child, urlService);
            }
            final Map<String, Object> content = model.getContent();

            // Children but nothing of its own to say: a wrapper such as an Edge Delivery cards or
            // accordion block. Exporting it would emit an empty component and hide its items.
            // Evaluated from the content already built, so properties are read only once.
            if (hasChildren && content.isEmpty()) {
                collectComponents(child, sink, depth + 1);
                continue;
            }

            final Map<String, Object> entry = new LinkedHashMap<>();
            final Map<String, Object> meta = new LinkedHashMap<>();
            meta.put(SchemaConstants.KEY_ID, model.getId());
            meta.put(SchemaConstants.KEY_COMPONENT, model.getComponentName());
            entry.put(SchemaConstants.KEY_META, meta);
            entry.put(SchemaConstants.KEY_CONTENT, content);

            // Form blocks carry their field definitions alongside the authored content, so a client
            // can render the form without a second call or a hard-coded copy of the schema.
            final Map<String, Object> schema = resolveFormSchema(model.getComponentName(), content);
            if (!schema.isEmpty()) {
                entry.put(SchemaConstants.KEY_SCHEMA, schema);
            }
            sink.add(entry);

            // Components that also nest (carousels, tabs, blocks with items) contribute their
            // children to the same flat list.
            if (hasChildren) {
                collectComponents(child, sink, depth + 1);
            }
        }
    }

    /**
     * Finds the form schema a block should carry, if any.
     *
     * <p>An explicit {@code formName} or {@code form} property wins, but the usual case needs
     * neither: Edge Delivery block models are already named after the form they render
     * ({@code sign-in}, {@code create-account}, {@code checkout}), so the component name is matched
     * against the known schemas directly. A block whose name is not a known form gets nothing, which
     * is how every non-form component stays untouched.</p>
     *
     * @return the field definitions, or an empty map when this block is not a form
     */
    private Map<String, Object> resolveFormSchema(final String componentName,
                                                  final Map<String, Object> content) {
        final String[] candidates = {
            asString(content.get(SchemaConstants.PN_FORM_NAME)),
            asString(content.get(SchemaConstants.PN_FORM)),
            componentName
        };
        for (final String candidate : candidates) {
            if (StringUtils.isBlank(candidate)) {
                continue;
            }
            final Map<String, Object> schema = formSchemaService.getSchema(candidate);
            if (!schema.isEmpty()) {
                LOG.debug("Attached form schema '{}' to a {} block", candidate, componentName);
                return schema;
            }
        }
        return Collections.emptyMap();
    }

    private static String asString(final Object value) {
        return value instanceof String ? (String) value : null;
    }

    /**
     * Machinery rather than content: {@code cq:responsive} breakpoints, live-copy bookkeeping and
     * the ContentBridge configuration node itself. Relevant mainly when a page keeps its components
     * directly under {@code jcr:content} and the walk starts there.
     */
    private static boolean isSystemNode(final Resource resource) {
        final String name = resource.getName();
        return name.startsWith("cq:")
                || name.startsWith("jcr:")
                || name.startsWith("rep:")
                || name.startsWith("oak:")
                || "contentbridge".equals(name);
    }

    private static boolean isKnownContainerType(final String resourceType) {
        if (StringUtils.isEmpty(resourceType)) {
            return false;
        }
        if (SchemaConstants.RT_FRANKLIN_ROOT.equals(resourceType)
                || SchemaConstants.RT_FRANKLIN_SECTION.equals(resourceType)
                || SchemaConstants.RT_FRANKLIN_COLUMNS.equals(resourceType)) {
            return true;
        }
        final String name = StringUtils.substringAfterLast(resourceType, "/");
        return "responsivegrid".equals(name)
                || "container".equals(name)
                || "parsys".equals(name)
                || "iparsys".equals(name)
                || "root".equals(name)
                || "section".equals(name);
    }

    /** @return true when no sling:resourceType is set and Sling fell back to the JCR primary type */
    private static boolean isUntyped(final String resourceType) {
        return StringUtils.isEmpty(resourceType)
                || "nt:unstructured".equals(resourceType)
                || "sling:Folder".equals(resourceType)
                || "sling:OrderedFolder".equals(resourceType)
                || "nt:folder".equals(resourceType);
    }

    /**
     * Adapts a resource to its ContentBridge model <em>only</em> when a model is registered for that
     * exact {@code sling:resourceType}.
     *
     * <p>A plain {@code adaptTo(ContentBridgeComponent.class)} is not safe here. When no
     * implementation matches the resource type, Sling falls back to
     * {@code FirstImplementationPicker}, which returns whichever implementation sorts first by class
     * name - so an unmapped resource would be silently exported as a {@code banner}. Resolving
     * through {@link ModelFactory} keys strictly on the resource type instead.</p>
     *
     * @return the model, or {@code null} when this resource is not part of the JSON contract
     */
    private ContentBridgeComponent adaptStrictly(final Resource resource) {
        try {
            if (!modelFactory.isModelAvailableForResource(resource)) {
                LOG.debug("No ContentBridge model registered for {} ({}) - skipping",
                        resource.getPath(), resource.getResourceType());
                return null;
            }
            final Object model = modelFactory.getModelFromResource(resource);
            if (model instanceof ContentBridgeComponent) {
                return (ContentBridgeComponent) model;
            }
            LOG.debug("Model for {} ({}) is not a ContentBridgeComponent - skipping",
                    resource.getPath(), resource.getResourceType());
        } catch (final RuntimeException e) {
            LOG.warn("Could not build a ContentBridge model for {} - skipping", resource.getPath(), e);
        }
        return null;
    }

    /**
     * Finds the node holding the authored components, trying the layouts AEM projects actually use
     * before falling back to {@code jcr:content} itself.
     *
     * <p>Editable templates put components under {@code root/container}, Edge Delivery under
     * {@code root}, and classic static templates under {@code par} or {@code content/par}. The
     * fallback is safe because the traversal walks through containers anyway - it mainly decides how
     * much page-level furniture (branding nodes, {@code cq:} subtrees) the walk has to step over.</p>
     */
    private Resource resolveContainer(final Page page) {
        final Resource content = page.getContentResource();
        if (content == null) {
            return null;
        }
        for (final String candidate : CONTAINER_CANDIDATES) {
            final Resource container = content.getChild(candidate);
            if (container != null) {
                return container;
            }
        }
        final Resource root = content.getChild("root");
        return root != null ? root : content;
    }

    /* -------------------------------------------------------------- branding */

    /**
     * Products come from the site's Content Fragments. The hand-authored {@code productsJson} on the
     * page is kept as a fallback so sites configured before this existed keep working, and so a site
     * with no fragment catalogue can still supply a static list.
     */
    private List<Object> buildProducts(final Page root, final Resource extras, final String assetHost,
                                       final String productsPath) {
        final List<Map<String, Object>> fromFragments =
                new ProductExporter(productsFolderName, maxProducts, productFieldAliases)
                        .export(root, assetHost, productsPath);

        if (!fromFragments.isEmpty()) {
            return new ArrayList<>(fromFragments);
        }
        return readJsonArray(extras, SchemaConstants.PN_PRODUCTS_JSON);
    }

    private Map<String, Object> buildBranding(final Page root, final String assetHost) {
        final Resource branding = childResource(root, SchemaConstants.NN_BRANDING);
        final Map<String, Object> result = new LinkedHashMap<>();
        if (branding == null) {
            return result;
        }
        final ValueMap props = branding.getValueMap();
        for (final Map.Entry<String, Object> entry : props.entrySet()) {
            if (IGNORED_PROPERTIES.contains(entry.getKey())) {
                continue;
            }
            final Object value = entry.getValue();
            // Logos are DAM paths; colour tokens are not, and prefixAsset keys on the value so it
            // leaves '#005971' alone without needing to know which properties are which.
            result.put(entry.getKey(),
                    value instanceof String ? prefixAsset((String) value, assetHost) : value);
        }
        return result;
    }

    /* ----------------------------------------------------------------- utils */

    private List<Page> collectScreens(final Page root) {
        final List<Page> pages = new ArrayList<>();
        if (isExported(root)) {
            pages.add(root);
        }
        collectDescendants(root, pages, 1);
        return pages;
    }

    private void collectDescendants(final Page parent, final List<Page> sink, final int depth) {
        if (depth > maxDepth) {
            return;
        }
        final Iterator<Page> children = parent.listChildren();
        while (children.hasNext()) {
            final Page child = children.next();
            if (!isExported(child)) {
                continue;
            }
            sink.add(child);
            collectDescendants(child, sink, depth + 1);
        }
    }

    private boolean isExported(final Page page) {
        return page != null && page.getContentResource() != null
                && !page.getProperties().get(SchemaConstants.PN_EXCLUDE_FROM_APP, false)
                // nav and footer are site furniture, not destinations
                && !excludedPageNames.contains(page.getName());
    }

    private Page findAppRoot(final Page page) {
        Page current = page;
        while (current != null) {
            if (StringUtils.isNotBlank(current.getProperties().get(SchemaConstants.PN_APP_ID, String.class))) {
                return current;
            }
            current = current.getParent();
        }
        return null;
    }

    private static String screenKey(final Page page) {
        return StringUtils.defaultIfBlank(
                page.getProperties().get("screenName", String.class),
                StringUtils.defaultIfBlank(page.getTitle(), page.getName()));
    }

    private Resource childResource(final Page page, final String relPath) {
        final Resource content = page.getContentResource();
        return content == null ? null : content.getChild(relPath);
    }

    private Map<String, Object> readJsonObject(final Resource extras, final String property) {
        final String raw = extras == null ? null : extras.getValueMap().get(property, String.class);
        if (StringUtils.isBlank(raw)) {
            return new LinkedHashMap<>();
        }
        try {
            return MAPPER.readValue(raw, new TypeReference<LinkedHashMap<String, Object>>() { });
        } catch (final IOException e) {
            LOG.warn("Property {} on {} is not valid JSON - emitting an empty object", property,
                    extras.getPath(), e);
            return new LinkedHashMap<>();
        }
    }

    private List<Object> readJsonArray(final Resource extras, final String property) {
        final String raw = extras == null ? null : extras.getValueMap().get(property, String.class);
        if (StringUtils.isBlank(raw)) {
            return new ArrayList<>();
        }
        try {
            return MAPPER.readValue(raw, new TypeReference<ArrayList<Object>>() { });
        } catch (final IOException e) {
            LOG.warn("Property {} on {} is not valid JSON - emitting an empty array", property,
                    extras.getPath(), e);
            return new ArrayList<>();
        }
    }
}

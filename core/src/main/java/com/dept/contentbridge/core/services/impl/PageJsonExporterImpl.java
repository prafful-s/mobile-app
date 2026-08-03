package com.dept.contentbridge.core.services.impl;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
import com.dept.contentbridge.core.services.PageJsonExporter;
import com.dept.contentbridge.core.services.UrlService;
import com.day.cq.wcm.api.Page;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

@Component(service = PageJsonExporter.class)
@Designate(ocd = ContentBridgeConfiguration.class)
public class PageJsonExporterImpl implements PageJsonExporter {

    private static final Logger LOG = LoggerFactory.getLogger(PageJsonExporterImpl.class);

    /** Guard against a cycle or a pathological tree while walking deeply nested content. */
    private static final int MAX_COMPONENT_DEPTH = 12;

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

    private String[] allowedRoots;
    private int maxDepth;
    private int cacheMaxAge;

    @Activate
    protected void activate(final ContentBridgeConfiguration config) {
        this.allowedRoots = config.allowedRoots() == null ? new String[0] : config.allowedRoots().clone();
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
        final Page appRoot = findAppRoot(requested);
        final Page envelopeSource = appRoot != null ? appRoot : requested;
        final ValueMap rootProps = contentProperties(envelopeSource);

        final String appId = rootProps.get(SchemaConstants.PN_APP_ID, envelopeSource.getName());

        final Map<String, Object> app = new LinkedHashMap<>();
        app.put(SchemaConstants.KEY_ID, appId);
        app.put(SchemaConstants.KEY_META, buildMeta(envelopeSource, appId, rootProps));
        app.put(SchemaConstants.KEY_SCREENS, buildScreens(envelopeSource, requested));
        app.put(SchemaConstants.KEY_BRANDING, buildBranding(envelopeSource));

        final Resource extras = childResource(envelopeSource, SchemaConstants.NN_EXTRAS);
        app.put(SchemaConstants.KEY_INTEGRATIONS,
                readJsonObject(extras, SchemaConstants.PN_INTEGRATIONS_JSON));
        app.put(SchemaConstants.KEY_PROFILE_VIEWER,
                readJsonObject(extras, SchemaConstants.PN_PROFILE_VIEWER_JSON));
        app.put(SchemaConstants.KEY_PRODUCTS,
                readJsonArray(extras, SchemaConstants.PN_PRODUCTS_JSON));

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
        for (final Page page : collectScreens(root)) {
            menu.add(screenKey(page));
        }
        return menu;
    }

    /* --------------------------------------------------------------- screens */

    private Map<String, Object> buildScreens(final Page root, final Page requested) {
        final Map<String, Object> screens = new LinkedHashMap<>();
        final boolean wholeApp = requested == null || StringUtils.equals(root.getPath(), requested.getPath());
        final List<Page> pages = wholeApp ? collectScreens(root) : Collections.singletonList(requested);
        for (final Page page : pages) {
            screens.put(screenKey(page), buildScreen(page));
        }
        return screens;
    }

    private Map<String, Object> buildScreen(final Page page) {
        final Map<String, Object> screen = new LinkedHashMap<>();

        final Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("name", screenKey(page));
        meta.put("title", StringUtils.defaultIfBlank(page.getTitle(), page.getName()));
        meta.put("icon", page.getProperties().get(SchemaConstants.PN_SCREEN_ICON,
                page.getDepth() <= depthOfRoot(page) ? SchemaConstants.DEFAULT_HOME_ICON
                        : SchemaConstants.DEFAULT_SCREEN_ICON));
        screen.put(SchemaConstants.KEY_META, meta);
        screen.put(SchemaConstants.KEY_CONTENT, buildComponents(page));
        return screen;
    }

    private List<Map<String, Object>> buildComponents(final Page page) {
        final List<Map<String, Object>> components = new ArrayList<>();
        final Resource container = resolveContainer(page);
        if (container != null) {
            collectComponents(container, components, 0);
        }
        return components;
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
            // Structural first: a container block would otherwise adapt to a model and be exported
            // as an empty component, swallowing the items underneath it.
            if (isStructural(child)) {
                collectComponents(child, sink, depth + 1);
                continue;
            }
            // A registered model wins so curated components keep their exact shape; everything
            // else is projected generically, which is what lets the API serve an arbitrary site.
            ContentBridgeComponent model = adaptStrictly(child);
            if (model == null) {
                model = new GenericComponent(child, urlService);
            }
            final Map<String, Object> entry = new LinkedHashMap<>();
            final Map<String, Object> meta = new LinkedHashMap<>();
            meta.put(SchemaConstants.KEY_ID, model.getId());
            meta.put(SchemaConstants.KEY_COMPONENT, model.getComponentName());
            entry.put(SchemaConstants.KEY_META, meta);
            entry.put(SchemaConstants.KEY_CONTENT, model.getContent());
            sink.add(entry);

            // Components that also nest (carousels, tabs, blocks with items) contribute their
            // children to the same flat list.
            if (child.hasChildren()) {
                collectComponents(child, sink, depth + 1);
            }
        }
    }

    /**
     * A node is structural when it groups other components instead of carrying content of its own.
     * Structural nodes are walked through and never appear in the output.
     *
     * <p>The test has to be generic, because an arbitrary site brings its own layout containers.
     * Three signals are used, in order:</p>
     * <ol>
     *   <li>a resource type known to be a layout container - responsive grid, parsys, container,
     *       or one of the Edge Delivery structural types;</li>
     *   <li>no {@code sling:resourceType} at all, plus children - the untyped {@code row1}/{@code col1}
     *       wrappers and plain node folders;</li>
     *   <li>children but no authored properties of its own - which is how a container is told apart
     *       from a component that merely happens to nest, without hard-coding its resource type.</li>
     * </ol>
     */
    private boolean isStructural(final Resource resource) {
        final String resourceType = StringUtils.trimToEmpty(resource.getResourceType());

        if (isKnownContainerType(resourceType)) {
            return true;
        }
        if (!resource.hasChildren()) {
            return false;
        }
        if (isUntyped(resourceType)) {
            return true;
        }
        // Children, but nothing of its own to say: a wrapper. Exporting it would emit an empty
        // component and, worse, hide everything underneath it.
        return new GenericComponent(resource, urlService).getContent().isEmpty();
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

    private Map<String, Object> buildBranding(final Page root) {
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
            result.put(entry.getKey(),
                    value instanceof String ? maybeExternalize(entry.getKey(), (String) value) : value);
        }
        return result;
    }

    /**
     * Logo properties hold DAM paths and must be externalised; colour tokens must not be touched.
     */
    private String maybeExternalize(final String key, final String value) {
        return StringUtils.containsIgnoreCase(key, "logo") ? urlService.externalize(value) : value;
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
                && !page.getProperties().get(SchemaConstants.PN_EXCLUDE_FROM_APP, false);
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

    private int depthOfRoot(final Page page) {
        final Page root = findAppRoot(page);
        return root != null ? root.getDepth() : page.getDepth();
    }

    private ValueMap contentProperties(final Page page) {
        return page.getProperties();
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
        } catch (final Exception e) {
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
        } catch (final Exception e) {
            LOG.warn("Property {} on {} is not valid JSON - emitting an empty array", property,
                    extras.getPath(), e);
            return new ArrayList<>();
        }
    }
}

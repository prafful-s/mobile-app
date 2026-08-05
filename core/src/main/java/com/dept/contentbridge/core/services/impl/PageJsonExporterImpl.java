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

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final List<String> IGNORED_PROPERTIES = List.of(
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

    // Built once at activation rather than per request: they are stateless, and rebuilding them for
    // every page re-parsed the alias table and re-read configuration that cannot have changed.
    private ComponentTreeWalker walker;
    private SiteHostResolver hostResolver;
    private ProductExporter productExporter;

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

        this.walker = new ComponentTreeWalker(modelFactory, urlService, formSchemaService);
        this.hostResolver = new SiteHostResolver(authorHostSegment, publishHostSegment);
        this.productExporter = new ProductExporter(productsFolderName, maxProducts, productFieldAliases);
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
        final String assetHost = hostResolver.resolve(envelopeSource);

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
                .extract(navPage, walker.componentsOf(navPage, null));

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
        screen.put(SchemaConstants.KEY_CONTENT, walker.componentsOf(page, assetHost));
        return screen;
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
                productExporter.export(root, assetHost, productsPath);

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

    /**
     * Branding logos are DAM paths and need the same treatment component assets get. Kept here
     * rather than reached for on the walker, because branding is page-level furniture and has
     * nothing to do with the component tree.
     */
    private String prefixAsset(final String value, final String assetHost) {
        if (StringUtils.isBlank(assetHost) || !StringUtils.startsWith(value, DAM_PREFIX)) {
            return value;
        }
        return assetHost + value;
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

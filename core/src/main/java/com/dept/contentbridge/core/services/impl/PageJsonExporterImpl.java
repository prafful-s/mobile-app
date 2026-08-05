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
import com.dept.contentbridge.core.services.ExportOptions;
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

        // Rules ship inside the bundle, so they are read once here rather than per request.
        this.walker = new ComponentTreeWalker(modelFactory, urlService, formSchemaService,
                new StyleResolver(config.styleTokens()), ComponentMappings.load());
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
        // Explicit defaults rather than a bare null: with both a String and an ExportOptions
        // overload, null is ambiguous to the compiler.
        return export(requested, ExportOptions.defaults());
    }

    @Override
    public Map<String, Object> export(final Page requested, final String productsPath) {
        return export(requested, ExportOptions.of(ExportOptions.SCHEMA_V1, productsPath));
    }

    @Override
    public Map<String, Object> export(final Page requested, final ExportOptions options) {
        final ExportOptions opts = options == null ? ExportOptions.defaults() : options;
        final String productsPath = opts.getProductsPath();
        final Page appRoot = findAppRoot(requested);
        final Page envelopeSource = appRoot != null ? appRoot : requested;
        final ValueMap rootProps = envelopeSource.getProperties();

        final String appId = rootProps.get(SchemaConstants.PN_APP_ID, envelopeSource.getName());

        // Resolved once per export: every screen in one response belongs to the same site, so the
        // placeholders lookup should not be repeated per page.
        final String assetHost = deliveryHost(envelopeSource);

        // Resolved once and shared: the menu and the screen list have to agree, and screenPages()
        // walks the authored navigation page, which is not work to repeat.
        final List<String> menu = buildMenu(screenPages(envelopeSource));

        final Map<String, Object> app = new LinkedHashMap<>();
        app.put(SchemaConstants.KEY_ID, appId);
        app.put(SchemaConstants.KEY_META, buildMeta(envelopeSource, appId, rootProps, menu, opts));
        if (opts.isHierarchical()) {
            // No AEM source for either yet, but the target shape is fixed, so a client can rely on
            // the keys being present rather than testing for them.
            app.put(SchemaConstants.KEY_RULES, Collections.emptyList());
            app.put(SchemaConstants.KEY_MODULES, Collections.emptyList());
        }
        app.put(SchemaConstants.KEY_SCREENS,
                buildScreens(envelopeSource, requested, assetHost, opts, menu));
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

    private Map<String, Object> buildMeta(final Page root, final String appId, final ValueMap props,
                                          final List<String> menu, final ExportOptions options) {
        final Map<String, Object> meta = new LinkedHashMap<>();
        meta.put(SchemaConstants.KEY_ID, appId);
        meta.put("type", props.get(SchemaConstants.PN_APP_TYPE, SchemaConstants.DEFAULT_APP_TYPE));
        meta.put("title", props.get(SchemaConstants.PN_APP_TITLE,
                StringUtils.defaultIfBlank(root.getTitle(), root.getName())));
        meta.put("useCart", props.get(SchemaConstants.PN_USE_CART, false));

        final Map<String, Object> navigation = new LinkedHashMap<>();
        navigation.put("menu", menu);
        navigation.put("loginScreen", props.get(SchemaConstants.PN_LOGIN_SCREEN, "login"));
        navigation.put("profileScreen", props.get(SchemaConstants.PN_PROFILE_SCREEN, "user-details"));
        navigation.put("authorizedMenu",
                Arrays.asList(props.get(SchemaConstants.PN_AUTHORIZED_MENU, new String[0])));
        navigation.put("registrationScreen", props.get(SchemaConstants.PN_REGISTRATION_SCREEN, "registration"));
        meta.put(SchemaConstants.KEY_NAVIGATION, navigation);

        if (options.isHierarchical()) {
            EnvelopeMeta.addAppMeta(meta, root);
        }
        return meta;
    }

    private List<String> buildMenu(final List<Page> screens) {
        final List<String> menu = new ArrayList<>();
        for (final Page page : screens) {
            menu.add(screenKey(page));
        }
        return menu;
    }

    /* --------------------------------------------------------------- screens */

    private Map<String, Object> buildScreens(final Page root, final Page requested, final String assetHost,
                                            final ExportOptions options, final List<String> menu) {
        final Map<String, Object> screens = new LinkedHashMap<>();
        final boolean wholeApp = requested == null || StringUtils.equals(root.getPath(), requested.getPath());
        final List<Page> pages = wholeApp ? screenPages(root) : Collections.singletonList(requested);
        for (final Page page : pages) {
            screens.put(screenKey(page), buildScreen(page, root, assetHost, options, menu));
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

    private Map<String, Object> buildScreen(final Page page, final Page root, final String assetHost,
                                           final ExportOptions options, final List<String> menu) {
        final Map<String, Object> screen = new LinkedHashMap<>();

        final Map<String, Object> meta;
        if (options.isHierarchical()) {
            meta = EnvelopeMeta.screenMeta(page, root, screenKey(page), menu);
        } else {
            meta = new LinkedHashMap<>();
            meta.put("name", screenKey(page));
            meta.put("title", StringUtils.defaultIfBlank(page.getTitle(), page.getName()));
        }
        screen.put(SchemaConstants.KEY_META, meta);
        screen.put(SchemaConstants.KEY_CONTENT, walker.componentsOf(page, assetHost, options.isHierarchical()));
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
            // Logos are repository paths; colour tokens are not, and the prefixer keys on the value
            // so it leaves '#005971' alone without needing to know which properties are which.
            result.put(entry.getKey(),
                    value instanceof String ? prefixAsset((String) value, assetHost) : value);
        }
        return result;
    }

    /**
     * The host every repository path in this response is made absolute against.
     *
     * <p>The site's own {@code placeholders} page wins, so two sites in one repository can point at
     * different delivery domains. A site declaring no {@code hostname} falls back to the
     * instance-wide publish domain - an explicit operator setting, not a guess - and with neither
     * configured the paths stay relative. Nothing else is consulted: a URL sitting in some other
     * placeholder is not necessarily a host, and guessing produces links that resolve to nothing.</p>
     */
    private String deliveryHost(final Page site) {
        final String fromPlaceholders = hostResolver.resolve(site);
        if (StringUtils.isNotBlank(fromPlaceholders)) {
            return fromPlaceholders;
        }
        return urlService == null ? StringUtils.EMPTY : urlService.getPublishDomain();
    }

    /**
     * Branding logos are DAM paths and need the same treatment component assets get. Kept here
     * rather than reached for on the walker, because branding is page-level furniture and has
     * nothing to do with the component tree.
     */
    private String prefixAsset(final String value, final String assetHost) {
        return ContentPaths.prefix(value, assetHost);
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

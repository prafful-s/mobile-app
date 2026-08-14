package com.dept.contentbridge.core.services.impl;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.apache.commons.lang3.StringUtils;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ValueMap;
import org.apache.sling.engine.SlingRequestProcessor;
import org.apache.sling.models.factory.ModelFactory;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.osgi.service.component.annotations.ReferencePolicy;
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

    /* ---- where the catalogue came from; logged, never emitted ---- */
    private static final String SOURCE_GRAPHQL = "graphql";
    private static final String SOURCE_FRAGMENTS = "fragments";
    private static final String SOURCE_AUTHORED = "authored";
    private static final String SOURCE_NONE = "none";

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

    /**
     * Dispatches the persisted GraphQL query in-JVM. Optional so the exporter still activates on an
     * instance where it cannot be bound - products then come from Content Fragments, which is the
     * behaviour every site had before persisted queries were an option.
     */
    @Reference(cardinality = ReferenceCardinality.OPTIONAL, policy = ReferencePolicy.STATIC)
    private SlingRequestProcessor requestProcessor;

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
    private GraphQlProductSource graphQlProducts;
    private SiteProductDefaults siteProductDefaults;

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
        this.graphQlProducts = new GraphQlProductSource(
                new GraphQlClient(requestProcessor), productExporter);
        this.siteProductDefaults = SiteProductDefaults.load();
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
        final Page appRoot = findAppRoot(requested);
        final Page envelopeSource = appRoot != null ? appRoot : requested;
        final ValueMap rootProps = envelopeSource.getProperties();

        final String appId = rootProps.get(SchemaConstants.PN_APP_ID, envelopeSource.getName());

        // Resolved once per export: every screen in one response belongs to the same site, so the
        // placeholders lookup should not be repeated per page.
        final String assetHost = deliveryHost(envelopeSource);

        // Two different questions, deliberately answered by two different methods. The menu is what
        // an author curated on the nav page; the screens are every page the app can reach. A page
        // can be a screen without being in the menu - a plan detail page reached from a card, say -
        // and screen meta records that as includeInNavigation: false.
        final List<String> menu = buildMenu(menuPages(envelopeSource));

        final Resource extras = childResource(envelopeSource, SchemaConstants.NN_EXTRAS);

        final Catalogue catalogue = buildProducts(envelopeSource, extras, assetHost, opts);

        // At INFO because it answers the one question the payload cannot: the three sources produce
        // identical output, so a working persisted query and a silent fallback look the same.
        LOG.info("Products for {} came from {} ({} item(s))",
                envelopeSource.getPath(), catalogue.source, catalogue.products.size());

        final Map<String, Object> app = new LinkedHashMap<>();
        // v2 (hierarchical): top-level id is inside meta; omit the standalone key to match the DSN
        // reference contract. v1 keeps it for backward compatibility with existing clients.
        if (!opts.isHierarchical()) {
            app.put(SchemaConstants.KEY_ID, appId);
        }
        app.put(SchemaConstants.KEY_META,
                buildMeta(envelopeSource, appId, rootProps, menu, opts));
        if (opts.isHierarchical()) {
            // No AEM source for either yet, but the target shape is fixed, so a client can rely on
            // the keys being present rather than testing for them.
            app.put(SchemaConstants.KEY_RULES, Collections.emptyList());
            app.put(SchemaConstants.KEY_MODULES, Collections.emptyList());
        }
        app.put(SchemaConstants.KEY_SCREENS,
                buildScreens(envelopeSource, requested, assetHost, opts, menu));
        app.put(SchemaConstants.KEY_BRANDING, buildBranding(envelopeSource, assetHost));

        if (opts.isHierarchical()) {
            // DSN reference order: branding, products, integrations, profileViewer
            app.put(SchemaConstants.KEY_PRODUCTS, catalogue.products);
            app.put(SchemaConstants.KEY_INTEGRATIONS,
                    readJsonObject(extras, SchemaConstants.PN_INTEGRATIONS_JSON));
            app.put(SchemaConstants.KEY_PROFILE_VIEWER,
                    readJsonObject(extras, SchemaConstants.PN_PROFILE_VIEWER_JSON));
        } else {
            app.put(SchemaConstants.KEY_INTEGRATIONS,
                    readJsonObject(extras, SchemaConstants.PN_INTEGRATIONS_JSON));
            app.put(SchemaConstants.KEY_PROFILE_VIEWER,
                    readJsonObject(extras, SchemaConstants.PN_PROFILE_VIEWER_JSON));
            app.put(SchemaConstants.KEY_PRODUCTS, catalogue.products);
        }

        return app;
    }

    /* ------------------------------------------------------------------ meta */

    private Map<String, Object> buildMeta(final Page root, final String appId, final ValueMap props,
                                          final List<String> menu, final ExportOptions options) {
        final Map<String, Object> navigation = buildNavigation(props, menu);

        if (options.isHierarchical()) {
            // v2: build the entire meta in DSN reference field order via EnvelopeMeta. The nav
            // page's search block, when authored, takes over meta.rightIcon from the placeholders
            // sheet - so a header search icon can be configured by dropping a search block into
            // the nav, no placeholder-sheet round-trip needed.
            return EnvelopeMeta.buildFullV2Meta(root, appId, props, navigation,
                    navPageRightIcon(root));
        }

        // v1: original field order (id, type, title, useCart, navigation)
        final Map<String, Object> meta = new LinkedHashMap<>();
        meta.put(SchemaConstants.KEY_ID, appId);
        meta.put("type", props.get(SchemaConstants.PN_APP_TYPE, SchemaConstants.DEFAULT_APP_TYPE));
        meta.put("title", props.get(SchemaConstants.PN_APP_TITLE,
                StringUtils.defaultIfBlank(root.getTitle(), root.getName())));
        meta.put("useCart", props.get(SchemaConstants.PN_USE_CART, false));
        meta.put(SchemaConstants.KEY_NAVIGATION, navigation);
        return meta;
    }

    private Map<String, Object> buildNavigation(final ValueMap props, final List<String> menu) {
        final Map<String, Object> navigation = new LinkedHashMap<>();
        navigation.put("menu", menu);
        navigation.put("loginScreen", props.get(SchemaConstants.PN_LOGIN_SCREEN, "login"));
        navigation.put("profileScreen", props.get(SchemaConstants.PN_PROFILE_SCREEN, "user-details"));
        navigation.put("authorizedMenu",
                Arrays.asList(props.get(SchemaConstants.PN_AUTHORIZED_MENU, new String[0])));
        navigation.put("registrationScreen",
                props.get(SchemaConstants.PN_REGISTRATION_SCREEN, "registration"));
        return navigation;
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
            final String key = screenKey(page);
            if (screens.containsKey(key)) {
                // Same node name reused under two different parents (siblings can't collide - JCR
                // forbids it) - the later one would otherwise silently clobber the former in the
                // response with no trace of what happened to it.
                LOG.warn("Screen key '{}' from {} collides with an already-exported screen - "
                        + "overwriting it. Give one of the pages a distinct node name.", key, page.getPath());
            }
            screens.put(key, buildScreen(page, root, assetHost, options, menu));
        }
        return screens;
    }

    /**
     * Every page the app can reach: the site root and its descendants, minus the fragments that are
     * furniture rather than destinations.
     *
     * <p>Deliberately not the menu. A site has pages a client must be able to render but that no
     * author put in the navigation - a plan detail page opened from a card, a confirmation screen
     * after a form. Exporting only the menu would leave those unreachable; exporting them without
     * distinction would make the menu meaningless. So both are produced, and screen meta says which
     * screens the menu contains.</p>
     *
     * <p>{@code header}, {@code footer} and the nav fragment are excluded by name through
     * {@code excludedPageNames}, along with any page flagged with {@code excludeFromApp}.</p>
     */
    private List<Page> screenPages(final Page root) {
        return collectScreens(root);
    }

    /**
     * The pages named in the authored navigation, in the order the author put them.
     *
     * <p>Read from the nav or header fragment rather than by listing children, because listing
     * produces an accidental menu - every page in JCR order, including the fragments themselves.
     * A site with no navigation page falls back to its screens, which at least gives a client
     * something to render.</p>
     */
    private List<Page> menuPages(final Page root) {
        final Page navPage = resolveNavPage(root);
        if (navPage == null) {
            LOG.debug("No navigation page under {} - the menu falls back to the screen list",
                    root.getPath());
            return collectScreens(root);
        }

        final PageManager pageManager = root.getPageManager();
        final List<Page> pages = new NavigationExtractor(pageManager)
                // No asset host needed: only the anchors matter here, not the images.
                .extract(navPage, walker.componentsOf(navPage, null));

        if (pages.isEmpty()) {
            LOG.warn("Navigation page {} produced no resolvable links - the menu falls back to the "
                    + "screen list", navPage.getPath());
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
     * Reads the {@code rightIcon} sub-object out of the nav page's first {@code search} block,
     * if one is authored. Same {@code {icon, name, target}} shape the meta wants, which is what
     * the {@code search.json} mapping rule already produces on that block.
     *
     * @return the rightIcon map to use, or null when no nav page or no search block is present -
     *         the caller then falls back to the placeholders-sheet source
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> navPageRightIcon(final Page root) {
        final Page navPage = resolveNavPage(root);
        if (navPage == null) {
            return null;
        }
        // hierarchical=true so mapping rules fire - the search.json rule is what turns a flat
        // 'search' block into the { rightIcon: { icon, name, target } } shape.
        final List<Map<String, Object>> components = walker.componentsOf(navPage, null, true);
        for (final Map<String, Object> component : findSearchComponents(components)) {
            final Object content = component.get(SchemaConstants.KEY_CONTENT);
            if (!(content instanceof Map)) {
                continue;
            }
            final Object rightIcon = ((Map<String, Object>) content).get("rightIcon");
            if (rightIcon instanceof Map) {
                return (Map<String, Object>) rightIcon;
            }
        }
        return null;
    }

    /** Walks a component tree (v2 hierarchical, nested {@code content} arrays) collecting every
     *  {@code search} component. Recursive because panels wrap child components. */
    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> findSearchComponents(final List<Map<String, Object>> tree) {
        final List<Map<String, Object>> found = new ArrayList<>();
        for (final Map<String, Object> component : tree) {
            final Object meta = component.get(SchemaConstants.KEY_META);
            if (meta instanceof Map && "search".equals(((Map<String, Object>) meta).get(SchemaConstants.KEY_COMPONENT))) {
                found.add(component);
                continue;
            }
            final Object content = component.get(SchemaConstants.KEY_CONTENT);
            if (content instanceof List) {
                found.addAll(findSearchComponents((List<Map<String, Object>>) content));
            }
        }
        return found;
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
        if (options.isHierarchical()) {
            // DSN reference shape: meta, editor, content.
            // The editor block carries canvas layout (zIndex/position) authored in DSN Studio;
            // AEM has no equivalent, so a zero-valued skeleton keeps the key shape fixed without
            // fabricating a real position - the client sees the shape it expects, and the zeros
            // read as "unpositioned" rather than "missing".
            final Map<String, Object> position = new LinkedHashMap<>();
            position.put("top", 0);
            position.put("left", 0);
            final Map<String, Object> editor = new LinkedHashMap<>();
            editor.put("zIndex", 0);
            editor.put("position", position);
            screen.put(SchemaConstants.KEY_EDITOR, editor);
        }
        // applyScreenExclusions=true drops unmapped components (fileUpload/iframe/loan-calculator/
        // search) from screens.content[]. Nav-page walk above deliberately does not exclude - it
        // needs 'search' visible so meta.rightIcon can pick it up.
        screen.put(SchemaConstants.KEY_CONTENT,
                walker.componentsOf(page, assetHost, options.isHierarchical(), true));
        return screen;
    }


    /* -------------------------------------------------------------- branding */

    /**
     * Products come from the site's Content Fragments. The hand-authored {@code productsJson} on the
     * page is kept as a fallback so sites configured before this existed keep working, and so a site
     * with no fragment catalogue can still supply a static list.
     */
    /**
     * Products, from the first source that has any.
     *
     * <p>Three sources, most authoritative first. A persisted GraphQL query is preferred where a
     * site configures one, because the query is a contract owned by whoever owns the fragment model.
     * Reading the fragments directly is the fallback, and a hand-authored {@code productsJson} the
     * one after that - kept so sites configured before either existed keep working.</p>
     *
     * <p>Each falls through on an empty result rather than on an exception, so a query that is
     * misconfigured, unregistered or simply returns nothing costs the catalogue and not the export.
     * The cost of that leniency is that a genuinely empty catalogue is indistinguishable from a
     * broken query - which is why {@code GraphQlClient} logs the difference.</p>
     *
     * <p>When neither the request nor the site's placeholders name a catalogue location at all, the
     * bundle's shipped {@link SiteProductDefaults} supplies both the persisted query (via
     * {@link GraphQlQuery#resolve}) and, if that yields nothing, the Content Fragment folder for the
     * plain read below - so a site works out of the box before anyone authors a placeholders page.</p>
     */
    private Catalogue buildProducts(final Page root, final Resource extras, final String assetHost,
                                    final ExportOptions options) {
        // Persisted GraphQL query owns the catalogue when configured. Its result stands - an empty
        // query result means an empty catalogue, not a signal to fall back to the raw CF walk. That
        // fallback existed to bridge sites configured before GraphQL support landed; keeping it
        // meant a working query returning zero items was silently overwritten by a stale on-disk
        // fragment folder. When GraphQL is not configured at all, the plain-CF read still runs.
        final GraphQlQuery query = GraphQlQuery.resolve(root, options, this::isAllowed, siteProductDefaults);
        if (query.isConfigured()) {
            final List<Map<String, Object>> fromQuery =
                    graphQlProducts.export(root, query, assetHost);
            return new Catalogue(new ArrayList<>(fromQuery),
                    fromQuery.isEmpty() ? SOURCE_NONE : SOURCE_GRAPHQL);
        }

        List<Map<String, Object>> fromFragments =
                productExporter.export(root, assetHost, options.getProductsPath());

        if (fromFragments.isEmpty() && StringUtils.isBlank(options.getProductsPath())) {
            final SiteProductDefaults.Entry shipped = siteProductDefaults.forPage(root);
            if (shipped != null && StringUtils.isNotBlank(shipped.getCfFolderPath())) {
                fromFragments = productExporter.export(root, assetHost, shipped.getCfFolderPath());
            }
        }

        if (!fromFragments.isEmpty()) {
            return new Catalogue(new ArrayList<>(fromFragments), SOURCE_FRAGMENTS);
        }
        final List<Object> authored = readJsonArray(extras, SchemaConstants.PN_PRODUCTS_JSON);
        return new Catalogue(authored, authored.isEmpty() ? SOURCE_NONE : SOURCE_AUTHORED);
    }

    /**
     * The products and where they came from.
     *
     * <p>The source is carried so it can be logged. It is deliberately not emitted: the response
     * schema is a published contract, and diagnostics do not belong in it.</p>
     */
    private static final class Catalogue {

        private final List<Object> products;
        private final String source;

        Catalogue(final List<Object> products, final String source) {
            this.products = products;
            this.source = source;
        }
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
        if (parent == null || depth > maxDepth) {
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

    /**
     * The stable key a screen is filed under in {@code screens} and referred to by from
     * {@code meta.navigation}.
     *
     * <p>Always the page's JCR node name, never the authored {@code pageName} property. JCR
     * guarantees sibling node names are unique, so this key cannot collide the way {@code pageName}
     * did - two authors are free to type the same value into that property (or leave it blank,
     * which used to fall back to the node name anyway, so most sites saw no difference). The
     * {@code pageName} property itself is no longer read here.</p>
     *
     * <p>Normalised to lower-kebab-case: {@code "Credit_Cards"} becomes {@code "credit-cards"}. That
     * way the key that reaches a client is always a valid identifier regardless of how the node was
     * named.</p>
     */
    private static String screenKey(final Page page) {
        return kebab(page.getName());
    }

    /**
     * Lower-kebab-cases a string: whitespace and underscores collapse to a single hyphen, the
     * result is lower-cased, and leading/trailing hyphens are trimmed. Non-blank input always
     * yields non-blank output.
     */
    private static String kebab(final String value) {
        if (StringUtils.isBlank(value)) {
            return value;
        }
        return value.trim().toLowerCase(Locale.ROOT)
                .replaceAll("[\\s_]+", "-")
                .replaceAll("-{2,}", "-")
                .replaceAll("^-+|-+$", "");
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

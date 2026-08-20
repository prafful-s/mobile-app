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
    private int maxDepth;
    private int cacheMaxAge;

    // Built once at activation rather than per request: they are stateless, and rebuilding them for
    // every page re-parsed the alias table and re-read configuration that cannot have changed.
    private ComponentTreeWalker walker;
    private SiteHostResolver hostResolver;
    private SiteBranding siteBranding;
    private ProductCatalog productCatalog;

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
        this.maxDepth = Math.max(1, config.maxDepth());
        this.cacheMaxAge = Math.max(0, config.cacheMaxAge());

        // Rules ship inside the bundle, so they are read once here rather than per request.
        this.walker = new ComponentTreeWalker(modelFactory, urlService, formSchemaService,
                new StyleResolver(config.styleTokens()), ComponentMappings.load());
        this.hostResolver = new SiteHostResolver(authorHostSegment, publishHostSegment);
        this.siteBranding = SiteBranding.load();
        this.productCatalog = new ProductCatalog(ProductMappings.load());
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
        return export(requested, ExportOptions.of(false));
    }

    @Override
    public Map<String, Object> export(final Page requested, final ExportOptions options) {
        final ExportOptions opts = options == null ? ExportOptions.of(false) : options;
        // Whatever path the caller names is the root of this export: everything under it is the app.
        // There is no appId hunt up the tree - the provided path stands on its own.
        final Page envelopeSource = requested;
        final ValueMap rootProps = envelopeSource.getProperties();

        // The app identifier is derived from the path, not authored: the node name of the provided
        // root (e.g. 'secur-financial').
        final String appId = envelopeSource.getName();

        // The screens, and the home screen among them. The provided root can be a bare site
        // container (holding language-masters and nothing of its own), so the home screen is the
        // first real content page below it - nav resolution, home-screen inclusion and the
        // isRootScreen flag are all relative to this, not to the container above it.
        final List<Page> screenList = collectScreens(envelopeSource);
        final Page homeRoot = screenList.isEmpty() ? envelopeSource : screenList.get(0);

        // Resolved once per export: every screen in one response belongs to the same site, so the
        // placeholders lookup should not be repeated per page.
        final String assetHost = deliveryHost(envelopeSource);

        // Two different questions, deliberately answered by two different methods. The menu is what
        // an author curated on the nav page; the screens are every page the app can reach. A page
        // can be a screen without being in the menu - a plan detail page reached from a card, say -
        // and screen meta records that as includeInNavigation: false.
        final List<String> menu = buildMenu(menuPages(homeRoot));

        final Resource extras = childResource(envelopeSource, SchemaConstants.NN_EXTRAS);

        final Map<String, Object> app = new LinkedHashMap<>();
        app.put(SchemaConstants.KEY_META,
                buildMeta(envelopeSource, homeRoot, appId, rootProps, menu));
        // No AEM source for either yet, but the target shape is fixed, so a client can rely on
        // the keys being present rather than testing for them.
        app.put(SchemaConstants.KEY_RULES, Collections.emptyList());
        app.put(SchemaConstants.KEY_MODULES, Collections.emptyList());
        app.put(SchemaConstants.KEY_SCREENS,
                buildScreens(screenList, homeRoot, assetHost, opts, menu));
        app.put(SchemaConstants.KEY_BRANDING, buildBranding(envelopeSource, assetHost));
        app.put(SchemaConstants.KEY_PRODUCTS, productCatalog.export(envelopeSource, assetHost));
        app.put(SchemaConstants.KEY_INTEGRATIONS,
                readJsonObject(extras, SchemaConstants.PN_INTEGRATIONS_JSON));
        app.put(SchemaConstants.KEY_PROFILE_VIEWER,
                readJsonObject(extras, SchemaConstants.PN_PROFILE_VIEWER_JSON));

        return app;
    }

    /* ------------------------------------------------------------------ meta */

    private Map<String, Object> buildMeta(final Page root, final Page homeRoot, final String appId,
                                          final ValueMap props, final List<String> menu) {
        final Map<String, Object> navigation = buildNavigation(props, menu);

        // The entire meta is built in DSN reference field order via EnvelopeMeta. The nav page's
        // search block, when authored, takes over meta.rightIcon from the placeholders sheet - so a
        // header search icon can be configured by dropping a search block into the nav, no
        // placeholder-sheet round-trip needed. The nav lives under the home screen, not the site
        // container, so it is resolved from there.
        return EnvelopeMeta.buildFullV2Meta(root, appId, props, navigation,
                navPageRightIcon(homeRoot));
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

    private Map<String, Object> buildScreens(final List<Page> pages, final Page homeRoot,
                                            final String assetHost, final ExportOptions options,
                                            final List<String> menu) {
        final Map<String, Object> screens = new LinkedHashMap<>();
        for (final Page page : pages) {
            final String key = screenKey(page);
            if (screens.containsKey(key)) {
                // Same node name reused under two different parents (siblings can't collide - JCR
                // forbids it) - the later one would otherwise silently clobber the former in the
                // response with no trace of what happened to it.
                LOG.warn("Screen key '{}' from {} collides with an already-exported screen - "
                        + "overwriting it. Give one of the pages a distinct node name.", key, page.getPath());
            }
            screens.put(key, buildScreen(page, homeRoot, assetHost, options, menu));
        }
        return screens;
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

        screen.put(SchemaConstants.KEY_META, EnvelopeMeta.screenMeta(page, root, screenKey(page), menu));
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
        // applyScreenExclusions=true drops unmapped components (fileUpload/iframe/loan-calculator/
        // search) from screens.content[]. Nav-page walk above deliberately does not exclude - it
        // needs 'search' visible so meta.rightIcon can pick it up.
        final List<Map<String, Object>> content =
                walker.componentsOf(page, assetHost, true, true);
        screen.put(SchemaConstants.KEY_CONTENT,
                options.isReduced() ? keepEnabledComponents(content) : content);
        return screen;
    }

    /**
     * Temporary reduced-output filter: keeps only the components whose mappings are currently
     * enabled ({@code banner}, {@code card}, {@code htmlText}, {@code form}) and drops panels left
     * empty once their other children are removed. Extend {@link #ENABLED_COMPONENTS} as more
     * mappings are re-enabled; remove this call and method when the full component set is restored.
     */
    private static final Set<String> ENABLED_COMPONENTS = Set.of("banner", "card", "htmlText", "form", "list", "title", "image", "button", "productCard", "cartSummary");

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> keepEnabledComponents(final List<Map<String, Object>> tree) {
        final List<Map<String, Object>> kept = new ArrayList<>();
        for (final Map<String, Object> component : tree) {
            final Object meta = component.get(SchemaConstants.KEY_META);
            final Object name = meta instanceof Map
                    ? ((Map<String, Object>) meta).get(SchemaConstants.KEY_COMPONENT) : null;
            // A form's content is its ordered field list, not child components - keep the whole
            // block rather than descending into it as though it were a panel.
            if (ENABLED_COMPONENTS.contains(name)) {
                kept.add(component);
                continue;
            }
            final Object content = component.get(SchemaConstants.KEY_CONTENT);
            if (content instanceof List) {
                final List<Map<String, Object>> children =
                        keepEnabledComponents((List<Map<String, Object>>) content);
                if (!children.isEmpty()) {
                    component.put(SchemaConstants.KEY_CONTENT, children);
                    kept.add(component);
                }
            }
        }
        return kept;
    }


    /* -------------------------------------------------------------- branding */

    private Map<String, Object> buildBranding(final Page root, final String assetHost) {
        // Branding never derives from a page, so the shipped per-site object wins and is emitted
        // verbatim; the authored node is only a fallback for sites that ship none.
        final Map<String, Object> shipped = siteBranding == null ? null : siteBranding.forPage(root);
        if (shipped != null) {
            return prefixAssets(shipped, assetHost);
        }
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

    /**
     * Walks the shipped branding object making logo paths absolute, in place per level. The prefixer
     * keys on the value, so colour tokens and absolute logo URLs are left untouched and only
     * repository paths are rewritten - no need to know which keys are which.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> prefixAssets(final Map<String, Object> branding, final String assetHost) {
        final Map<String, Object> result = new LinkedHashMap<>();
        for (final Map.Entry<String, Object> entry : branding.entrySet()) {
            final Object value = entry.getValue();
            if (value instanceof Map) {
                result.put(entry.getKey(), prefixAssets((Map<String, Object>) value, assetHost));
            } else if (value instanceof String) {
                result.put(entry.getKey(), prefixAsset((String) value, assetHost));
            } else {
                result.put(entry.getKey(), value);
            }
        }
        return result;
    }

    /* ----------------------------------------------------------------- utils */

    /**
     * Every page the app can reach: the home screen and its descendants, minus the fragments that
     * are furniture rather than destinations.
     *
     * <p>The provided path may be a bare site container - a page whose only job is to hold
     * {@code language-masters} - so it is emitted only when it carries authored content of its own.
     * Otherwise the walk descends through it, and through the structural containers below it, until
     * it reaches the first real content page, which becomes the home screen.</p>
     *
     * <p>{@code header}, {@code footer}, the nav fragment, {@code placeholders},
     * {@code language-masters} and {@code redirects} are excluded by name through
     * {@code excludedPageNames}, along with any page flagged with {@code excludeFromApp}. Excluded
     * pages are still descended into, so a container never hides the screens beneath it.</p>
     */
    private List<Page> collectScreens(final Page root) {
        final List<Page> pages = new ArrayList<>();
        if (isScreen(root) && !isBareContainer(root)) {
            pages.add(root);
            collectDescendants(root, pages, 1);
        } else {
            // Transparent: descend without emitting and without spending a depth level, so the
            // depth limit measures content depth below the home screen rather than repository depth.
            collectDescendants(root, pages, 0);
        }
        return pages;
    }

    private void collectDescendants(final Page parent, final List<Page> sink, final int depth) {
        if (parent == null || depth > maxDepth) {
            return;
        }
        final Iterator<Page> children = parent.listChildren();
        while (children.hasNext()) {
            final Page child = children.next();
            if (child.getProperties().get(SchemaConstants.PN_EXCLUDE_FROM_APP, false)) {
                // Explicitly removed from the app, subtree and all.
                continue;
            }
            if (!isScreen(child) || child.getContentResource() == null) {
                // Furniture, a structural container, or a page with no content of its own: never a
                // screen, but its descendants may be - so descend without spending a depth level.
                collectDescendants(child, sink, depth);
                continue;
            }
            sink.add(child);
            collectDescendants(child, sink, depth + 1);
        }
    }

    /** A page that could stand as a screen: not furniture, not excluded from the app. */
    private boolean isScreen(final Page page) {
        return page != null
                && !excludedPageNames.contains(page.getName())
                && !page.getProperties().get(SchemaConstants.PN_EXCLUDE_FROM_APP, false);
    }

    /**
     * A page acting purely as a structural container: it has no authored components of its own and
     * every child page is itself an excluded container or furniture (e.g. {@code language-masters},
     * {@code placeholders}). Such a page is a wrapper rather than a destination, so the export
     * descends through it to the home screen below.
     *
     * <p>Deliberately narrow: the home screen often has child screens but no components of its own,
     * and it must still be emitted. Only a page whose children are <em>all</em> structural counts as
     * a container, which is what distinguishes a bare site root from the home page beneath it.</p>
     */
    private boolean isBareContainer(final Page page) {
        if (page.getContentResource() == null) {
            return true;
        }
        if (!walker.componentsOf(page, null).isEmpty()) {
            return false;
        }
        final Iterator<Page> children = page.listChildren();
        if (!children.hasNext()) {
            // An empty leaf page is still a screen, just an empty one.
            return false;
        }
        while (children.hasNext()) {
            if (isScreen(children.next())) {
                return false;
            }
        }
        return true;
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
}

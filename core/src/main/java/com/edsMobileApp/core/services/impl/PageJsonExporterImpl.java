package com.edsMobileApp.core.services.impl;

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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.edsMobileApp.core.models.impl.AbstractComponentModel;
import com.edsMobileApp.core.schema.SchemaConstants;
import com.edsMobileApp.core.services.ExportOptions;
import com.edsMobileApp.core.services.FormSchemaService;
import com.edsMobileApp.core.services.PageJsonExporter;
import com.edsMobileApp.core.services.UrlService;
import com.day.cq.wcm.api.Page;
import com.day.cq.wcm.api.PageManager;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

@Component(service = PageJsonExporter.class)
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

    private String[] navPageNames;
    private Set<String> excludedPageNames;
    private boolean includeHomeScreen;
    private int maxDepth;
    private boolean overrideEnabled;
    private String overridePropertyName;

    // Built once at activation rather than per request: they are stateless, and rebuilding them for
    // every page re-parsed the alias table and re-read configuration that cannot have changed.
    private ComponentTreeWalker walker;
    private SiteBranding siteBranding;
    private SiteProfileViewer siteProfileViewer;
    private SiteIntegrations siteIntegrations;
    private SiteDefaults siteDefaults;
    private ProductCatalog productCatalog;

    @Activate
    protected void activate(final Map<String, Object> properties) {
        final ContentBridgeSettings config = ContentBridgeSettings.load(properties);
        this.navPageNames = config.navPageNames();
        this.excludedPageNames = new HashSet<>(Arrays.asList(config.excludedPageNames()));
        this.includeHomeScreen = config.includeHomeScreen();
        this.maxDepth = config.maxDepth();
        this.overrideEnabled = config.overrideEnabled();
        this.overridePropertyName = config.overridePropertyName();

        // Rules ship inside the bundle, so they are read once here rather than per request.
        this.walker = new ComponentTreeWalker(modelFactory, urlService, formSchemaService,
                new StyleResolver(config.styleTokens()), ComponentMappings.load());
        this.siteBranding = SiteBranding.load();
        this.siteProfileViewer = SiteProfileViewer.load();
        this.siteIntegrations = SiteIntegrations.load();
        this.siteDefaults = SiteDefaults.load();
        this.productCatalog = new ProductCatalog(ProductMappings.load());
        this.walker.setSiteDefaults(this.siteDefaults);
    }

    @Override
    public boolean isOverrideEnabled() {
        return overrideEnabled;
    }

    @Override
    public String getOverridePropertyName() {
        return overridePropertyName;
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
        final String assetHost = deliveryHost();

        // Two different questions, deliberately answered by two different methods. The menu is what
        // an author curated on the nav page; the screens are every page the app can reach. A page
        // can be a screen without being in the menu - a plan detail page reached from a card, say -
        // and screen meta records that as includeInNavigation: false.
        final List<String> menu = buildMenu(menuPages(homeRoot));
        final Map<String, Object> navigation = buildNavigation(rootProps, menu, screenList, homeRoot);
        final Map<String, String> screenPresets = screenPresets(navigation);

        // Set before screens are walked so that any form's schema-declared secondary link (built by
        // ComponentTreeWalker/FormBlockBuilder while walking each page below, e.g. sign-in's "Create
        // an account") points at the same screen this export's own nav already resolved for that role.
        walker.setNavScreensByRole(Map.of(
                SchemaConstants.PN_LOGIN_SCREEN, StringUtils.defaultString(
                        (String) navigation.get(SchemaConstants.PN_LOGIN_SCREEN)),
                SchemaConstants.PN_PROFILE_SCREEN, StringUtils.defaultString(
                        (String) navigation.get(SchemaConstants.PN_PROFILE_SCREEN)),
                SchemaConstants.PN_REGISTRATION_SCREEN, StringUtils.defaultString(
                        (String) navigation.get(SchemaConstants.PN_REGISTRATION_SCREEN))));

        // Every screen key the app will actually expose, gathered once so a Cards-block-to-list
        // conversion (see ComponentTreeWalker) can tell a real screen link from a dangling one
        // without re-walking the page tree per block.
        final Set<String> knownScreenKeys = new HashSet<>();
        for (final Page screen : screenList) {
            knownScreenKeys.add(screenKey(screen));
        }

        final Resource extras = childResource(envelopeSource, SchemaConstants.NN_EXTRAS);

        // Filled in by the Cards-to-list conversion as screens are walked (see
        // ComponentTreeWalker#cardsBlockAsList): screen key -> the raw image path of a card that
        // links to it. Applied to screens.*.meta.thumbnail in one pass below, once every screen has
        // been built, since a card on one page may resolve to a screen built earlier or later in
        // screenList's order.
        final Map<String, String> screenThumbnailOverrides = new LinkedHashMap<>();

        final Map<String, Object> app = new LinkedHashMap<>();
        app.put(SchemaConstants.KEY_META,
                buildMeta(envelopeSource, homeRoot, appId, rootProps, navigation));
        // No AEM source for either yet, but the target shape is fixed, so a client can rely on
        // the keys being present rather than testing for them.
        app.put(SchemaConstants.KEY_RULES, Collections.emptyList());
        app.put(SchemaConstants.KEY_MODULES, Collections.emptyList());
        final Map<String, Object> screens = buildScreens(screenList, homeRoot, assetHost, opts, menu,
                screenPresets, knownScreenKeys, screenThumbnailOverrides);
        applyScreenThumbnailOverrides(screens, screenThumbnailOverrides, assetHost);
        app.put(SchemaConstants.KEY_SCREENS, screens);
        app.put(SchemaConstants.KEY_BRANDING, buildBranding(envelopeSource, assetHost));
        app.put(SchemaConstants.KEY_PRODUCTS, productCatalog.export(envelopeSource, assetHost));
        app.put(SchemaConstants.KEY_INTEGRATIONS, buildIntegrations(envelopeSource, extras));
        app.put(SchemaConstants.KEY_PROFILE_VIEWER, buildProfileViewer(envelopeSource, extras));

        return app;
    }

    /* ------------------------------------------------------------------ meta */

    private Map<String, Object> buildMeta(final Page root, final Page homeRoot, final String appId,
                                          final ValueMap props, final Map<String, Object> navigation) {
        // The entire meta is built in DSN reference field order via EnvelopeMeta. The nav page's
        // search block, when authored, takes over meta.rightIcon from the placeholders sheet - so a
        // header search icon can be configured by dropping a search block into the nav, no
        // placeholder-sheet round-trip needed. The nav lives under the home screen, not the site
        // container, so it is resolved from there.
        //
        // A site's defaults.json may pin rightIcon to a fixed value (e.g. Luma wants the cart
        // shortcut in the app bar even though its nav authors a search block) - that override wins
        // over the nav-page lookup entirely, so a site opting in never needs its search
        // configuration to match what its app bar shows.
        //
        // metaDefaults (brand/thumbnail/template/vertical/rightIcon/rightIconTarget) is resolved
        // per site from defaults.json rather than once at activation from a shared OSGi config -
        // otherwise every site would fall back to whichever one instance happened to be configured.
        final EnvelopeMeta.MetaDefaults metaDefaults = EnvelopeMeta.MetaDefaults.of(
                SiteDefaults.objectMap(siteDefaults.forPage(root), SiteDefaults.KEY_META));
        return EnvelopeMeta.buildFullV2Meta(root, appId, props, navigation,
                resolveRightIcon(root, homeRoot), metaDefaults);
    }

    /**
     * @return the site's pinned {@code rightIcon} override from {@code defaults.json} when one is
     *         configured, otherwise whatever the nav page's own search block resolves to (or
     *         {@code null}, letting {@link EnvelopeMeta} fall through to the placeholders sheet /
     *         compiled-in cart default)
     */
    private Map<String, Object> resolveRightIcon(final Page root, final Page homeRoot) {
        final Map<String, Object> pinned = SiteDefaults.objectMap(
                siteDefaults.forPage(root), SiteDefaults.KEY_RIGHT_ICON);
        return pinned.isEmpty() ? navPageRightIcon(homeRoot) : pinned;
    }

    /**
     * The {@code preset} each screen carries, keyed by screen name, derived from the navigation
     * roles rather than an authored page property. The screen the nav points at as its login,
     * registration or profile destination is tagged {@code login}, {@code registration} or
     * {@code profile} respectively - the same identity the client uses to switch on those screens.
     * A screen with no navigation role gets no entry here and stays blank.
     */
    private static Map<String, String> screenPresets(final Map<String, Object> navigation) {
        final Map<String, String> presets = new LinkedHashMap<>();
        addScreenPreset(presets, navigation.get(SchemaConstants.PN_LOGIN_SCREEN), "login");
        addScreenPreset(presets, navigation.get(SchemaConstants.PN_REGISTRATION_SCREEN), "registration");
        addScreenPreset(presets, navigation.get(SchemaConstants.PN_PROFILE_SCREEN), "profile");
        // cart and review-order are fixed system screens like the roles above, not authored - their
        // preset per the LUMA MOBILE reference is a screen-specific value rather than the generic
        // DEFAULT_SCREEN_PRESET.
        presets.putIfAbsent("cart", "cart");
        presets.putIfAbsent("review-order", "reviewOrder");
        return presets;
    }

    private static void addScreenPreset(final Map<String, String> presets, final Object screenKey,
                                        final String preset) {
        if (screenKey instanceof String && StringUtils.isNotBlank((String) screenKey)) {
            presets.putIfAbsent((String) screenKey, preset);
        }
    }

    private Map<String, Object> buildNavigation(final ValueMap props, final List<String> menu,
                                                final List<Page> screens, final Page homeRoot) {
        final Map<String, Object> navigation = new LinkedHashMap<>();
        navigation.put("menu", menu);
        navigation.put("loginScreen",
                resolveScreenRef(props, SchemaConstants.PN_LOGIN_SCREEN, "sign-in", screens, homeRoot));
        // No dedicated profile page exists here, so the logged-in account icon lands on settings.
        navigation.put("profileScreen",
                resolveScreenRef(props, SchemaConstants.PN_PROFILE_SCREEN, "settings", screens, homeRoot));
        navigation.put("authorizedMenu",
                Arrays.asList(props.get(SchemaConstants.PN_AUTHORIZED_MENU, new String[0])));
        navigation.put("registrationScreen",
                resolveScreenRef(props, SchemaConstants.PN_REGISTRATION_SCREEN, "create-account", screens, homeRoot));
        return navigation;
    }

    /**
     * The screen an authored property names, or - when none is authored - the screen whose own
     * content actually carries the given form, found by scanning rather than hard-coding a page
     * name. The home screen and any {@code *-test} page are skipped; a page named exactly after the
     * form (e.g. {@code sign-in}) wins over any other page carrying it, so the canonical screen is
     * chosen regardless of authoring order. Falls back to the form name itself when none carries it.
     */
    private String resolveScreenRef(final ValueMap props, final String property,
                                    final String formName, final List<Page> screens,
                                    final Page homeRoot) {
        final String authored = props.get(property, String.class);
        if (StringUtils.isNotBlank(authored)) {
            return authored;
        }
        String firstMatch = null;
        for (final Page page : screens) {
            if (page.equals(homeRoot) || page.getName().endsWith("-test")) {
                continue;
            }
            if (!walker.formSchemasOn(page).contains(formName)) {
                continue;
            }
            final String key = screenKey(page);
            if (formName.equals(key)) {
                return key;
            }
            if (firstMatch == null) {
                firstMatch = key;
            }
        }
        return firstMatch != null ? firstMatch : formName;
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
                                            final List<String> menu,
                                            final Map<String, String> screenPresets,
                                            final Set<String> knownScreenKeys,
                                            final Map<String, String> screenThumbnailOverrides) {
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
            screens.put(key, buildScreen(page, homeRoot, assetHost, options, menu,
                    screenPresets.getOrDefault(key, StringUtils.EMPTY), knownScreenKeys,
                    screenThumbnailOverrides));
        }
        return screens;
    }

    /**
     * Fills in {@code screens.*.meta.thumbnail} with the image of a card that links to it, for
     * every screen a Cards-to-list conversion recorded one for - see
     * {@link ComponentTreeWalker#cardsBlockAsList}. That card's image is the only source a screen's
     * thumbnail has: it is what a client actually sees rendered wherever the screen appears inside a
     * list.
     */
    @SuppressWarnings("unchecked")
    private void applyScreenThumbnailOverrides(final Map<String, Object> screens,
                                               final Map<String, String> overrides,
                                               final String assetHost) {
        for (final Map.Entry<String, String> override : overrides.entrySet()) {
            final Object screen = screens.get(override.getKey());
            if (!(screen instanceof Map)) {
                continue;
            }
            final Object meta = ((Map<String, Object>) screen).get(SchemaConstants.KEY_META);
            if (meta instanceof Map) {
                ((Map<String, Object>) meta).put("thumbnail", prefixAsset(override.getValue(), assetHost));
            }
        }
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
                                           final ExportOptions options, final List<String> menu,
                                           final String rolePreset, final Set<String> knownScreenKeys,
                                           final Map<String, String> screenThumbnailOverrides) {
        final Map<String, Object> screen = new LinkedHashMap<>();

        final String key = screenKey(page);
        final Map<String, Object> meta = EnvelopeMeta.screenMeta(page, root, key, menu, rolePreset);
        screen.put(SchemaConstants.KEY_META, meta);
        screen.put(SchemaConstants.KEY_EDITOR, screenEditor(key));
        // applyScreenExclusions=true drops unmapped components (fileUpload/iframe/loan-calculator/
        // search) from screens.content[]. Nav-page walk above deliberately does not exclude - it
        // needs 'search' visible so meta.rightIcon can pick it up.
        List<Map<String, Object>> content = walker.componentsOf(page, assetHost, true, true,
                knownScreenKeys, screenThumbnailOverrides);
        // A screenList item shows the target screen's thumbnail (see luma-mobile.json's
        // beginners-luck/warming-up/keep-moving) - filled in below from whichever card links to this
        // screen (see ComponentTreeWalker#cardsBlockAsList / applyScreenThumbnailOverrides). Left
        // blank when no card links here, same as every other unset meta field.
        meta.put("thumbnail", StringUtils.EMPTY);
        if ("cart".equals(key)) {
            addCartActionsPanel(content, page);
        } else if ("review-order".equals(key)) {
            content = reviewOrderContent(content, page);
        } else if ("checkout".equals(key)) {
            patchCheckoutFormMeta(content, page);
        }
        screen.put(SchemaConstants.KEY_CONTENT,
                options.isReduced() ? keepEnabledComponents(content) : content);
        return screen;
    }

    /**
     * The screen's {@code editor} block: canvas layout (zIndex/position) authored in DSN Studio.
     * AEM has no equivalent, so every screen gets a zero-valued skeleton by default - the client
     * sees the shape it expects, and the zeros read as "unpositioned" rather than "missing". The
     * cart screen is a fixed system screen like its action panel above; its specific canvas
     * coordinates come from the LUMA MOBILE reference rather than any AEM source.
     */
    private static Map<String, Object> screenEditor(final String screenKey) {
        final boolean isCart = "cart".equals(screenKey);
        final Map<String, Object> position = new LinkedHashMap<>();
        position.put("top", isCart ? 275 : 0);
        position.put("left", isCart ? 600 : 0);
        final Map<String, Object> editor = new LinkedHashMap<>();
        editor.put("zIndex", isCart ? 5 : 0);
        editor.put("position", position);
        return editor;
    }

    /**
     * Nests the cart screen's static "Empty cart" / "Review order" action row inside the panel
     * that already wraps {@code cartSummary} - the reference shape is one outer panel holding both,
     * not two sibling panels. Falls back to appending at the top level when the page's content does
     * not follow that shape (e.g. the cart block sits outside any section), so a re-authored cart
     * page still gets the action row rather than silently losing it.
     */
    @SuppressWarnings("unchecked")
    private void addCartActionsPanel(final List<Map<String, Object>> content, final Page cartPage) {
        for (final Map<String, Object> component : content) {
            final Object meta = component.get(SchemaConstants.KEY_META);
            if (!(meta instanceof Map)
                    || !"panel".equals(((Map<String, Object>) meta).get(SchemaConstants.KEY_COMPONENT))) {
                continue;
            }
            final Object innerContent = component.get(SchemaConstants.KEY_CONTENT);
            if (!(innerContent instanceof List)) {
                continue;
            }
            final List<Map<String, Object>> inner = (List<Map<String, Object>>) innerContent;
            final boolean holdsCartSummary = inner.stream().anyMatch(c -> {
                final Object innerMeta = c.get(SchemaConstants.KEY_META);
                return innerMeta instanceof Map
                        && "cartSummary".equals(((Map<String, Object>) innerMeta).get(SchemaConstants.KEY_COMPONENT));
            });
            if (holdsCartSummary) {
                inner.add(cartActionsPanel(cartPage, inner.size()));
                return;
            }
        }
        // No panel holding cartSummary found - fall back to a top-level sibling so the action row
        // still appears somewhere rather than being dropped.
        content.add(cartActionsPanel(cartPage, content.size()));
    }

    /**
     * The cart screen's "Empty cart" / "Review order" action row. No AEM block backs it - the
     * authored {@code cart} block already maps entirely onto {@code cartSummary} - so this is the
     * LUMA MOBILE reference's static panel, added verbatim rather than derived from content.
     * Ids are generated the same way real components are, from a stable synthetic path, so a
     * rebuild does not renumber them.
     */
    private Map<String, Object> cartActionsPanel(final Page cartPage, final int order) {
        final String base = cartPage.getPath() + "/cart-actions";

        final Map<String, Object> emptyCartButton = new LinkedHashMap<>();
        emptyCartButton.put(SchemaConstants.KEY_META, buttonMeta(base + "/empty-cart", 0));
        emptyCartButton.put(SchemaConstants.KEY_CONTENT, buttonContent(
                "Empty cart", "emptyCart", "", "", "contained-tonal", "stretch"));

        final Map<String, Object> reviewOrderButton = new LinkedHashMap<>();
        reviewOrderButton.put(SchemaConstants.KEY_META, buttonMeta(base + "/review-order", 2));
        reviewOrderButton.put(SchemaConstants.KEY_CONTENT, buttonContent(
                "Review order", " --- ", "checkout", "", "contained", "stretch"));

        final Map<String, Object> meta = new LinkedHashMap<>();
        meta.put(SchemaConstants.KEY_ID, AbstractComponentModel.generateId(base));
        meta.put(SchemaConstants.KEY_ORDER, order);
        meta.put("layout", "horizontal");
        meta.put(SchemaConstants.KEY_COMPONENT, "panel");
        meta.put("showBorder", false);
        meta.put("itemsAlignment", "center");

        final Map<String, Object> styles = new LinkedHashMap<>();
        styles.put("gap", 16);
        styles.put("padding", 16);
        styles.put("alignItems", "center");
        styles.put("justifyContent", "center");

        final Map<String, Object> panel = new LinkedHashMap<>();
        panel.put(SchemaConstants.KEY_META, meta);
        panel.put(SchemaConstants.KEY_STYLES, styles);
        panel.put(SchemaConstants.KEY_CONTENT, List.of(emptyCartButton, reviewOrderButton));
        return panel;
    }

    private Map<String, Object> buttonMeta(final String syntheticPath, final int order) {
        final Map<String, Object> meta = new LinkedHashMap<>();
        meta.put(SchemaConstants.KEY_ID, AbstractComponentModel.generateId(syntheticPath));
        meta.put(SchemaConstants.KEY_ORDER, order);
        meta.put(SchemaConstants.KEY_COMPONENT, "button");
        return meta;
    }

    private Map<String, Object> buttonContent(final String label, final String events,
                                              final String linkTo, final String message,
                                              final String variant, final String alignment) {
        final Map<String, Object> content = new LinkedHashMap<>();
        content.put("label", label);
        content.put("events", events);
        content.put("linkTo", linkTo);
        content.put("message", message);
        content.put("variant", variant);
        content.put("alignment", alignment);
        return content;
    }

    /**
     * The review-order screen's content, flattened to exactly {@code [cartSummary, confirm button]}
     * with no wrapping panel - unlike the cart screen, the reference here has both as direct
     * siblings. The authored {@code order-summary} block already maps onto {@code cartSummary}
     * (see {@code orderSummary.json}); {@link #walker} still wraps its section into a panel like any
     * other single-block section, so that wrapper is unwound here rather than emitted. The confirm
     * button has no AEM block behind it at all - the LUMA MOBILE reference's static content, added
     * verbatim. Falls back to the walker's own content (plus the button) if no cartSummary is found,
     * so a re-authored page still gets something rather than an empty screen.
     */
    private List<Map<String, Object>> reviewOrderContent(final List<Map<String, Object>> content,
                                                          final Page page) {
        final Map<String, Object> cartSummary = findComponent(content, "cartSummary");
        final List<Map<String, Object>> result = new ArrayList<>();
        if (cartSummary != null) {
            asMeta(cartSummary.get(SchemaConstants.KEY_META)).put(SchemaConstants.KEY_ORDER, 0);
            result.add(cartSummary);
        } else {
            result.addAll(content);
        }
        result.add(confirmButton(page, result.size()));
        return result;
    }

    /**
     * Overrides the checkout screen's form meta with the LUMA MOBILE reference's values.
     * {@link FormBlockBuilder} derives {@code eventType}/{@code submitButtonLabel}/etc. purely from
     * the authored {@code checkout-shipping} block's own properties (its {@code buttoneventtype}
     * gives {@code eventType: "checkout"}) or its own hardcoded defaults - there is no authored
     * source for the reference's {@code commerce.checkouts} event name, its {@code checkoutForm} id,
     * or the {@code link} to the review-order screen after submit, so those are patched in here.
     * The reset button is dropped entirely (the reference form has none) rather than left hidden.
     * Fields (from {@code checkout-shipping.json}) and the component's own id/order are untouched.
     */
    /** Compiled-in fallback used when a site's {@code defaults.json} has no {@code checkoutForm} block. */
    private static final Map<String, Object> CHECKOUT_FORM_DEFAULTS = Map.of(
            "link", "review-order",
            "formId", "checkoutForm",
            "eventType", "commerce.checkouts",
            "webhookUrl", "",
            "toastMessage", "",
            "submitButtonLabel", "Checkout");

    private void patchCheckoutFormMeta(final List<Map<String, Object>> content, final Page page) {
        final Map<String, Object> form = findComponent(content, "form");
        if (form == null) {
            return;
        }
        final Map<String, Object> old = asMeta(form.get(SchemaConstants.KEY_META));

        // A site may override any subset of these in defaults.json.checkoutForm; everything absent
        // there falls back to the compiled-in reference values below.
        final Map<String, Object> overrides = SiteDefaults.objectMap(
                siteDefaults.forPage(page), SiteDefaults.KEY_CHECKOUT_FORM);

        // Rebuilt rather than mutated in place: the reference's key order (id, link, order, formId,
        // component, ...) differs from FormBlockBuilder's default, and insertion order is part of
        // this contract everywhere else in the export.
        final Map<String, Object> meta = new LinkedHashMap<>();
        meta.put(SchemaConstants.KEY_ID, old.get(SchemaConstants.KEY_ID));
        meta.put("link", override(overrides, "link"));
        meta.put(SchemaConstants.KEY_ORDER, old.get(SchemaConstants.KEY_ORDER));
        meta.put("formId", override(overrides, "formId"));
        meta.put(SchemaConstants.KEY_COMPONENT, old.get(SchemaConstants.KEY_COMPONENT));
        meta.put("eventType", override(overrides, "eventType"));
        meta.put("webhookUrl", overrides.containsKey("webhookUrl")
                ? overrides.get("webhookUrl") : old.get("webhookUrl"));
        meta.put("toastMessage", overrides.containsKey("toastMessage")
                ? overrides.get("toastMessage") : old.get("toastMessage"));
        meta.put("submitButtonLabel", override(overrides, "submitButtonLabel"));
        form.put(SchemaConstants.KEY_META, meta);
    }

    /** Reads {@code key} from {@code overrides}, falling back to {@link #CHECKOUT_FORM_DEFAULTS}. */
    private static Object override(final Map<String, Object> overrides, final String key) {
        return overrides.getOrDefault(key, CHECKOUT_FORM_DEFAULTS.get(key));
    }

    /** Depth-first search for the first component in {@code tree} whose meta.component matches. */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> findComponent(final List<Map<String, Object>> tree,
                                                      final String componentName) {
        for (final Map<String, Object> component : tree) {
            final Object meta = component.get(SchemaConstants.KEY_META);
            if (meta instanceof Map
                    && componentName.equals(((Map<String, Object>) meta).get(SchemaConstants.KEY_COMPONENT))) {
                return component;
            }
            final Object inner = component.get(SchemaConstants.KEY_CONTENT);
            if (inner instanceof List) {
                final Map<String, Object> found = findComponent((List<Map<String, Object>>) inner, componentName);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMeta(final Object value) {
        return (Map<String, Object>) value;
    }

    /** The review-order screen's static "Confirm" button - see {@link #reviewOrderContent}. */
    private Map<String, Object> confirmButton(final Page reviewOrderPage, final int order) {
        final Map<String, Object> meta = new LinkedHashMap<>();
        meta.put(SchemaConstants.KEY_ID,
                AbstractComponentModel.generateId(reviewOrderPage.getPath() + "/confirm"));
        meta.put(SchemaConstants.KEY_ORDER, order);
        meta.put(SchemaConstants.KEY_COMPONENT, "button");

        final Map<String, Object> styles = new LinkedHashMap<>();
        styles.put("marginTop", "16");

        final Map<String, Object> content = new LinkedHashMap<>();
        content.put("icon", "cash-check");
        content.put("label", "Confirm");
        content.put("events", "commerce.purchases");
        content.put("formId", "checkoutForm");
        content.put("linkTo", "home");
        content.put("message", "Thank you for your purchase! Your order has been successfully "
                + "placed. You'll receive a confirmation email shortly with tracking details. "
                + "Enjoy your shopping experience!");
        content.put("variant", "contained");
        content.put("alignment", "center");
        content.put("customEvent", "commerce.purchases");

        final Map<String, Object> button = new LinkedHashMap<>();
        button.put(SchemaConstants.KEY_META, meta);
        button.put(SchemaConstants.KEY_STYLES, styles);
        button.put(SchemaConstants.KEY_CONTENT, content);
        return button;
    }

    /**
     * Temporary reduced-output filter: keeps only the components whose mappings are currently
     * enabled ({@code banner}, {@code card}, {@code htmlText}, {@code form}) and drops panels left
     * empty once their other children are removed. Extend {@link #ENABLED_COMPONENTS} as more
     * mappings are re-enabled; remove this call and method when the full component set is restored.
     */
    private static final Set<String> ENABLED_COMPONENTS = Set.of("banner", "card", "htmlText", "form", "list",
            "title", "image", "button", "productCard", "cartSummary", "divider", "dropdown", "formSubmitButton",
            "flightSearch", "flightResults");

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


    /* -------------------------------------- integrations / profileViewer */

    /**
     * The {@code integrations} block: the shipped per-site object wins and is emitted verbatim,
     * with the authored {@code integrationsJson} blob as the only fallback for sites that ship
     * none. Mirrors {@link #buildBranding} - integrations is site configuration, not page content.
     */
    private Map<String, Object> buildIntegrations(final Page root, final Resource extras) {
        final Map<String, Object> shipped =
                siteIntegrations == null ? null : siteIntegrations.forPage(root);
        if (shipped != null) {
            return shipped;
        }
        return readJsonObject(extras, SchemaConstants.PN_INTEGRATIONS_JSON);
    }

    /**
     * The {@code profileViewer} block: the shipped per-site object wins and is emitted verbatim,
     * with the authored {@code profileViewerJson} blob as the only fallback for sites that ship
     * none. Mirrors {@link #buildBranding} - profileViewer is site configuration, not page content.
     */
    private Map<String, Object> buildProfileViewer(final Page root, final Resource extras) {
        final Map<String, Object> shipped =
                siteProfileViewer == null ? null : siteProfileViewer.forPage(root);
        if (shipped != null) {
            return shipped;
        }
        return readJsonObject(extras, SchemaConstants.PN_PROFILE_VIEWER_JSON);
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
     * <p>Every site uses the same instance-wide publish domain; no per-site override is consulted.</p>
     */
    private String deliveryHost() {
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
    /**
     * Authored node names that disagree with the reference's own screen key for the same screen.
     * The site's login page is authored as {@code sign-in}, but the LUMA MOBILE reference names
     * that screen {@code login} - the same key {@code navigation.loginScreen} points at - the same
     * way its {@code registration} screen's key matches {@code navigation.registrationScreen}
     * exactly. Likewise the authored {@code order-summary} page is the reference's
     * {@code review-order} screen. Rewritten after kebab-casing so {@code Sign_In}, {@code sign-in},
     * etc. all resolve.
     */
    /**
     * Compiled-in default screen-key rewrites: two AEM node names the client expects under a
     * different key. A site's {@code defaults.json} may override or extend this - see
     * {@link SiteDefaults}. Kept as a fallback so a site that ships no {@code defaults.json} still
     * gets the two rewrites the reference contract has always relied on.
     */
    private static final Map<String, String> SCREEN_KEY_ALIASES =
            Map.of("sign-in", "login", "order-summary", "review-order");

    private String screenKey(final Page page) {
        final String key = kebab(page.getName());
        final Map<String, String> siteAliases = SiteDefaults.stringMap(
                siteDefaults.forPage(page), SiteDefaults.KEY_SCREEN_KEY_ALIASES);
        if (siteAliases.containsKey(key)) {
            return siteAliases.get(key);
        }
        return SCREEN_KEY_ALIASES.getOrDefault(key, key);
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

package com.dept.contentbridge.core.services.impl;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import org.apache.commons.lang3.StringUtils;
import org.apache.sling.api.resource.ValueMap;

import com.dept.contentbridge.core.schema.SchemaConstants;
import com.day.cq.wcm.api.Page;

/**
 * Fills in the app-level and screen-level {@code meta} blocks of the v2 envelope.
 *
 * <p>The target contract carries twenty app fields and eleven screen fields, and they do not all
 * come from the same kind of place. Three sources, in order of preference:</p>
 *
 * <ol>
 *   <li><b>Derived</b> - AEM already knows it. {@code locale} is the page's language,
 *       {@code isRootScreen} is whether this page is the site root, and
 *       {@code navigationOrder} / {@code includeInNavigation} fall straight out of the menu that was
 *       already built from the authored navigation. Deriving these keeps them honest: a page added to
 *       the nav gets its order without anyone remembering to set a property.</li>
 *   <li><b>Authored</b> - a page property, or a key on the site's {@code placeholders} page for the
 *       app-wide values. Placeholders is where {@code hostname} already lives, so an author has one
 *       place to look rather than two.</li>
 *   <li><b>Defaulted</b> - a stable value for fields that describe the consuming app rather than the
 *       content. They are still emitted, because a client parsing a fixed shape should not have to
 *       treat a missing key differently from a default one.</li>
 * </ol>
 *
 * <p>v2 only. These keys are additions, and adding them to v1 would change a contract that is
 * already published.</p>
 */
final class EnvelopeMeta {

    /* ---- app meta: placeholder key -> emitted key, where the two differ in nothing but intent ---- */
    private static final String[] APP_PLACEHOLDER_KEYS = {
        "docs", "brand", "template", "vertical", "thumbnail", "description",
        "displayInbox", "authentication", "brandingMboxName", "targetRecMboxName",
    };

    /** App flags, emitted as real booleans so a client need not parse strings. */
    private static final String[] APP_FLAG_KEYS = {
        "enableTargetRec", "enableBrandingTargeting",
    };

    private static final String DEFAULT_AUTHENTICATION = "optional";
    private static final String DEFAULT_DISPLAY_INBOX = "mainNavigation";
    private static final String DEFAULT_SCREEN_TEMPLATE = "FreeformScreen";

    /** Suffix stripped from the AEM theme property when deriving a brand display label. */
    private static final Pattern THEME_SUFFIX = Pattern.compile("-theme$", Pattern.CASE_INSENSITIVE);

    /** Page property holding the category/preset for a screen (e.g. "home", "article"). */
    private static final String PN_PAGE_CATEGORY = "pageCategory";

    private EnvelopeMeta() {
        // static helpers only
    }

    /**
     * Builds the complete v2 app-level {@code meta} block in DSN reference field order.
     *
     * <p>Field order matches the DSN contract:
     * id, docs, type, brand, title, locale, useCart, template, vertical, rightIcon, thumbnail,
     * navigation, description, displayInbox, authentication, enableTargetRec, brandingMboxName,
     * targetRecMboxName, enableBrandingTargeting.</p>
     *
     * @param root       the site root page (placeholders page is resolved from here)
     * @param appId      the app identifier
     * @param props      {@code jcr:content} properties of the root page
     * @param navigation the already-built navigation block
     */
    static Map<String, Object> buildFullV2Meta(final Page root, final String appId,
                                               final ValueMap props,
                                               final Map<String, Object> navigation) {
        final Map<String, Object> meta = new LinkedHashMap<>();

        meta.put("id", appId);

        putIfPlaceholder(meta, root, "docs");

        meta.put("type", props.get(SchemaConstants.PN_APP_TYPE, SchemaConstants.DEFAULT_APP_TYPE));

        // brand: placeholders wins, then the theme name, then blank - but always present.
        final String brand = SiteHostResolver.placeholder(root, "brand");
        meta.put("brand", StringUtils.defaultString(StringUtils.defaultIfBlank(brand,
                themeToLabel(root.getProperties().get("theme", String.class)))));

        meta.put("title", props.get(SchemaConstants.PN_APP_TITLE,
                StringUtils.defaultIfBlank(root.getTitle(), root.getName())));

        meta.put(SchemaConstants.KEY_LOCALE, locale(root));

        meta.put("useCart", props.get(SchemaConstants.PN_USE_CART, false));

        // optional: template, vertical, rightIcon, thumbnail (from placeholders)
        putIfPlaceholder(meta, root, "template");
        putIfPlaceholder(meta, root, "vertical");
        meta.put("rightIcon", rightIcon(root));
        putIfPlaceholder(meta, root, "thumbnail");

        meta.put(SchemaConstants.KEY_NAVIGATION, navigation);

        // description: placeholders wins, then jcr:description, then blank - always present.
        meta.put("description", StringUtils.defaultString(StringUtils.defaultIfBlank(
                SiteHostResolver.placeholder(root, "description"), root.getDescription())));

        // Always-present fields the client switches on
        meta.put("displayInbox", StringUtils.defaultIfBlank(
                SiteHostResolver.placeholder(root, "displayInbox"), DEFAULT_DISPLAY_INBOX));
        meta.put("authentication", StringUtils.defaultIfBlank(
                SiteHostResolver.placeholder(root, "authentication"), DEFAULT_AUTHENTICATION));
        meta.put("enableTargetRec", flag(SiteHostResolver.placeholder(root, "enableTargetRec")));
        meta.put("brandingMboxName", StringUtils.defaultString(
                SiteHostResolver.placeholder(root, "brandingMboxName")));
        meta.put("targetRecMboxName", StringUtils.defaultString(
                SiteHostResolver.placeholder(root, "targetRecMboxName")));
        meta.put("enableBrandingTargeting",
                flag(SiteHostResolver.placeholder(root, "enableBrandingTargeting")));

        return meta;
    }

    /**
     * Adds v2 app fields to a pre-built meta block (legacy call path).
     *
     * @deprecated Prefer {@link #buildFullV2Meta} which emits keys in reference order.
     *             This method is retained so that any existing call sites compile without changes.
     */
    @Deprecated
    static void addAppMeta(final Map<String, Object> meta, final Page root) {
        for (final String key : APP_PLACEHOLDER_KEYS) {
            final String value = SiteHostResolver.placeholder(root, key);
            if (StringUtils.isNotBlank(value)) {
                meta.put(key, value);
            }
        }
        if (!meta.containsKey("brand")) {
            final String theme = root.getProperties().get("theme", String.class);
            if (StringUtils.isNotBlank(theme)) {
                meta.put("brand", themeToLabel(theme));
            }
        }
        if (!meta.containsKey("description")) {
            final String desc = root.getDescription();
            if (StringUtils.isNotBlank(desc)) {
                meta.put("description", desc);
            }
        }
        meta.putIfAbsent("authentication", DEFAULT_AUTHENTICATION);
        meta.putIfAbsent("displayInbox", DEFAULT_DISPLAY_INBOX);
        for (final String key : APP_FLAG_KEYS) {
            meta.put(key, flag(SiteHostResolver.placeholder(root, key)));
        }
        meta.putIfAbsent("brandingMboxName", StringUtils.EMPTY);
        meta.putIfAbsent("targetRecMboxName", StringUtils.EMPTY);
        final String locale = locale(root);
        if (StringUtils.isNotBlank(locale)) {
            meta.put(SchemaConstants.KEY_LOCALE, locale);
        }
        final Map<String, Object> rightIcon = rightIcon(root);
        if (!rightIcon.isEmpty()) {
            meta.put("rightIcon", rightIcon);
        }
    }

    /**
     * The app's locale, as a language tag.
     *
     * <p>Taken from the page's own language rather than parsed out of the path. AEM resolves that
     * from {@code jcr:language} where set and from the language root above it otherwise, which is
     * exactly the rule the rest of AEM uses - so a site under {@code /language-masters/en} and one
     * under {@code /us/en} both answer correctly without ContentBridge knowing either layout.</p>
     *
     * @return a tag such as {@code en-us}, or empty when the page has no language
     */
    static String locale(final Page page) {
        final Locale language = page.getLanguage(false);
        if (language == null || StringUtils.isBlank(language.getLanguage())) {
            return StringUtils.EMPTY;
        }
        return StringUtils.isBlank(language.getCountry())
                ? language.getLanguage().toLowerCase(Locale.ROOT)
                : language.getLanguage().toLowerCase(Locale.ROOT) + "-"
                        + language.getCountry().toLowerCase(Locale.ROOT);
    }

    /**
     * Builds a screen's {@code meta} in DSN reference field order, as a fixed skeleton.
     *
     * <p>Every key defined by the target contract is always present, so a client parsing a fixed
     * shape need not distinguish "author set nothing" from "exporter forgot the key". Missing
     * string properties emit as {@code ""}; {@code navigationOrder} is {@code null} for screens
     * off the menu, since {@code 0} would falsely claim the first slot.</p>
     *
     * <p>Field order: icon, name, event, title, preset, template, customEvent,
     * isRootScreen, navigationOrder, includeInNavigation.</p>
     *
     * @param page       the screen
     * @param root       the site root, used to decide whether this screen is the root one
     * @param screenKey  the key this screen is filed under, which is also its {@code name}
     * @param menu       the app menu, in order, so navigation membership and order are consistent
     *                   with what {@code meta.navigation.menu} already says
     */
    static Map<String, Object> screenMeta(final Page page, final Page root, final String screenKey,
                                          final List<String> menu) {
        final Map<String, Object> meta = new LinkedHashMap<>();
        final ValueMap props = page.getProperties();

        meta.put("icon", StringUtils.defaultString(props.get("icon", String.class)));
        meta.put("name", screenKey);
        meta.put("event", StringUtils.defaultString(props.get("event", String.class)));
        meta.put("title", StringUtils.defaultIfBlank(page.getTitle(), page.getName()));

        // preset: explicit prop wins, then pageCategory fallback, else blank.
        meta.put("preset", StringUtils.defaultString(StringUtils.defaultIfBlank(
                props.get("preset", String.class),
                props.get(PN_PAGE_CATEGORY, String.class))));

        meta.put("template", StringUtils.defaultIfBlank(
                props.get("template", String.class), DEFAULT_SCREEN_TEMPLATE));

        meta.put("customEvent", StringUtils.defaultString(props.get("customEvent", String.class)));

        meta.put("isRootScreen", StringUtils.equals(page.getPath(), root.getPath()));

        // navigationOrder before includeInNavigation to match reference order
        final int order = menu == null ? -1 : menu.indexOf(screenKey);
        meta.put("navigationOrder", order >= 0 ? Integer.valueOf(order) : null);
        meta.put("includeInNavigation", order >= 0);

        return meta;
    }

    /**
     * The optional icon in the app bar. Emitted only when an author named one - a right icon with no
     * target is a button that does nothing.
     */
    private static Map<String, Object> rightIcon(final Page root) {
        final String icon = StringUtils.defaultString(SiteHostResolver.placeholder(root, "rightIcon"));
        final String target = SiteHostResolver.placeholder(root, "rightIconTarget");

        // Emitted even when unset, for the same reason as the flat keys: the shape is fixed. An
        // icon with no name renders as nothing, which is what an unconfigured right icon should do.
        final Map<String, Object> rightIcon = new LinkedHashMap<>();
        rightIcon.put("icon", icon);
        rightIcon.put("name", icon);
        rightIcon.put("target", StringUtils.defaultIfBlank(target, icon));
        return rightIcon;
    }

    /** Adds a placeholder value to the map only when non-blank. */
    private static void putIfPlaceholder(final Map<String, Object> meta, final Page root,
                                         final String key) {
        // Always emitted, blank when the site sets nothing. The target contract is a fixed shape,
        // and a client should not have to distinguish "this site has no docs URL" from "this
        // exporter forgot to send one".
        meta.put(key, StringUtils.defaultString(SiteHostResolver.placeholder(root, key)));
    }

    /** Placeholder sheets store everything as text, so a flag arrives as "true" rather than true. */
    private static boolean flag(final String value) {
        return Boolean.parseBoolean(StringUtils.trimToEmpty(value));
    }

    /**
     * Converts an AEM theme token to a display label by stripping the conventional {@code -theme}
     * suffix and capitalising the initial character.
     *
     * <p>Examples: {@code wehealthcare-theme} → {@code Wehealthcare},
     * {@code binji-theme} → {@code Binji}.</p>
     *
     * <p>A properly authored brand name in the placeholders page always wins; this is a best-effort
     * fallback for sites that have not yet set one.</p>
     */
    static String themeToLabel(final String theme) {
        // Null- and blank-safe: brand is now always emitted, so this is called for every site,
        // including the ones that set no theme at all.
        if (StringUtils.isBlank(theme)) {
            return StringUtils.EMPTY;
        }
        final String base = THEME_SUFFIX.matcher(theme).replaceFirst("");
        final String label = StringUtils.isBlank(base) ? theme : base;
        return Character.toUpperCase(label.charAt(0)) + label.substring(1);
    }
}

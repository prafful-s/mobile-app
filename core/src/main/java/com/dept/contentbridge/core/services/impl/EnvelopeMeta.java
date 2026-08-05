package com.dept.contentbridge.core.services.impl;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

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

    /** Screen fields an author sets on the page itself. */
    private static final String[] SCREEN_PAGE_KEYS = {
        "icon", "event", "preset", "template", "thumbnail", "customEvent",
    };

    private static final String DEFAULT_AUTHENTICATION = "optional";
    private static final String DEFAULT_DISPLAY_INBOX = "mainNavigation";
    private static final String DEFAULT_SCREEN_TEMPLATE = "FreeformScreen";

    private EnvelopeMeta() {
        // static helpers only
    }

    /**
     * Adds the v2-only app fields to a {@code meta} block that already carries id, type, title,
     * useCart and navigation.
     *
     * @param meta the block to extend, in place
     * @param root the site root page, whose placeholders supply the authored values
     */
    static void addAppMeta(final Map<String, Object> meta, final Page root) {
        for (final String key : APP_PLACEHOLDER_KEYS) {
            final String value = SiteHostResolver.placeholder(root, key);
            if (StringUtils.isNotBlank(value)) {
                meta.put(key, value);
            }
        }

        // Emitted whether or not authored: the client switches on them, and an absent key is a
        // different thing to reason about than a false one.
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
     * Builds a screen's {@code meta}.
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

        meta.put("name", screenKey);
        meta.put("title", StringUtils.defaultIfBlank(page.getTitle(), page.getName()));

        for (final String key : SCREEN_PAGE_KEYS) {
            final String value = props.get(key, String.class);
            if (StringUtils.isNotBlank(value)) {
                meta.put(key, value);
            }
        }
        meta.putIfAbsent("template", DEFAULT_SCREEN_TEMPLATE);

        meta.put("isRootScreen", StringUtils.equals(page.getPath(), root.getPath()));

        // Derived from the menu rather than authored: the navigation page is what an author curates,
        // so a screen's place in it should not also have to be typed onto the page.
        final int order = menu == null ? -1 : menu.indexOf(screenKey);
        meta.put("includeInNavigation", order >= 0);
        meta.put("navigationOrder", order >= 0 ? Integer.valueOf(order) : null);

        return meta;
    }

    /**
     * The optional icon in the app bar. Emitted only when an author named one - a right icon with no
     * target is a button that does nothing.
     */
    private static Map<String, Object> rightIcon(final Page root) {
        final String icon = SiteHostResolver.placeholder(root, "rightIcon");
        if (StringUtils.isBlank(icon)) {
            return java.util.Collections.emptyMap();
        }
        final String target = SiteHostResolver.placeholder(root, "rightIconTarget");

        final Map<String, Object> rightIcon = new LinkedHashMap<>();
        rightIcon.put("icon", icon);
        rightIcon.put("name", icon);
        rightIcon.put("target", StringUtils.defaultIfBlank(target, icon));
        return rightIcon;
    }

    /** Placeholder sheets store everything as text, so a flag arrives as "true" rather than true. */
    private static boolean flag(final String value) {
        return Boolean.parseBoolean(StringUtils.trimToEmpty(value));
    }
}

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

    private static final String DEFAULT_AUTHENTICATION = "optional";
    private static final String DEFAULT_DISPLAY_INBOX = "mainNavigation";
    private static final String DEFAULT_SCREEN_TEMPLATE = "FreeformScreen";

    /**
     * Preset a content screen gets when neither an author nor a navigation role names one. The DSN
     * app renders every screen against a preset; a blank preset leaves it with no layout to apply,
     * so the reference falls back to {@code home} - the banner-plus-cards layout most screens use.
     */
    private static final String DEFAULT_SCREEN_PRESET = "home";

    /** Suffix stripped from the AEM theme property when deriving a brand display label. */
    private static final Pattern THEME_SUFFIX = Pattern.compile("-theme$", Pattern.CASE_INSENSITIVE);

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
        return buildFullV2Meta(root, appId, props, navigation, null);
    }

    /**
     * As {@link #buildFullV2Meta(Page, String, ValueMap, Map)}, plus an optional pre-resolved
     * {@code rightIcon} map. Passed in when the caller has already walked the nav page and found a
     * search block there - a mapped search component carries the same {@code {icon, name, target}}
     * shape the meta wants, so that reading wins over the placeholders-sheet fallback.
     */
    static Map<String, Object> buildFullV2Meta(final Page root, final String appId,
                                               final ValueMap props,
                                               final Map<String, Object> navigation,
                                               final Map<String, Object> navRightIcon) {
        return buildFullV2Meta(root, appId, props, navigation, navRightIcon, MetaDefaults.empty());
    }

    /**
     * As {@link #buildFullV2Meta(Page, String, ValueMap, Map, Map)}, plus site-level defaults from
     * OSGi configuration. These fill {@code brand}, {@code thumbnail} and {@code rightIcon} when the
     * placeholders sheet leaves them blank, so a site can be branded without any authored content -
     * an authored placeholder (or a nav search block, for the icon) still wins.
     */
    static Map<String, Object> buildFullV2Meta(final Page root, final String appId,
                                               final ValueMap props,
                                               final Map<String, Object> navigation,
                                               final Map<String, Object> navRightIcon,
                                               final MetaDefaults defaults) {
        final Map<String, Object> meta = new LinkedHashMap<>();

        // DIAGNOSTIC: every top-level meta field is hardcoded to the reference contract's values,
        // except navigation (still built from authoring), to validate whether the missing top-right
        // icon is caused by the authored/placeholder mapping. Revert once confirmed.
        meta.put("id", appId);
        meta.put("docs", "https://docs.adobedemo.com/projects/public-projects/securfinancial/mobile");
        meta.put("type", "mobile-v2");
        meta.put("brand", "SecurFinancial");
        meta.put("title", "SecurFinancial Mobile App");
        meta.put(SchemaConstants.KEY_LOCALE, "en-us");
        meta.put("useCart", false);
        meta.put("template", "mobile-v2/generic-fsi-v4");
        meta.put("vertical", "FSI");
        final Map<String, Object> hardRightIcon = new LinkedHashMap<>();
        hardRightIcon.put("icon", "shopping-cart");
        hardRightIcon.put("name", "shopping-cart");
        hardRightIcon.put("target", "cart");
        meta.put("rightIcon", hardRightIcon);
        meta.put("thumbnail", "https://demo-system-next.s3.eu-north-1.amazonaws.com/argon/upload/dev/securfinancial2-mobile/thumbnail");
        meta.put(SchemaConstants.KEY_NAVIGATION, navigation);
        meta.put("description", "Retail banking app");
        meta.put("displayInbox", "mainNavigation");
        meta.put("authentication", "optional");
        meta.put("enableTargetRec", false);
        meta.put("brandingMboxName", "");
        meta.put("targetRecMboxName", "");
        meta.put("enableBrandingTargeting", false);

        return meta;
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
                : (language.getLanguage().toLowerCase(Locale.ROOT) + "-"
                        + language.getCountry().toLowerCase(Locale.ROOT));
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
     * @param rolePreset the preset implied by this screen's navigation role (login/registration/
     *                   profile), or blank when it has none; an explicit {@code preset} prop wins
     */
    static Map<String, Object> screenMeta(final Page page, final Page root, final String screenKey,
                                          final List<String> menu, final String rolePreset) {
        final Map<String, Object> meta = new LinkedHashMap<>();
        final ValueMap props = page.getProperties();

        meta.put("icon", StringUtils.defaultString(StringUtils.defaultIfBlank(
                props.get("mobileIcon", String.class),
                props.get("icon", String.class))));
        meta.put("name", screenKey);
        meta.put("event", StringUtils.defaultString(props.get("event", String.class)));
        meta.put("title", StringUtils.defaultIfBlank(page.getTitle(), page.getName()));

        // preset: explicit prop wins, then the screen's navigation role, else the content default.
        meta.put("preset", StringUtils.firstNonBlank(
                props.get("preset", String.class),
                rolePreset,
                DEFAULT_SCREEN_PRESET));

        meta.put("template", screenTemplate(props));

        meta.put("customEvent", StringUtils.defaultString(props.get("customEvent", String.class)));

        meta.put("isRootScreen", StringUtils.equals(page.getPath(), root.getPath()));

        // navigationOrder before includeInNavigation to match reference order
        final int order = menu == null ? -1 : menu.indexOf(screenKey);
        meta.put("navigationOrder", order >= 0 ? Integer.valueOf(order) : null);
        meta.put("includeInNavigation", order >= 0);

        return meta;
    }

    /**
     * The screen template name the DSN app renders against. Every screen here is authored on the
     * same Franklin page template, whose PascalCased last segment ({@code Page}) is not a template
     * the app understands - it only knows the DSN screen templates ({@code FreeformScreen} and
     * friends). So the value defaults to {@link #DEFAULT_SCREEN_TEMPLATE}, with an explicit authored
     * {@code template} property winning as an override for the rare screen that needs a different one.
     */
    private static String screenTemplate(final ValueMap props) {
        final String override = props.get("template", String.class);
        return StringUtils.isNotBlank(override) ? override : DEFAULT_SCREEN_TEMPLATE;
    }

    /**
     * The optional icon in the app bar. Emitted only when an author named one - a right icon with no
     * target is a button that does nothing.
     */
    private static Map<String, Object> rightIcon(final Page root, final MetaDefaults defaults) {
        final String icon = StringUtils.defaultIfBlank(
                SiteHostResolver.placeholder(root, "rightIcon"), defaults.rightIcon());
        final String target = StringUtils.defaultIfBlank(
                SiteHostResolver.placeholder(root, "rightIconTarget"), defaults.rightIconTarget());

        // Emitted even when unset, for the same reason as the flat keys: the shape is fixed. An
        // icon with no name renders as nothing, which is what an unconfigured right icon should do.
        final Map<String, Object> rightIcon = new LinkedHashMap<>();
        rightIcon.put("icon", StringUtils.defaultString(icon));
        rightIcon.put("name", StringUtils.defaultString(icon));
        rightIcon.put("target", StringUtils.defaultString(StringUtils.defaultIfBlank(target, icon)));
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

    /**
     * Site-level meta defaults sourced from OSGi configuration, used when the placeholders sheet
     * leaves a field blank. Lets a site be branded through configuration rather than authored
     * content.
     */
    static final class MetaDefaults {

        private static final MetaDefaults EMPTY = new MetaDefaults("", "", "", "", "", "");

        private final String brand;
        private final String thumbnail;
        private final String rightIcon;
        private final String rightIconTarget;
        private final String template;
        private final String vertical;

        private MetaDefaults(final String brand, final String thumbnail,
                             final String rightIcon, final String rightIconTarget,
                             final String template, final String vertical) {
            this.brand = StringUtils.trimToEmpty(brand);
            this.thumbnail = StringUtils.trimToEmpty(thumbnail);
            this.rightIcon = StringUtils.trimToEmpty(rightIcon);
            this.rightIconTarget = StringUtils.trimToEmpty(rightIconTarget);
            this.template = StringUtils.trimToEmpty(template);
            this.vertical = StringUtils.trimToEmpty(vertical);
        }

        static MetaDefaults of(final String brand, final String thumbnail,
                               final String rightIcon, final String rightIconTarget,
                               final String template, final String vertical) {
            return new MetaDefaults(brand, thumbnail, rightIcon, rightIconTarget, template, vertical);
        }

        static MetaDefaults empty() {
            return EMPTY;
        }

        String brand() {
            return brand;
        }

        String thumbnail() {
            return thumbnail;
        }

        String rightIcon() {
            return rightIcon;
        }

        String rightIconTarget() {
            return rightIconTarget;
        }

        String template() {
            return template;
        }

        String vertical() {
            return vertical;
        }
    }
}

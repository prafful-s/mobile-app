package com.edsMobileApp.core.services.impl;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.day.cq.wcm.api.Page;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Per-site override for values the code otherwise carries as hardcoded defaults: the
 * {@code screenKeyAliases} rewrites (e.g. AEM's {@code sign-in} node feeds the client's
 * {@code login} screen), the {@code checkoutForm} meta overrides
 * ({@code formId}, {@code eventType}, ...) and the {@code formHeadings} default text used above each
 * form when the block itself has no authored title. Every consumer keeps its own compiled-in
 * default; this class narrows the scope of a change to one file per site instead of a code edit.
 *
 * <p>Shape of {@code /edsMobileApp/sites/<site>/defaults.json}:</p>
 * <pre>
 * {
 *   "screenKeyAliases":  { "&lt;jcr-name&gt;":  "&lt;screen-key&gt;" },
 *   "checkoutForm":      { "formId": "...", "eventType": "...", ... },
 *   "formHeadings":      { "&lt;form-name&gt;": "&lt;title text&gt;" },
 *   "forms":             { "&lt;screen-key&gt;": { "redirectAfterSubmit": "&lt;screen-key&gt;",
 *                                            "eventType": "..." } },
 *   "rightIcon":         { "icon": "...", "name": "...", "target": "..." },
 *   "meta":              { "brand": "...", "thumbnail": "...", "template": "...", "vertical": "...",
 *                          "rightIcon": "...", "rightIconTarget": "...", "useCart": true },
 *   "flightDestinationsFragment": "/content/dam/.../flight-source-destination-dropdown",
 *   "screenList":        { "textColor": "...", "color": "..." }
 * }
 * </pre>
 *
 * <p>{@code meta} carries the site's branding fallbacks (brand/thumbnail/template/vertical/
 * rightIcon/rightIconTarget) - the values {@link EnvelopeMeta} falls back to when the
 * placeholders sheet leaves a field blank. These used to live in one shared OSGi config, which
 * meant every site got the same brand/template/vertical regardless of which one was actually
 * being exported; per-site {@code defaults.json} is the fix, following the same pattern as the
 * other sections here.</p>
 *
 * <p>{@code rightIcon}, when present, pins {@code meta.rightIcon} to exactly this value for the
 * site - it wins over both an authored nav-page search block and the placeholders-sheet
 * {@code rightIcon}/{@code rightIconTarget} keys, which for every other site still take precedence
 * over the compiled-in cart default in that order. Use it when a site wants the app-bar icon fixed
 * regardless of what search configuration its nav page carries.</p>
 *
 * <p>Each section is optional; a section that is absent leaves the consumer's own default in place,
 * so a site that overrides nothing keeps the same behaviour it had before this class existed.</p>
 *
 * <p>Each site's file is read and cached on first use; an unknown site is only probed once. Same
 * classloader-only loading arrangement as {@link SiteBranding} and {@link SiteIntegrations}, so
 * behaviour is identical in OSGi, in tests and in a plain JVM.</p>
 */
final class SiteDefaults {

    private static final Logger LOG = LoggerFactory.getLogger(SiteDefaults.class);

    private static final String FILE = "defaults.json";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    static final String KEY_SCREEN_KEY_ALIASES = "screenKeyAliases";
    static final String KEY_CHECKOUT_FORM = "checkoutForm";
    static final String KEY_FORM_HEADINGS = "formHeadings";
    static final String KEY_RIGHT_ICON = "rightIcon";
    static final String KEY_META = "meta";
    /**
     * Per-screen form settings, keyed by screen key:
     * {@code "forms": { "<screen-key>": { "redirectAfterSubmit": "<screen-key>", "eventType": "..." } }}.
     * {@code redirectAfterSubmit} sets the screen the client opens after a successful submit
     * ({@code meta.link}); {@code eventType} sets the analytics event the submit fires
     * ({@code meta.eventType}). Each is applied only when present and non-blank; anything absent
     * (setting, screen, section, or file) leaves the value {@code FormBlockBuilder} already emitted.
     * See {@code PageJsonExporterImpl#applyFormSettings}.
     */
    static final String KEY_FORMS = "forms";
    /**
     * Path to the site's destination-dropdown Content Fragment, whose {@code destination_mapping}
     * multifield supplies the country a {@code flightResults} block should filter by, keyed by
     * screen key: {@code "flightDestinationsFragment": "/content/dam/.../flight-source-destination-
     * dropdown"}. A destination screen's block authors no such field itself (unlike the web block,
     * which reads it from the URL path), so this fragment is the only source; a screen key with no
     * matching row leaves {@code flightResults} with no {@code destination}, unchanged from today.
     * See {@code PageJsonExporterImpl#applyFlightDestination} and {@link FlightDestinationLookup}.
     */
    static final String KEY_FLIGHT_DESTINATIONS_FRAGMENT = "flightDestinationsFragment";
    /**
     * Overrides the fixed colors {@code ComponentTreeWalker#cardsBlockAsList} otherwise hardcodes
     * for a cards-block-to-screenList conversion (a near-white {@code textColor} meant to sit over
     * a photo, copied from the LUMA MOBILE reference's own dark-background screenList): {@code
     * "screenList": { "textColor": "...", "color": "..." } }. {@code textColor} is the item label
     * color, {@code color} the section's own background. Each is applied only when present and
     * non-blank; a site that overrides neither keeps today's hardcoded values - needed by any site
     * whose cards render on a light background, where light-on-light text would otherwise be
     * unreadable regardless of what an individual card authored.
     */
    static final String KEY_SCREEN_LIST = "screenList";

    private final Map<String, Optional<Map<String, Object>>> bySite = new ConcurrentHashMap<>();

    private SiteDefaults() {
    }

    static SiteDefaults load() {
        return new SiteDefaults();
    }

    /**
     * @param page any page in the site
     * @return that site's overrides, or an empty map when no {@code defaults.json} is shipped for it
     */
    Map<String, Object> forPage(final Page page) {
        return page == null ? Collections.emptyMap() : forPath(page.getPath());
    }

    /**
     * @param anyPath any repository path beneath the site root (a page, a resource, a fragment)
     * @return that site's overrides, or an empty map when no {@code defaults.json} is shipped for it
     */
    Map<String, Object> forPath(final String anyPath) {
        final String siteRoot = SiteHostResolver.siteRoot(anyPath);
        if (siteRoot == null) {
            return Collections.emptyMap();
        }
        final String site = StringUtils.substringAfterLast(siteRoot, "/");
        return bySite.computeIfAbsent(site, SiteDefaults::read).orElse(Collections.emptyMap());
    }

    /**
     * Convenience for a nested string map (e.g. {@code screenKeyAliases}, {@code formHeadings}).
     *
     * @return the map at {@code sectionKey}, or an empty map when the section is absent
     */
    @SuppressWarnings("unchecked")
    static Map<String, String> stringMap(final Map<String, Object> defaults, final String sectionKey) {
        final Object section = defaults.get(sectionKey);
        if (!(section instanceof Map)) {
            return Collections.emptyMap();
        }
        final Map<String, String> out = new LinkedHashMap<>();
        for (final Map.Entry<String, Object> entry : ((Map<String, Object>) section).entrySet()) {
            if (entry.getValue() instanceof String) {
                out.put(entry.getKey(), (String) entry.getValue());
            }
        }
        return out;
    }

    /**
     * Convenience for a nested object map (e.g. {@code checkoutForm}).
     *
     * @return the object at {@code sectionKey}, or an empty map when the section is absent
     */
    @SuppressWarnings("unchecked")
    static Map<String, Object> objectMap(final Map<String, Object> defaults, final String sectionKey) {
        final Object section = defaults.get(sectionKey);
        return section instanceof Map ? (Map<String, Object>) section : Collections.emptyMap();
    }

    /**
     * Convenience for a top-level string value (e.g. {@code flightDestinationsFragment}).
     *
     * @return the value at {@code key}, or {@code null} when absent or not a string
     */
    static String stringValue(final Map<String, Object> defaults, final String key) {
        final Object value = defaults.get(key);
        return value instanceof String ? (String) value : null;
    }

    private static Optional<Map<String, Object>> read(final String site) {
        final String path = "/edsMobileApp/sites/" + site + "/" + FILE;
        try (InputStream in = SiteDefaults.class.getResourceAsStream(path)) {
            if (in == null) {
                LOG.debug("{} not present - {} keeps compiled-in defaults", path, site);
                return Optional.empty();
            }
            final Map<String, Object> obj = MAPPER.readValue(
                    new String(in.readAllBytes(), StandardCharsets.UTF_8),
                    new TypeReference<Map<String, Object>>() { });
            LOG.info("Loaded site defaults for {}", site);
            return Optional.of(Collections.unmodifiableMap(obj));
        } catch (final IOException | RuntimeException e) {
            LOG.error("{} could not be read - {} keeps compiled-in defaults", path, site, e);
            return Optional.empty();
        }
    }
}

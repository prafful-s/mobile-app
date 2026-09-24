package com.edsMobileApp.core.services.impl;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.commons.lang3.StringUtils;
import org.apache.sling.api.resource.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.day.cq.wcm.api.Page;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Per-site branding: the colour palette, logos, fonts and style groups the app dresses itself in -
 * read live from the site's theme Content Fragment rather than shipped as static data.
 *
 * <p>{@code /edsMobileApp/sites/<site>/branding.json} (site = the site root's last segment, e.g.
 * {@code secur-financial}) now holds only a pointer:</p>
 * <pre>
 * { "contentFragmentPath": "/content/dam/carvelo/en/fragments/brand-themes/carvelo-mobile" }
 * </pre>
 * <p>so a brand color change is a content edit, not a bundle redeploy. That pointer is the only
 * part safe to cache for the bundle's lifetime; the fragment it names is live content an author can
 * change at any time, so it - and every value pulled from it - is re-read on every {@link #forPage}
 * call rather than cached alongside the pointer. A site with no such file, or whose fragment cannot
 * be resolved, simply exports no shipped branding (unchanged from the static-file behaviour this
 * replaces).</p>
 *
 * <p>Two fragment shapes are understood, distinguished by whether the fragment carries a
 * {@code themePropertyKeys} element:</p>
 * <ul>
 *   <li><b>Flat</b> (every site but secur-financial today, model {@code mobile-app-theme}): every
 *       element name is already the exact {@code branding} key - {@code primaryColor},
 *       {@code topBarBackgroundColor}, and so on - so each is carried through as-is, save for three
 *       fields the model types as a number ({@code roundness}, {@code panelBorderRadius},
 *       {@code buttonBorderRadius}) which are parsed back out of the string
 *       {@link ContentFragmentReader} returns every element as.</li>
 *   <li><b>Hybrid</b> (secur-financial today, model {@code secur-financial-brandtheme}): five
 *       dedicated logo reference fields ({@code logo}, {@code dark_logo}, {@code logoUrl},
 *       {@code logoLarge}, {@code logoSmall}) plus an arbitrary property list encoded as three
 *       index-aligned multifields - {@code themePropertyKeys[i]}/{@code themePropertyValues[i]}/
 *       {@code themePropertyTypes[i]} is one property, {@code type} one of
 *       {@code string|number|boolean|color}. A dotted key ({@code "headerStyles.backgroundColor"})
 *       becomes a nested object in the output ({@code {"headerStyles": {"backgroundColor": ...}}}),
 *       matching how this site's branding has always been shaped.</li>
 * </ul>
 *
 * <p>Neither shape prefixes a reference field's repository path with the delivery host - that
 * happens once, uniformly, back in {@code PageJsonExporterImpl#buildBranding}, the same as it
 * always has for the shipped-file branding this replaces.</p>
 */
final class SiteBranding {

    private static final Logger LOG = LoggerFactory.getLogger(SiteBranding.class);

    private static final String FILE = "branding.json";
    private static final String KEY_CONTENT_FRAGMENT_PATH = "contentFragmentPath";

    private static final String EL_THEME_PROPERTY_KEYS = "themePropertyKeys";
    private static final String EL_THEME_PROPERTY_VALUES = "themePropertyValues";
    private static final String EL_THEME_PROPERTY_TYPES = "themePropertyTypes";

    private static final String TYPE_NUMBER = "number";
    private static final String TYPE_BOOLEAN = "boolean";

    /** Flat-model elements typed as a number in the CF model, so the stringified read needs parsing back. */
    private static final Set<String> NUMERIC_FIELDS = Set.of("roundness", "panelBorderRadius", "buttonBorderRadius");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Map<String, Optional<String>> fragmentPathBySite = new ConcurrentHashMap<>();

    private SiteBranding() {
    }

    static SiteBranding load() {
        return new SiteBranding();
    }

    /**
     * @param page any page in the site
     * @return that site's branding, read fresh from its theme Content Fragment, or {@code null}
     *         when the site ships no pointer or the fragment cannot be resolved/read
     */
    Map<String, Object> forPage(final Page page) {
        final String siteRoot = SiteHostResolver.siteRoot(page.getPath());
        if (siteRoot == null || page.getContentResource() == null) {
            return null;
        }
        final String site = StringUtils.substringAfterLast(siteRoot, "/");
        final String fragmentPath =
                fragmentPathBySite.computeIfAbsent(site, SiteBranding::readFragmentPath).orElse(null);
        if (fragmentPath == null) {
            return null;
        }
        final Resource fragment = page.getContentResource().getResourceResolver().getResource(fragmentPath);
        if (fragment == null) {
            LOG.warn("Theme Content Fragment {} (site {}) does not resolve - no shipped branding", fragmentPath, site);
            return null;
        }
        final Map<String, String> elements = ContentFragmentReader.readElements(fragment);
        if (elements == null) {
            LOG.warn("{} (site {}) is not a Content Fragment - no shipped branding", fragmentPath, site);
            return null;
        }
        return elements.containsKey(EL_THEME_PROPERTY_KEYS)
                ? readHybrid(fragment, elements)
                : readFlat(elements);
    }

    /** Every element name is already the target {@code branding} key. */
    private static Map<String, Object> readFlat(final Map<String, String> elements) {
        final Map<String, Object> branding = new LinkedHashMap<>();
        for (final Map.Entry<String, String> element : elements.entrySet()) {
            branding.put(element.getKey(), coerceIfNumeric(element.getKey(), element.getValue()));
        }
        return branding;
    }

    private static Object coerceIfNumeric(final String key, final String value) {
        if (!NUMERIC_FIELDS.contains(key)) {
            return value;
        }
        final Long parsed = asLong(value);
        return parsed != null ? parsed : value;
    }

    /**
     * The five dedicated logo fields, carried through as-is, plus every
     * {@code themePropertyKeys[i]}/{@code themePropertyValues[i]}/{@code themePropertyTypes[i]}
     * triple, type-coerced and nested by splitting its key on {@code "."}.
     */
    private static Map<String, Object> readHybrid(final Resource fragment, final Map<String, String> elements) {
        final Map<String, Object> branding = new LinkedHashMap<>();
        for (final Map.Entry<String, String> element : elements.entrySet()) {
            final String name = element.getKey();
            if (EL_THEME_PROPERTY_KEYS.equals(name) || EL_THEME_PROPERTY_VALUES.equals(name)
                    || EL_THEME_PROPERTY_TYPES.equals(name)) {
                continue;
            }
            branding.put(name, element.getValue());
        }

        final List<String> keys = ContentFragmentReader.readMultiValue(fragment, EL_THEME_PROPERTY_KEYS);
        final List<String> values = ContentFragmentReader.readMultiValue(fragment, EL_THEME_PROPERTY_VALUES);
        final List<String> types = ContentFragmentReader.readMultiValue(fragment, EL_THEME_PROPERTY_TYPES);
        final int rows = Math.min(keys.size(), Math.min(values.size(), types.size()));
        for (int i = 0; i < rows; i++) {
            putNested(branding, keys.get(i), coerce(values.get(i), types.get(i)));
        }
        return branding;
    }

    /** @return {@code raw} parsed per {@code type} ({@code number}/{@code boolean}), else {@code raw} itself. */
    private static Object coerce(final String raw, final String type) {
        if (TYPE_NUMBER.equalsIgnoreCase(type)) {
            final Long asLong = asLong(raw);
            if (asLong != null) {
                return asLong;
            }
            try {
                return Double.valueOf(raw);
            } catch (final NumberFormatException | NullPointerException e) {
                return raw;
            }
        }
        if (TYPE_BOOLEAN.equalsIgnoreCase(type)) {
            return Boolean.valueOf(raw);
        }
        return raw;
    }

    private static Long asLong(final String value) {
        try {
            return Long.valueOf(StringUtils.trim(value));
        } catch (final NumberFormatException | NullPointerException e) {
            return null;
        }
    }

    /**
     * Places {@code value} at {@code dottedKey} inside {@code root}, creating an intermediate
     * {@link LinkedHashMap} for each segment but the last - {@code "headerStyles.backgroundColor"}
     * becomes {@code root.headerStyles.backgroundColor}. A key with no {@code "."} is placed at the
     * top level directly.
     */
    @SuppressWarnings("unchecked")
    private static void putNested(final Map<String, Object> root, final String dottedKey, final Object value) {
        final String[] segments = dottedKey.split("\\.");
        Map<String, Object> cursor = root;
        for (int i = 0; i < segments.length - 1; i++) {
            final Object existing = cursor.get(segments[i]);
            final Map<String, Object> nested = existing instanceof Map
                    ? (Map<String, Object>) existing : new LinkedHashMap<>();
            cursor.put(segments[i], nested);
            cursor = nested;
        }
        cursor.put(segments[segments.length - 1], value);
    }

    private static Optional<String> readFragmentPath(final String site) {
        final String path = "/edsMobileApp/sites/" + site + "/" + FILE;
        try (InputStream in = SiteBranding.class.getResourceAsStream(path)) {
            if (in == null) {
                LOG.debug("{} not present - no shipped branding for {}", path, site);
                return Optional.empty();
            }
            final Map<String, Object> obj = MAPPER.readValue(
                    new String(in.readAllBytes(), StandardCharsets.UTF_8),
                    new TypeReference<Map<String, Object>>() { });
            final Object fragmentPath = obj.get(KEY_CONTENT_FRAGMENT_PATH);
            if (!(fragmentPath instanceof String) || StringUtils.isBlank((String) fragmentPath)) {
                LOG.warn("{} carries no non-blank '{}' - no shipped branding for {}",
                        path, KEY_CONTENT_FRAGMENT_PATH, site);
                return Optional.empty();
            }
            LOG.info("Site {} branding resolves to theme Content Fragment {}", site, fragmentPath);
            return Optional.of((String) fragmentPath);
        } catch (final IOException | RuntimeException e) {
            LOG.error("{} could not be read - no shipped branding for {}", path, site, e);
            return Optional.empty();
        }
    }
}

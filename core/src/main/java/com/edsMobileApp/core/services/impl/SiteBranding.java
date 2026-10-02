package com.edsMobileApp.core.services.impl;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.commons.lang3.StringUtils;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.day.cq.wcm.api.Page;

/**
 * Per-site branding: the colour palette, logos, fonts and style groups the app dresses itself in -
 * read live from the site's theme Content Fragment rather than shipped as static data, and found
 * by searching rather than a path named per site.
 *
 * <p>No site names its fragment anywhere: {@link #themeModels} (the bundle-wide
 * {@code themeContentFragmentModels} setting - see {@code config.json}) names the {@code cq:model}
 * path(s) a fragment must carry to count as a theme, and this class takes the first fragment found
 * under the site's own {@code /content/dam/<site>} root whose model matches one of those - the same
 * "search this site's DAM root" idea {@link ProductCatalog} already uses for products, just with a
 * model-name filter instead of a product-rule match. A site is expected to carry exactly one theme
 * fragment; if it somehow carries more than one, whichever the walk reaches first wins.</p>
 *
 * <p>The site's resolved fragment <em>path</em> is cached for the bundle's lifetime once found - an
 * unknown/themeless site is only walked once - but the fragment itself is live content an author can
 * change at any time, so its elements are re-read on every {@link #forPage} call rather than cached
 * alongside the path. A site with no matching fragment simply exports no shipped branding.</p>
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

    private static final String CONTENT_ROOT = "/content/";
    private static final String DAM_ROOT = "/content/dam/";

    private static final String EL_THEME_PROPERTY_KEYS = "themePropertyKeys";
    private static final String EL_THEME_PROPERTY_VALUES = "themePropertyValues";
    private static final String EL_THEME_PROPERTY_TYPES = "themePropertyTypes";

    private static final String TYPE_NUMBER = "number";
    private static final String TYPE_BOOLEAN = "boolean";

    /** Flat-model elements typed as a number in the CF model, so the stringified read needs parsing back. */
    private static final Set<String> NUMERIC_FIELDS = Set.of("roundness", "panelBorderRadius", "buttonBorderRadius");

    private final Set<String> themeModels;
    private final Map<String, Optional<String>> fragmentPathBySite = new ConcurrentHashMap<>();

    private SiteBranding(final Set<String> themeModels) {
        this.themeModels = themeModels;
    }

    /** @param themeModels the {@code cq:model} path(s) that mark a fragment as a site's theme */
    static SiteBranding load(final String[] themeModels) {
        final Set<String> models = new LinkedHashSet<>();
        if (themeModels != null) {
            for (final String model : themeModels) {
                if (StringUtils.isNotBlank(model)) {
                    models.add(model.trim());
                }
            }
        }
        return new SiteBranding(models);
    }

    /**
     * @param page any page in the site
     * @return that site's branding, read fresh from its theme Content Fragment, or {@code null}
     *         when the site's DAM root carries no matching fragment
     */
    Map<String, Object> forPage(final Page page) {
        if (themeModels.isEmpty() || page == null || page.getContentResource() == null) {
            return null;
        }
        final String siteRoot = SiteHostResolver.siteRoot(page.getPath());
        if (siteRoot == null) {
            return null;
        }
        final String site = StringUtils.substringAfterLast(siteRoot, "/");
        final ResourceResolver resolver = page.getContentResource().getResourceResolver();
        final String fragmentPath = fragmentPathBySite
                .computeIfAbsent(site, s -> findThemeFragment(resolver, damRoot(siteRoot, s)))
                .orElse(null);
        if (fragmentPath == null) {
            return null;
        }
        final Resource fragment = resolver.getResource(fragmentPath);
        if (fragment == null) {
            LOG.warn("Theme Content Fragment {} (site {}) no longer resolves - no shipped branding",
                    fragmentPath, site);
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

    /** {@code /content/dam/<site>}, mirroring {@code ProductMappings}'s own site-to-DAM-root rule. */
    private static String damRoot(final String siteRoot, final String site) {
        return siteRoot.startsWith(CONTENT_ROOT) ? DAM_ROOT + site : null;
    }

    /**
     * Depth-first search for the first fragment under {@code damRoot} whose {@code cq:model} is one
     * of {@link #themeModels}. A site with none logs nothing beyond debug - most sites will only
     * ever carry one, and an unthemed site is a normal, if incomplete, state rather than an error.
     */
    private Optional<String> findThemeFragment(final ResourceResolver resolver, final String damRoot) {
        if (damRoot == null) {
            return Optional.empty();
        }
        final Resource root = resolver.getResource(damRoot);
        if (root == null) {
            return Optional.empty();
        }
        final String found = search(root, new int[] {0});
        if (found == null) {
            LOG.debug("No fragment under {} matches a configured theme model - no shipped branding", damRoot);
            return Optional.empty();
        }
        LOG.info("Resolved theme Content Fragment {}", found);
        return Optional.of(found);
    }

    private String search(final Resource folder, final int[] budget) {
        for (final Resource child : folder.getChildren()) {
            if (budget[0]++ >= DamFragmentSearch.MAX_NODES_SCANNED) {
                return null;
            }
            final String model = DamFragmentSearch.modelOf(child);
            if (model != null) {
                if (themeModels.contains(model)) {
                    return child.getPath();
                }
                continue; // a fragment of a foreign model has no theme children to descend into
            }
            if (DamFragmentSearch.isFolder(child) && child.hasChildren()) {
                final String found = search(child, budget);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    /**
     * Every element name is already the target {@code branding} key. An unauthored field comes back
     * from {@link ContentFragmentReader} as {@code null} or an empty string; skipped rather than
     * emitted blank, so a site's branding only carries what its fragment actually sets - the same
     * shape the static {@code branding.json} files this replaced always had.
     */
    private static Map<String, Object> readFlat(final Map<String, String> elements) {
        final Map<String, Object> branding = new LinkedHashMap<>();
        for (final Map.Entry<String, String> element : elements.entrySet()) {
            if (StringUtils.isBlank(element.getValue())) {
                continue;
            }
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
     * triple, type-coerced and nested by splitting its key on {@code "."}. A blank/unauthored logo
     * field or property value is skipped rather than emitted blank - same reasoning as
     * {@link #readFlat} - so a dotted key whose value is blank never creates an empty intermediate
     * object either (nothing is placed under it at all).
     */
    private static Map<String, Object> readHybrid(final Resource fragment, final Map<String, String> elements) {
        final Map<String, Object> branding = new LinkedHashMap<>();
        for (final Map.Entry<String, String> element : elements.entrySet()) {
            final String name = element.getKey();
            if (EL_THEME_PROPERTY_KEYS.equals(name) || EL_THEME_PROPERTY_VALUES.equals(name)
                    || EL_THEME_PROPERTY_TYPES.equals(name) || StringUtils.isBlank(element.getValue())) {
                continue;
            }
            branding.put(name, element.getValue());
        }

        final List<String> keys = ContentFragmentReader.readMultiValue(fragment, EL_THEME_PROPERTY_KEYS);
        final List<String> values = ContentFragmentReader.readMultiValue(fragment, EL_THEME_PROPERTY_VALUES);
        final List<String> types = ContentFragmentReader.readMultiValue(fragment, EL_THEME_PROPERTY_TYPES);
        final int rows = Math.min(keys.size(), Math.min(values.size(), types.size()));
        for (int i = 0; i < rows; i++) {
            if (StringUtils.isBlank(keys.get(i)) || StringUtils.isBlank(values.get(i))) {
                continue;
            }
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
}

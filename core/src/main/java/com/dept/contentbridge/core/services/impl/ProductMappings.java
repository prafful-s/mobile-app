package com.dept.contentbridge.core.services.impl;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * The loaded set of product projection rules, one entry per rule file.
 *
 * <p>Each rule declares the authoring model to project ({@code match}) and how each element maps
 * onto the shared product shape ({@code fields}). A rule may additionally declare {@code sites} and
 * {@code scope} to target one specific export catalogue: {@code sites} names the export pages the
 * rule claims (prefix-matched), and {@code scope} names the DAM subtrees to scan. Both fields are
 * optional and defaults are intentionally permissive: a rule with no {@code sites} claims every
 * export, a rule with no {@code scope} walks {@code /content/dam/<siteName>} - the site's own DAM
 * root, which is what the walker already did before rules could carry their own scope.</p>
 *
 * <p>The optional fields carry the configuration that used to live in the {@code cf-folder-path}
 * site placeholder for Luma: the Luma rule points at {@code /content/dam/luma3/en/fragments} in the
 * rule file itself, so no per-site placeholder lookup is needed on export. Rules that stay silent
 * about sites and scope keep the old walk-the-site's-DAM behaviour, so an existing rule (secur-
 * financial's, for one) needs no changes.</p>
 *
 * <p>Files live under {@code /contentbridge/products}, one per model, with an {@code index.json}
 * manifest - the same classloader-only arrangement the component mappings and form schemas use, so
 * loading behaves identically in OSGi, in tests and in a plain JVM.</p>
 */
final class ProductMappings {

    private static final Logger LOG = LoggerFactory.getLogger(ProductMappings.class);

    static final String PRODUCTS_PATH = "/contentbridge/products";
    static final String INDEX = PRODUCTS_PATH + "/index.json";

    private static final String CONTENT_ROOT = "/content/";
    private static final String DAM_ROOT = "/content/dam/";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Used when the rule files are missing or unreadable: no fragment is recognised as a product. */
    static final ProductMappings EMPTY = new ProductMappings(Collections.emptyList());

    private final List<Entry> entries;

    private ProductMappings(final List<Entry> entries) {
        this.entries = entries;
    }

    /**
     * Reads every rule named in the index. A malformed file costs its own rule and nothing else:
     * failing activation would take the whole export down over one bad product model.
     */
    static ProductMappings load() {
        final List<Entry> loaded = new ArrayList<>();

        for (final String name : readIndex()) {
            if (StringUtils.isBlank(name)) {
                continue;
            }
            try {
                final Map<String, Object> definition = readRule(name.trim());
                final ComponentMapping mapping = ComponentMapping.of(definition);
                if (mapping == null) {
                    LOG.error("Product rule '{}' declares no component or no model match - skipping it", name);
                    continue;
                }
                loaded.add(new Entry(name.trim(), mapping,
                        readList(definition.get("sites")), readList(definition.get("scope"))));
            } catch (final IOException | RuntimeException e) {
                LOG.error("Product rule '{}' could not be read - skipping it", name, e);
            }
        }

        final List<String> names = new ArrayList<>();
        for (final Entry entry : loaded) {
            names.add(entry.name);
        }
        LOG.info("Loaded {} product mappings: {}", loaded.size(), names);
        return new ProductMappings(Collections.unmodifiableList(loaded));
    }

    private static List<String> readIndex() {
        try (InputStream in = ProductMappings.class.getResourceAsStream(INDEX)) {
            if (in == null) {
                LOG.error("{} is missing - no product mappings will be applied", INDEX);
                return Collections.emptyList();
            }
            return MAPPER.readValue(new String(in.readAllBytes(), StandardCharsets.UTF_8),
                    new TypeReference<List<String>>() { });
        } catch (final IOException e) {
            LOG.error("{} could not be read - no product mappings will be applied", INDEX, e);
            return Collections.emptyList();
        }
    }

    private static Map<String, Object> readRule(final String name) throws IOException {
        final String path = PRODUCTS_PATH + "/" + name + ".json";
        try (InputStream in = ProductMappings.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IOException(path + " is listed in the index but not present in the bundle");
            }
            return MAPPER.readValue(new String(in.readAllBytes(), StandardCharsets.UTF_8),
                    new TypeReference<LinkedHashMap<String, Object>>() { });
        }
    }

    /** Accepts either a single string or an array of strings; blanks are dropped. */
    private static List<String> readList(final Object declared) {
        final List<String> out = new ArrayList<>();
        if (declared instanceof String) {
            final String single = StringUtils.trimToNull((String) declared);
            if (single != null) {
                out.add(single);
            }
        } else if (declared instanceof List) {
            for (final Object element : (List<?>) declared) {
                if (element instanceof String) {
                    final String s = StringUtils.trimToNull((String) element);
                    if (s != null) {
                        out.add(s);
                    }
                }
            }
        }
        return out;
    }

    /**
     * @param sitePath the export site page's path
     * @return the DAM subtree roots to scan for that site's products - one entry per {@code scope}
     *         on every rule that claims the site, plus {@code /content/dam/<siteName>} for rules
     *         that stay silent about scope (default site DAM)
     */
    Set<String> rootsForSite(final String sitePath) {
        if (StringUtils.isBlank(sitePath)) {
            return Collections.emptySet();
        }
        final Set<String> roots = new LinkedHashSet<>();
        for (final Entry entry : entries) {
            if (!entry.claims(sitePath)) {
                continue;
            }
            if (!entry.scopes.isEmpty()) {
                roots.addAll(entry.scopes);
            } else {
                final String fallback = defaultDamRoot(sitePath);
                if (fallback != null) {
                    roots.add(fallback);
                }
            }
        }
        return Collections.unmodifiableSet(roots);
    }

    /**
     * Returns the projection rule for {@code model} <em>only when the rule claims {@code sitePath}</em>,
     * so a fragment authored under one site's model does not leak into another site's export.
     */
    ComponentMapping findForSite(final String model, final String sitePath) {
        if (StringUtils.isBlank(model)) {
            return null;
        }
        final String trimmed = model.trim();
        for (final Entry entry : entries) {
            if (entry.mapping.getMatches().contains(trimmed) && entry.claims(sitePath)) {
                return entry.mapping;
            }
        }
        return null;
    }

    /** Exposed for the test that keeps the index and the directory from drifting apart. */
    Map<String, ComponentMapping> all() {
        final Map<String, ComponentMapping> out = new TreeMap<>();
        for (final Entry entry : entries) {
            for (final String model : entry.mapping.getMatches()) {
                out.put(model, entry.mapping);
            }
        }
        return Collections.unmodifiableMap(out);
    }

    /** Number of distinct fragment models currently mapped - surfaced in the access log per request. */
    int knownModelCount() {
        return all().size();
    }

    /** @return {@code /content/dam/<siteName>} for a site path under {@code /content/}, else null. */
    private static String defaultDamRoot(final String sitePath) {
        if (!sitePath.startsWith(CONTENT_ROOT)) {
            return null;
        }
        final String siteName = sitePath.substring(CONTENT_ROOT.length()).split("/", 2)[0];
        return siteName.isEmpty() ? null : DAM_ROOT + siteName;
    }

    /** One rule as loaded: file name, projection rule, applicable site prefixes, and DAM scopes. */
    private static final class Entry {

        private final String name;
        private final ComponentMapping mapping;
        private final List<String> sites;
        private final List<String> scopes;

        Entry(final String name, final ComponentMapping mapping,
              final List<String> sites, final List<String> scopes) {
            this.name = name;
            this.mapping = mapping;
            this.sites = Collections.unmodifiableList(sites);
            this.scopes = Collections.unmodifiableList(scopes);
        }

        /**
         * True when this rule applies to {@code sitePath}. A rule with no {@code sites} claims every
         * export, so a rule that hasn't opted into per-site targeting stays as broadly applicable as
         * it always was.
         */
        boolean claims(final String sitePath) {
            if (sites.isEmpty()) {
                return true;
            }
            for (final String site : sites) {
                if (sitePath.equals(site) || sitePath.startsWith(site + "/")) {
                    return true;
                }
            }
            return false;
        }
    }
}

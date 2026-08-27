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
 * <p>Products come from Content Fragments, and each rule file describes one site's product model:
 * which authoring model to project ({@code match}), which sites the rule applies to ({@code sites}),
 * which DAM subtrees to scan for its fragments ({@code scope}), and how each element maps onto the
 * shared product shape ({@code fields}). Rules are matched to an export in two steps: pick every
 * rule whose {@code sites} claims the export site, then within its {@code scope} pick every fragment
 * whose {@code cq:model} the rule's {@code match} claims. The two together isolate one site's
 * catalogue from another - Luma exports do not see secur-financial fragments and vice versa, even
 * when the same JCR holds both.</p>
 *
 * <p>A rule with no {@code scope} contributes no products - the caller walks nothing on its behalf
 * rather than sweeping the entire DAM. A rule with no {@code sites} matches no export, again by
 * design.</p>
 *
 * <p>Files live under {@code /contentbridge/products}, one per model, with an {@code index.json}
 * manifest - the same classloader-only arrangement the component mappings and form schemas use, so
 * loading behaves identically in OSGi, in tests and in a plain JVM.</p>
 */
final class ProductMappings {

    private static final Logger LOG = LoggerFactory.getLogger(ProductMappings.class);

    static final String PRODUCTS_PATH = "/contentbridge/products";
    static final String INDEX = PRODUCTS_PATH + "/index.json";

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
                final List<String> sites = readList(definition.get("sites"));
                final List<String> scopes = readList(definition.get("scope"));
                loaded.add(new Entry(name.trim(), mapping, sites, scopes));
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

    /**
     * Reads a string-array field from a rule file. Accepts a single string (one entry) or an array
     * of strings (many). Blank entries are skipped.
     */
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
     * @param sitePath the export site page's path (e.g. {@code /content/luma-site/language-masters/en})
     * @return the DAM subtree roots to scan for that site's products, one entry per {@code scope} on
     *         every rule whose {@code sites} prefix-matches the export site; empty when no rule
     *         claims this site
     */
    Set<String> scopesForSite(final String sitePath) {
        if (StringUtils.isBlank(sitePath)) {
            return Collections.emptySet();
        }
        final Set<String> out = new LinkedHashSet<>();
        for (final Entry entry : entries) {
            if (entry.claims(sitePath)) {
                out.addAll(entry.scopes);
            }
        }
        return Collections.unmodifiableSet(out);
    }

    /**
     * @param model the fragment's {@code cq:model} path
     * @return the rule projecting that model onto a product, or {@code null} when none claims it
     */
    ComponentMapping find(final String model) {
        if (StringUtils.isBlank(model)) {
            return null;
        }
        final String trimmed = model.trim();
        for (final Entry entry : entries) {
            if (entry.mapping.getMatches().contains(trimmed)) {
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

    /** One rule as loaded: its file name, projection rule, applicable site prefixes, and DAM scopes. */
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

        /** True when {@code sitePath} starts with one of this rule's declared site prefixes. */
        boolean claims(final String sitePath) {
            for (final String site : sites) {
                if (sitePath.equals(site) || sitePath.startsWith(site + "/")) {
                    return true;
                }
            }
            return false;
        }
    }
}

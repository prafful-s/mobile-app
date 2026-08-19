package com.dept.contentbridge.core.services.impl;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * The loaded set of product projection rules, keyed by the Content Fragment model they apply to.
 *
 * <p>Products come from Content Fragments, and a site models them with more than one Content Fragment
 * model. Each model's fields are projected onto the shared product shape by a rule file - reusing the
 * same {@link ComponentMapping} field vocabulary the component mappings use - so adding a new product
 * model is a data edit, not a code change. Rules are matched on the fragment's {@code cq:model} path
 * (what {@code match} lists), not on a component name.</p>
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
    static final ProductMappings EMPTY = new ProductMappings(Collections.emptyMap());

    private final Map<String, ComponentMapping> byModel;

    private ProductMappings(final Map<String, ComponentMapping> byModel) {
        this.byModel = byModel;
    }

    /**
     * Reads every rule named in the index. A malformed file costs its own rule and nothing else:
     * failing activation would take the whole export down over one bad product model.
     */
    static ProductMappings load() {
        final Map<String, ComponentMapping> loaded = new TreeMap<>();

        for (final String name : readIndex()) {
            if (StringUtils.isBlank(name)) {
                continue;
            }
            try {
                final ComponentMapping mapping = ComponentMapping.of(readRule(name.trim()));
                if (mapping == null) {
                    LOG.error("Product rule '{}' declares no component or no model match - skipping it", name);
                    continue;
                }
                for (final String model : mapping.getMatches()) {
                    final ComponentMapping clash = loaded.put(model, mapping);
                    if (clash != null) {
                        LOG.warn("Two product rules both claim model '{}'; '{}' wins", model, name);
                    }
                }
            } catch (final IOException | RuntimeException e) {
                LOG.error("Product rule '{}' could not be read - skipping it", name, e);
            }
        }

        LOG.info("Loaded {} product mappings: {}", loaded.size(), loaded.keySet());
        return new ProductMappings(Collections.unmodifiableMap(loaded));
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
     * @param model the fragment's {@code cq:model} path
     * @return the rule projecting that model onto a product, or {@code null} when none claims it
     */
    ComponentMapping find(final String model) {
        return StringUtils.isBlank(model) ? null : byModel.get(model.trim());
    }

    /** Exposed for the test that keeps the index and the directory from drifting apart. */
    Map<String, ComponentMapping> all() {
        return byModel;
    }

    /** Number of distinct fragment models currently mapped - surfaced in the access log per request. */
    int knownModelCount() {
        return byModel.size();
    }
}

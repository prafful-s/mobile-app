package com.edsMobileApp.core.services.impl;

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
 * The loaded set of component mapping rules, keyed by the authored component name.
 *
 * <p>Rules live as JSON under {@code /contentbridge/mappings}, one file per authored component, plus
 * an {@code index.json} naming them all - the same arrangement as the form schemas, and for the same
 * reason: a bundle cannot reliably list its own contents, because {@code findEntries} is an OSGi
 * framework call that mocks and plain classloaders do not implement. Reading a manifest of names and
 * loading each by path uses nothing but the classloader, so it behaves identically in OSGi, in tests
 * and in a plain JVM.</p>
 *
 * <p>Keeping the mappings as data rather than Java is what makes this maintainable: adding a site
 * whose hero is called {@code masthead} is a one-line edit to a rule file, not a new model class and
 * a redeploy of compiled logic.</p>
 */
final class ComponentMappings {

    private static final Logger LOG = LoggerFactory.getLogger(ComponentMappings.class);

    static final String MAPPING_PATH = "/contentbridge/mappings";
    static final String INDEX = MAPPING_PATH + "/index.json";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Used when the rule files are missing or unreadable: every component falls through untouched. */
    static final ComponentMappings EMPTY = new ComponentMappings(Collections.emptyMap());

    private final Map<String, ComponentMapping> byComponentName;

    private ComponentMappings(final Map<String, ComponentMapping> byComponentName) {
        this.byComponentName = byComponentName;
    }

    /**
     * Reads every rule named in the index. A malformed file costs its own rule and nothing else:
     * the alternative - failing activation - would take the whole export down over one bad selector.
     */
    static ComponentMappings load() {
        final Map<String, ComponentMapping> loaded = new TreeMap<>();

        for (final String name : readIndex()) {
            if (StringUtils.isBlank(name)) {
                continue;
            }
            try {
                final ComponentMapping mapping = ComponentMapping.of(readRule(name.trim()));
                if (mapping == null) {
                    LOG.error("Mapping rule '{}' declares no component or no match - skipping it", name);
                    continue;
                }
                for (final String match : mapping.getMatches()) {
                    final ComponentMapping clash = loaded.put(match, mapping);
                    if (clash != null) {
                        LOG.warn("Two rules both claim '{}'; '{}' wins", match, mapping.getComponent());
                    }
                }
            } catch (final IOException | RuntimeException e) {
                LOG.error("Mapping rule '{}' could not be read - skipping it", name, e);
            }
        }

        LOG.info("Loaded {} component mappings: {}", loaded.size(), loaded.keySet());
        return new ComponentMappings(Collections.unmodifiableMap(loaded));
    }

    private static List<String> readIndex() {
        try (InputStream in = ComponentMappings.class.getResourceAsStream(INDEX)) {
            if (in == null) {
                LOG.error("{} is missing - no component mappings will be applied", INDEX);
                return Collections.emptyList();
            }
            return MAPPER.readValue(new String(in.readAllBytes(), StandardCharsets.UTF_8),
                    new TypeReference<List<String>>() { });
        } catch (final IOException e) {
            LOG.error("{} could not be read - no component mappings will be applied", INDEX, e);
            return Collections.emptyList();
        }
    }

    private static Map<String, Object> readRule(final String name) throws IOException {
        final String path = MAPPING_PATH + "/" + name + ".json";
        try (InputStream in = ComponentMappings.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IOException(path + " is listed in the index but not present in the bundle");
            }
            return MAPPER.readValue(new String(in.readAllBytes(), StandardCharsets.UTF_8),
                    new TypeReference<LinkedHashMap<String, Object>>() { });
        }
    }

    /**
     * @param componentName the authored component name, e.g. {@code hero}
     * @return the rule claiming that name, or {@code null} when there is none
     */
    ComponentMapping find(final String componentName) {
        return StringUtils.isBlank(componentName) ? null : byComponentName.get(componentName.trim());
    }

    /** Exposed for the test that keeps the index and the directory from drifting apart. */
    Map<String, ComponentMapping> all() {
        return byComponentName;
    }
}

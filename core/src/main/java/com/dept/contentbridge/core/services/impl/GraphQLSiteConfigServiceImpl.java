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
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.day.cq.wcm.api.Page;
import com.dept.contentbridge.core.services.GraphQLSiteConfig;
import com.dept.contentbridge.core.services.GraphQLSiteConfigService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Loads per-site GraphQL persisted-query settings from JSON bundled under
 * {@code /contentbridge/graphql/}.
 *
 * <p>Follows the same {@code index.json} + one-file-per-entry pattern as
 * {@link FormSchemaServiceImpl}, and for the same reason - {@code Bundle.findEntries} does not work
 * in the test classloader, but reading a manifest of names and each file by path does.</p>
 */
@Component(service = GraphQLSiteConfigService.class,
           property = {"service.description=ContentBridge - GraphQL site config loader"})
public class GraphQLSiteConfigServiceImpl implements GraphQLSiteConfigService {

    private static final Logger LOG = LoggerFactory.getLogger(GraphQLSiteConfigServiceImpl.class);

    private static final String BASE = "/contentbridge/graphql";
    private static final String INDEX = BASE + "/index.json";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private Map<String, GraphQLSiteConfig> bySiteRoot = Collections.emptyMap();

    @Activate
    protected void activate() {
        final Map<String, GraphQLSiteConfig> loaded = new TreeMap<>();
        for (final String name : readIndex()) {
            if (StringUtils.isBlank(name)) {
                continue;
            }
            try {
                final Map<String, Object> raw = readEntry(name.trim());
                final GraphQLSiteConfig config = SiteConfig.of(raw);
                if (config == null) {
                    LOG.error("GraphQL config '{}' has no siteRoot, configName or queryName - skipping", name);
                    continue;
                }
                loaded.put(config.getSiteRoot(), config);
            } catch (final IOException | RuntimeException e) {
                LOG.error("GraphQL config '{}' could not be read - skipping", name, e);
            }
        }
        this.bySiteRoot = Collections.unmodifiableMap(loaded);
        LOG.info("Loaded {} GraphQL site configs: {}", loaded.size(), loaded.keySet());
    }

    @Override
    public GraphQLSiteConfig forPage(final Page page) {
        if (page == null) {
            return null;
        }
        final String path = page.getPath();
        if (StringUtils.isBlank(path)) {
            return null;
        }
        // Longest matching prefix wins so a nested site never masks its parent.
        String best = null;
        for (final String root : bySiteRoot.keySet()) {
            if ((path.equals(root) || path.startsWith(root + "/"))
                    && (best == null || root.length() > best.length())) {
                best = root;
            }
        }
        return best == null ? null : bySiteRoot.get(best);
    }

    @Override
    public Set<String> configuredSites() {
        return bySiteRoot.keySet();
    }

    private List<String> readIndex() {
        try (InputStream in = getClass().getResourceAsStream(INDEX)) {
            if (in == null) {
                LOG.info("{} is missing - GraphQL site configs disabled", INDEX);
                return Collections.emptyList();
            }
            return MAPPER.readValue(new String(in.readAllBytes(), StandardCharsets.UTF_8),
                    new TypeReference<List<String>>() { });
        } catch (final IOException e) {
            LOG.error("{} could not be read - GraphQL site configs disabled", INDEX, e);
            return Collections.emptyList();
        }
    }

    private Map<String, Object> readEntry(final String name) throws IOException {
        final String path = BASE + "/" + name + ".json";
        try (InputStream in = getClass().getResourceAsStream(path)) {
            if (in == null) {
                throw new IOException(path + " is listed in the index but not present in the bundle");
            }
            return MAPPER.readValue(new String(in.readAllBytes(), StandardCharsets.UTF_8),
                    new TypeReference<LinkedHashMap<String, Object>>() { });
        }
    }

    /** Immutable snapshot of one entry. Package-private because callers see it through the interface. */
    static final class SiteConfig implements GraphQLSiteConfig {

        private final String siteRoot;
        private final String configName;
        private final String queryName;
        private final String pathVariable;
        private final String defaultPath;
        private final List<String> collectFields;

        private SiteConfig(final String siteRoot, final String configName, final String queryName,
                           final String pathVariable, final String defaultPath,
                           final List<String> collectFields) {
            this.siteRoot = siteRoot;
            this.configName = configName;
            this.queryName = queryName;
            this.pathVariable = pathVariable;
            this.defaultPath = defaultPath;
            this.collectFields = collectFields;
        }

        static SiteConfig of(final Map<String, Object> raw) {
            if (raw == null) {
                return null;
            }
            final String siteRoot = string(raw.get("siteRoot"));
            final String configName = string(raw.get("configName"));
            final String queryName = string(raw.get("queryName"));
            if (siteRoot == null || configName == null || queryName == null) {
                return null;
            }
            final String pathVariable = StringUtils.defaultIfBlank(string(raw.get("pathVariable")), "_path");
            final String defaultPath = string(raw.get("defaultPath"));
            final List<String> collectFields = strings(raw.get("collectFields"));
            return new SiteConfig(siteRoot, configName, queryName, pathVariable, defaultPath,
                    collectFields.isEmpty() ? List.of("sku", "id") : Collections.unmodifiableList(collectFields));
        }

        @Override public String getSiteRoot()           { return siteRoot; }
        @Override public String getConfigName()         { return configName; }
        @Override public String getQueryName()          { return queryName; }
        @Override public String getPathVariable()       { return pathVariable; }
        @Override public String getDefaultPath()        { return defaultPath; }
        @Override public List<String> getCollectFields() { return collectFields; }

        private static String string(final Object value) {
            return value instanceof String ? StringUtils.trimToNull((String) value) : null;
        }

        private static List<String> strings(final Object value) {
            final List<String> out = new ArrayList<>();
            if (value instanceof List) {
                for (final Object item : (List<?>) value) {
                    final String s = string(item);
                    if (s != null && !out.contains(s)) {
                        out.add(s);
                    }
                }
            } else {
                final String s = string(value);
                if (s != null) {
                    out.add(s);
                }
            }
            return out.isEmpty() ? Collections.emptyList() : new ArrayList<>(new LinkedHashSet<>(out));
        }
    }
}

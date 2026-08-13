package com.dept.contentbridge.core.services.impl;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.day.cq.wcm.api.Page;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Shipped, per-site defaults for where a site's product catalogue lives: which persisted GraphQL
 * query to run, and which Content Fragment folder to fall back to when it does not.
 *
 * <p>Lives as data under {@code /contentbridge/products/sites.json} rather than content, so a new
 * site's defaults ship with the bundle instead of requiring every environment's {@code placeholders}
 * page to be authored by hand. Content still wins: {@link GraphQlQuery#resolve} only reaches for an
 * entry here when neither the request nor the site's own placeholders named a query or a path - see
 * that class for the full precedence.</p>
 *
 * <pre>
 * "secur-financial": {
 *   "graphQlEndpoint": "secur-financial/product-card-information",
 *   "cfFolderPath": "/content/dam/secur-financial"
 * }
 * </pre>
 *
 * <p>{@code graphQlEndpoint} is {@code <config>/<queryName>} - the same two path segments
 * {@code /graphql/execute.json/<config>/<queryName>} uses - kept as one string because that is how
 * both this file and a pasted execute URL name a query, and splitting it into two JSON keys would
 * just be reassembled at the call site.</p>
 */
final class SiteProductDefaults {

    private static final Logger LOG = LoggerFactory.getLogger(SiteProductDefaults.class);

    static final String PATH = "/contentbridge/products/sites.json";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Used when the file is missing or unreadable: every site falls through with no shipped default. */
    static final SiteProductDefaults EMPTY = new SiteProductDefaults(Collections.emptyMap());

    private final Map<String, Entry> bySite;

    private SiteProductDefaults(final Map<String, Entry> bySite) {
        this.bySite = bySite;
    }

    static SiteProductDefaults load() {
        try (InputStream in = SiteProductDefaults.class.getResourceAsStream(PATH)) {
            if (in == null) {
                LOG.debug("{} not present - no shipped per-site product defaults", PATH);
                return EMPTY;
            }
            final Map<String, RawEntry> raw = MAPPER.readValue(
                    new String(in.readAllBytes(), StandardCharsets.UTF_8),
                    new TypeReference<Map<String, RawEntry>>() { });

            final Map<String, Entry> parsed = new java.util.LinkedHashMap<>();
            for (final Map.Entry<String, RawEntry> siteEntry : raw.entrySet()) {
                final Entry entry = Entry.of(siteEntry.getValue());
                if (entry != null) {
                    parsed.put(siteEntry.getKey(), entry);
                }
            }
            LOG.info("Loaded shipped product defaults for {} site(s): {}", parsed.size(), parsed.keySet());
            return new SiteProductDefaults(Collections.unmodifiableMap(parsed));
        } catch (final IOException | RuntimeException e) {
            LOG.error("{} could not be read - no shipped per-site product defaults", PATH, e);
            return EMPTY;
        }
    }

    /**
     * @param page any page in the site
     * @return the shipped defaults for that site, or {@code null} when none are configured
     */
    Entry forPage(final Page page) {
        final String siteRoot = SiteHostResolver.siteRoot(page.getPath());
        if (siteRoot == null) {
            return null;
        }
        return bySite.get(StringUtils.substringAfterLast(siteRoot, "/"));
    }

    /** Jackson binding target - kept separate from {@link Entry} so a malformed row costs itself. */
    private static final class RawEntry {
        public String graphQlEndpoint;
        public String cfFolderPath;
    }

    /** One site's shipped defaults, with the endpoint already split into config and query name. */
    static final class Entry {

        private final String configName;
        private final String queryName;
        private final String cfFolderPath;

        private Entry(final String configName, final String queryName, final String cfFolderPath) {
            this.configName = configName;
            this.queryName = queryName;
            this.cfFolderPath = cfFolderPath;
        }

        /** @return the entry, or {@code null} when it names no usable config/query and no folder path */
        private static Entry of(final RawEntry raw) {
            if (raw == null) {
                return null;
            }
            final String endpoint = StringUtils.trimToNull(raw.graphQlEndpoint);
            final String folderPath = StringUtils.trimToNull(raw.cfFolderPath);
            String configName = null;
            String queryName = null;
            if (endpoint != null && endpoint.indexOf('/') > 0) {
                configName = StringUtils.substringBefore(endpoint, "/");
                queryName = StringUtils.substringAfter(endpoint, "/");
            } else if (endpoint != null) {
                LOG.warn("graphQlEndpoint '{}' is not '<config>/<queryName>' - ignoring it", endpoint);
            }
            if (configName == null && folderPath == null) {
                return null;
            }
            return new Entry(configName, queryName, folderPath);
        }

        String getConfigName() {
            return configName;
        }

        String getQueryName() {
            return queryName;
        }

        String getCfFolderPath() {
            return cfFolderPath;
        }
    }
}

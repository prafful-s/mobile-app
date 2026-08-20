package com.dept.contentbridge.core.services.impl;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
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
 * Shipped, per-site {@code integrations}: the AEP/IMS/Launch wiring the app reports. Like
 * {@link SiteBranding} and {@link SiteProfileViewer} it never varies with anything on a page, so
 * each site keeps it as data under {@code /contentbridge/sites/<site>/integrations.json} (site = the
 * site root's last segment, e.g. {@code secur-financial}) - one folder per site, alongside that
 * site's other shipped config, and ships with the bundle. A site with no such file simply exports no
 * integrations.
 *
 * <p>The object is emitted verbatim, nesting and all, so the JSON here is exactly the
 * {@code integrations} shape the client receives; this class does not impose a schema on it. Each
 * site's file is read and cached on first use, so an unknown site is only probed once.</p>
 */
final class SiteIntegrations {

    private static final Logger LOG = LoggerFactory.getLogger(SiteIntegrations.class);

    private static final String FILE = "integrations.json";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Map<String, Optional<Map<String, Object>>> bySite = new ConcurrentHashMap<>();

    private SiteIntegrations() {
    }

    static SiteIntegrations load() {
        return new SiteIntegrations();
    }

    /**
     * @param page any page in the site
     * @return that site's shipped integrations, or {@code null} when none is configured
     */
    Map<String, Object> forPage(final Page page) {
        final String siteRoot = SiteHostResolver.siteRoot(page.getPath());
        if (siteRoot == null) {
            return null;
        }
        final String site = StringUtils.substringAfterLast(siteRoot, "/");
        return bySite.computeIfAbsent(site, SiteIntegrations::read).orElse(null);
    }

    private static Optional<Map<String, Object>> read(final String site) {
        final String path = "/contentbridge/sites/" + site + "/" + FILE;
        try (InputStream in = SiteIntegrations.class.getResourceAsStream(path)) {
            if (in == null) {
                LOG.debug("{} not present - no shipped integrations for {}", path, site);
                return Optional.empty();
            }
            final Map<String, Object> obj = MAPPER.readValue(
                    new String(in.readAllBytes(), StandardCharsets.UTF_8),
                    new TypeReference<Map<String, Object>>() { });
            LOG.info("Loaded shipped integrations for site {}", site);
            return Optional.of(Collections.unmodifiableMap(obj));
        } catch (final IOException | RuntimeException e) {
            LOG.error("{} could not be read - no shipped integrations for {}", path, site, e);
            return Optional.empty();
        }
    }
}

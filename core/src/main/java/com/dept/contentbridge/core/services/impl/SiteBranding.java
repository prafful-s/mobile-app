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
 * Shipped, per-site branding: the colour palette, logos, fonts and style groups the app dresses
 * itself in. It never varies with anything on a page, so it lives as data under
 * {@code /contentbridge/branding/sites.json} rather than being authored per page - a new site drops
 * its object in keyed by the site root's last segment (e.g. {@code secur-financial}) and ships with
 * the bundle.
 *
 * <p>The object is emitted verbatim, nesting and all, so the JSON here is exactly the {@code branding}
 * shape the client receives; this class does not impose a schema on it.</p>
 */
final class SiteBranding {

    private static final Logger LOG = LoggerFactory.getLogger(SiteBranding.class);

    static final String PATH = "/contentbridge/branding/sites.json";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Used when the file is missing or unreadable: every site falls through with no shipped branding. */
    static final SiteBranding EMPTY = new SiteBranding(Collections.emptyMap());

    private final Map<String, Map<String, Object>> bySite;

    private SiteBranding(final Map<String, Map<String, Object>> bySite) {
        this.bySite = bySite;
    }

    static SiteBranding load() {
        try (InputStream in = SiteBranding.class.getResourceAsStream(PATH)) {
            if (in == null) {
                LOG.debug("{} not present - no shipped per-site branding", PATH);
                return EMPTY;
            }
            final Map<String, Map<String, Object>> raw = MAPPER.readValue(
                    new String(in.readAllBytes(), StandardCharsets.UTF_8),
                    new TypeReference<Map<String, Map<String, Object>>>() { });
            LOG.info("Loaded shipped branding for {} site(s): {}", raw.size(), raw.keySet());
            return new SiteBranding(Collections.unmodifiableMap(raw));
        } catch (final IOException | RuntimeException e) {
            LOG.error("{} could not be read - no shipped per-site branding", PATH, e);
            return EMPTY;
        }
    }

    /**
     * @param page any page in the site
     * @return that site's shipped branding, or {@code null} when none is configured
     */
    Map<String, Object> forPage(final Page page) {
        final String siteRoot = SiteHostResolver.siteRoot(page.getPath());
        if (siteRoot == null) {
            return null;
        }
        return bySite.get(StringUtils.substringAfterLast(siteRoot, "/"));
    }
}

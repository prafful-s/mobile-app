package com.dept.contentbridge.core.services.impl;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;
import org.apache.sling.api.resource.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.day.cq.wcm.api.Page;

/**
 * Works out which host DAM references should be served from, per site.
 *
 * <p>Sites carry their own hostname in a {@code placeholders} page under the site root, so a single
 * globally-configured domain would be wrong the moment a second site is exported. The authored value
 * is the <em>author</em> host; clients need the publish one, so the host segment is switched over.</p>
 *
 * <pre>
 * /content/&lt;site&gt;/placeholders   hostname = https://author-p153659-e1620914.adobeaemcloud.com
 *                                   emitted  = https://publish-p153659-e1620914.adobeaemcloud.com
 * </pre>
 *
 * <p>The lookup is deliberately forgiving about where the value sits. {@code placeholders} is a
 * key/value sheet in Edge Delivery and a plain page elsewhere, so the whole subtree is searched both
 * for a property literally named {@code hostname} and for the key/value row shape that sheets use.</p>
 */
final class SiteHostResolver {

    private static final Logger LOG = LoggerFactory.getLogger(SiteHostResolver.class);

    private static final String CONTENT_ROOT = "/content/";
    private static final String PLACEHOLDERS = "placeholders";
    private static final String PN_HOSTNAME = "hostname";

    /** Property names a key/value sheet uses for the value column. */
    private static final String[] VALUE_PROPERTIES = {"value", "text", "Text", "Value"};

    /** Guard against pathological trees; placeholders sheets are shallow. */
    private static final int MAX_NODES = 500;

    private final String authorSegment;
    private final String publishSegment;

    SiteHostResolver(final String authorSegment, final String publishSegment) {
        this.authorSegment = authorSegment;
        this.publishSegment = publishSegment;
    }

    /**
     * @param page any page in the site
     * @return the publish host to prefix DAM paths with, or empty when the site declares none
     */
    String resolve(final Page page) {
        final String hostname = placeholder(page, PN_HOSTNAME);
        if (StringUtils.isBlank(hostname)) {
            return StringUtils.EMPTY;
        }
        return toPublishHost(hostname);
    }

    /**
     * Reads any entry from the site's placeholders page.
     *
     * <p>Placeholders are the natural home for per-site settings an author owns - the delivery
     * hostname, the catalogue location - so the lookup is generic rather than hard-wired to one key.</p>
     *
     * @param page any page in the site
     * @param key  the placeholder name, e.g. {@code hostname} or {@code productsPath}
     * @return the value, or empty when the site has no placeholders page or no such entry
     */
    String placeholder(final Page page, final String key) {
        final Resource placeholders = findPlaceholders(page);
        if (placeholders == null) {
            LOG.debug("No {} page for {}", PLACEHOLDERS, page.getPath());
            return StringUtils.EMPTY;
        }
        final String value = findEntry(placeholders, key);
        if (StringUtils.isBlank(value)) {
            LOG.debug("No '{}' entry under {}", key, placeholders.getPath());
            return StringUtils.EMPTY;
        }
        return value.trim();
    }

    /**
     * Swaps the author host segment for the publish one. AEM as a Cloud Service hostnames are
     * {@code author-pNNNNN-eNNNNNN} / {@code publish-pNNNNN-eNNNNNN}, so only the leading segment
     * differs. A value that is already a publish host is returned untouched.
     */
    private String toPublishHost(final String hostname) {
        final String trimmed = StringUtils.removeEnd(StringUtils.trimToEmpty(hostname), "/");
        if (!StringUtils.contains(trimmed, authorSegment)) {
            return trimmed;
        }
        return StringUtils.replaceOnce(trimmed, authorSegment, publishSegment);
    }

    /**
     * @return the placeholders resource for the site {@code page} belongs to
     */
    private Resource findPlaceholders(final Page page) {
        final String siteRoot = siteRoot(page.getPath());
        if (siteRoot == null) {
            return null;
        }
        final Page placeholders = page.getPageManager().getPage(siteRoot + "/" + PLACEHOLDERS);
        if (placeholders != null) {
            return placeholders.getContentResource() != null
                    ? placeholders.getContentResource()
                    : placeholders.adaptTo(Resource.class);
        }
        // Not every site models it as a page; fall back to a plain node.
        return page.getContentResource() == null
                ? null
                : page.getContentResource().getResourceResolver().getResource(siteRoot + "/" + PLACEHOLDERS);
    }

    /**
     * @return {@code /content/<site>} for any path beneath it, or null when the path is not a site
     */
    static String siteRoot(final String path) {
        if (!StringUtils.startsWith(path, CONTENT_ROOT)) {
            return null;
        }
        final String remainder = path.substring(CONTENT_ROOT.length());
        final String site = StringUtils.substringBefore(remainder, "/");
        return StringUtils.isEmpty(site) ? null : CONTENT_ROOT + site;
    }

    /**
     * Breadth-first search for the hostname, tolerating both a direct property and the key/value
     * row shape used by Edge Delivery placeholder sheets.
     */
    private String findEntry(final Resource root, final String key) {
        final Deque<Resource> queue = new ArrayDeque<>();
        queue.add(root);
        int visited = 0;

        while (!queue.isEmpty() && visited++ < MAX_NODES) {
            final Resource current = queue.poll();
            final Map<String, Object> properties = current.getValueMap();

            final String direct = asString(properties.get(key));
            if (StringUtils.isNotBlank(direct)) {
                return direct;
            }

            final String fromRow = valueFromKeyValueRow(properties, key);
            if (StringUtils.isNotBlank(fromRow)) {
                return fromRow;
            }

            for (final Resource child : current.getChildren()) {
                queue.add(child);
            }
        }
        return StringUtils.EMPTY;
    }

    /**
     * Handles the sheet shape, where one node holds {@code key=<name>} alongside the value column.
     */
    private String valueFromKeyValueRow(final Map<String, Object> properties, final String key) {
        boolean isMatchingRow = false;
        for (final Map.Entry<String, Object> entry : properties.entrySet()) {
            if (StringUtils.equalsIgnoreCase(key, asString(entry.getValue()))
                    && !key.equalsIgnoreCase(entry.getKey())) {
                isMatchingRow = true;
                break;
            }
        }
        if (!isMatchingRow) {
            return StringUtils.EMPTY;
        }
        for (final String candidate : VALUE_PROPERTIES) {
            final String value = asString(properties.get(candidate));
            if (StringUtils.isNotBlank(value)) {
                return value;
            }
        }
        return StringUtils.EMPTY;
    }

    private static String asString(final Object value) {
        return value instanceof String ? (String) value : null;
    }
}

package com.dept.contentbridge.core.services.impl;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.day.cq.wcm.api.Page;

/**
 * Fetches products through an AEM persisted GraphQL query.
 *
 * <p>Preferred over reading Content Fragments directly because the query is a contract owned by
 * whoever owns the fragment model: it names the fields, resolves references, and hands back
 * {@code _publishUrl} for images - which the fragment API does not give without rebuilding AEM's own
 * externalisation rules here.</p>
 *
 * <p>The output is the same fixed product schema the Content Fragment reader produces. The source
 * changes; what a client parses does not. That is what makes the two interchangeable per site and
 * what lets the fallback be invisible.</p>
 */
final class GraphQlProductSource {

    private static final Logger LOG = LoggerFactory.getLogger(GraphQlProductSource.class);

    /** Query variable naming the folder to search, matching the persisted query's signature. */
    static final String VAR_PATH = "_path";

    /* ---- request parameters and placeholder keys, checked in that order ---- */
    static final String PN_CONFIG = "gqlConfig";
    static final String PN_QUERY = "gqlQuery";
    static final String PN_PRODUCTS_PATH = "productsPath";

    /**
     * Image URL preference. {@code _publishUrl} is the only one of the three that is both absolute
     * and correct for a client outside AEM: {@code _authorUrl} needs author credentials, and
     * {@code _dynamicUrl} is Dynamic Media, which resolves only where DM is configured for that
     * asset.
     */
    private static final String IMAGE_URL_FIELD = "_publishUrl";
    private static final String[] IMAGE_URL_FALLBACKS = {"_publishUrl", "_authorUrl", "_dynamicUrl"};

    /** Rich-text fields arrive as an object; the client renders HTML. */
    private static final String[] RICH_TEXT_FIELDS = {"html", "markdown", "plaintext"};

    private final GraphQlClient client;
    private final ProductExporter mapper;

    GraphQlProductSource(final GraphQlClient client, final ProductExporter mapper) {
        this.client = client;
        this.mapper = mapper;
    }

    /**
     * @param page      any page in the site, used only for its resource resolver
     * @param query     the resolved query; its path has already been checked against the allowed
     *                  roots by {@link GraphQlQuery#resolve}
     * @param assetHost publish host for any repository path still left relative
     * @return the products, or an empty list when this site has no query configured or the call
     *         failed. An empty list means "fall back", never "this site has no products".
     */
    List<Map<String, Object>> export(final Page page, final GraphQlQuery query,
                                     final String assetHost) {
        if (!query.isConfigured() || !client.isAvailable()) {
            return Collections.emptyList();
        }
        final Resource resource = page.adaptTo(Resource.class);
        if (resource == null) {
            return Collections.emptyList();
        }
        final ResourceResolver resolver = resource.getResourceResolver();

        final Map<String, String> variables = new LinkedHashMap<>();
        variables.put(VAR_PATH, query.getPath());

        LOG.debug("Running persisted query {}/{} with {}={}",
                query.getConfigName(), query.getQueryName(), VAR_PATH, query.getPath());

        final List<Map<String, Object>> items = GraphQlClient.itemsOf(
                client.execute(resolver, query.getConfigName(), query.getQueryName(), variables));

        if (items.isEmpty()) {
            return Collections.emptyList();
        }

        final List<Map<String, Object>> products = new ArrayList<>(items.size());
        for (final Map<String, Object> item : items) {
            products.add(mapper.productFrom(flatten(item), fallbackId(item), assetHost));
        }
        LOG.debug("Persisted query {}/{} produced {} products",
                query.getConfigName(), query.getQueryName(), products.size());
        return products;
    }

    /**
     * Flattens a GraphQL item into the flat name/value map the product mapper reads, so that the
     * alias table and type coercion are shared with the Content Fragment path rather than written
     * twice.
     *
     * <p>Two shapes need unwrapping. An image is an {@code ImageRef} carrying three URLs, of which
     * one is wanted; a rich-text field is an object carrying the same content in several formats.
     * Everything else is a scalar or a list of scalars.</p>
     */
    private Map<String, String> flatten(final Map<String, Object> item) {
        final Map<String, String> values = new LinkedHashMap<>();
        for (final Map.Entry<String, Object> entry : item.entrySet()) {
            final String flattened = stringify(entry.getValue());
            if (flattened != null) {
                values.put(entry.getKey(), flattened);
            }
        }
        return values;
    }

    private String stringify(final Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Map) {
            return fromObject((Map<?, ?>) value);
        }
        if (value instanceof List) {
            // Lists of scalars are joined; the mapper splits them again for the keys that want a
            // list, which keeps that decision in one place.
            final List<String> parts = new ArrayList<>();
            for (final Object item : (List<?>) value) {
                final String part = stringify(item);
                if (StringUtils.isNotBlank(part)) {
                    parts.add(part);
                }
            }
            return parts.isEmpty() ? null : String.join(",", parts);
        }
        return String.valueOf(value);
    }

    /**
     * An image reference or a rich-text object. Tried in preference order, so an asset with no
     * publish URL still yields something rather than disappearing.
     */
    private String fromObject(final Map<?, ?> object) {
        for (final String field : IMAGE_URL_FALLBACKS) {
            final Object url = object.get(field);
            if (url instanceof String && StringUtils.isNotBlank((String) url)) {
                if (!IMAGE_URL_FIELD.equals(field)) {
                    LOG.debug("Asset has no {} - using {} instead", IMAGE_URL_FIELD, field);
                }
                return (String) url;
            }
        }
        for (final String field : RICH_TEXT_FIELDS) {
            final Object text = object.get(field);
            if (text instanceof String && StringUtils.isNotBlank((String) text)) {
                return (String) text;
            }
        }
        return null;
    }

    /** Identity has to resolve to something: a product with no id is useless to a client. */
    private String fallbackId(final Map<String, Object> item) {
        for (final String key : new String[] {"id", "_path", "name"}) {
            final Object value = item.get(key);
            if (value instanceof String && StringUtils.isNotBlank((String) value)) {
                return StringUtils.substringAfterLast((String) value, "/").isEmpty()
                        ? (String) value
                        : StringUtils.substringAfterLast((String) value, "/");
            }
        }
        return null;
    }
}

package com.dept.contentbridge.core.services.impl;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.engine.SlingRequestProcessor;
import org.apache.sling.servlethelpers.internalrequests.SlingInternalRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Runs an AEM persisted GraphQL query without leaving the JVM.
 *
 * <p>The endpoint is an ordinary Sling servlet:</p>
 *
 * <pre>
 * /graphql/execute.json/{config}/{query};_path=/content/dam/binji/products
 * </pre>
 *
 * <p>ContentBridge runs inside the same instance that serves it, so an HTTP call to
 * {@code localhost} would take a second request thread for every export, need credentials on author,
 * and need a host and port configured per environment. Dispatching internally through
 * {@link SlingRequestProcessor} avoids all three, and reuses the resolver the export already holds -
 * so the query runs with exactly the read access the rest of the export has, not more.</p>
 *
 * <p>A GraphQL endpoint answers 200 with an {@code errors} array when a query fails, so the status
 * code alone does not say whether the call worked. Both are checked.</p>
 */
final class GraphQlClient {

    private static final Logger LOG = LoggerFactory.getLogger(GraphQlClient.class);

    /** Persisted queries live under this path; the config and query name follow it. */
    static final String EXECUTE_ROOT = "/graphql/execute.json";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final SlingRequestProcessor requestProcessor;

    GraphQlClient(final SlingRequestProcessor requestProcessor) {
        this.requestProcessor = requestProcessor;
    }

    /** @return true when this instance can dispatch at all */
    boolean isAvailable() {
        return requestProcessor != null;
    }

    /**
     * Executes a persisted query and returns its {@code data} block.
     *
     * @param resolver   the resolver the query runs as
     * @param configName the GraphQL endpoint configuration, e.g. {@code dsn-eds-configuration}
     * @param queryName  the persisted query, e.g. {@code productListByPathBinji}
     * @param variables  query variables, appended as request parameters
     * @return the {@code data} object, or an empty map when the query could not be run
     */
    Map<String, Object> execute(final ResourceResolver resolver, final String configName,
                                final String queryName, final Map<String, String> variables) {
        if (!isAvailable()) {
            LOG.warn("No SlingRequestProcessor available - cannot run persisted queries");
            return java.util.Collections.emptyMap();
        }
        // Persisted query variables MUST arrive as matrix parameters on the path itself
        // (;name=value pairs) - request parameters via withParameter() reach the endpoint but the
        // GraphQL query executor never reads them; the query then sees the variable as null and
        // fails validation on NonNull types. The public URL Adobe documents also uses matrix form
        // (;_path=/content/dam/luma3), so building the path this way matches what a caller would
        // see going through the HTTP endpoint directly.
        final StringBuilder pathBuilder = new StringBuilder(EXECUTE_ROOT)
                .append('/').append(StringUtils.strip(configName, "/"))
                .append('/').append(StringUtils.strip(queryName, "/"));
        for (final Map.Entry<String, String> variable : variables.entrySet()) {
            // Matrix values are appended literally: URL-encoding the forward slashes in a path
            // value (/content/dam/foo -> %2Fcontent%2Fdam%2Ffoo) confuses Sling's routing, and the
            // matrix delimiter itself already separates variable from path so no further escaping
            // is needed for the character set the callers pass here (safe-name-restricted config +
            // query + allowed-root-restricted content path).
            pathBuilder.append(';').append(variable.getKey()).append('=').append(variable.getValue());
        }
        final String path = pathBuilder.toString();

        try {
            final SlingInternalRequest request =
                    new SlingInternalRequest(resolver, requestProcessor, path);

            final String body = request.execute().checkStatus(200).getResponseAsString();
            return dataOf(body, configName, queryName);
        } catch (final Exception e) {
            // A catalogue is one part of the envelope. Whatever went wrong - endpoint missing, query
            // not registered, resolver denied - the page export still has to answer.
            LOG.warn("Persisted query {}/{} could not be run - falling back", configName, queryName, e);
            return java.util.Collections.emptyMap();
        }
    }

    /**
     * Unwraps the GraphQL envelope, treating a populated {@code errors} array as a failure even
     * though it arrives with a 200.
     */
    private Map<String, Object> dataOf(final String body, final String configName,
                                       final String queryName) throws java.io.IOException {
        if (StringUtils.isBlank(body)) {
            LOG.warn("Persisted query {}/{} returned an empty body", configName, queryName);
            return java.util.Collections.emptyMap();
        }
        final Map<String, Object> envelope =
                MAPPER.readValue(body, new TypeReference<LinkedHashMap<String, Object>>() { });

        final Object errors = envelope.get("errors");
        if (errors instanceof List && !((List<?>) errors).isEmpty()) {
            LOG.warn("Persisted query {}/{} reported errors: {}", configName, queryName, errors);
            return java.util.Collections.emptyMap();
        }

        final Object data = envelope.get("data");
        if (!(data instanceof Map)) {
            LOG.warn("Persisted query {}/{} returned no data block", configName, queryName);
            return java.util.Collections.emptyMap();
        }
        @SuppressWarnings("unchecked")
        final Map<String, Object> typed = (Map<String, Object>) data;
        return typed;
    }

    /**
     * Finds the item list inside a {@code data} block.
     *
     * <p>The list is named after the Content Fragment model - {@code binjiProductModelList} in one
     * site, something else in the next - so it cannot be a constant. The shape is stable even though
     * the name is not: one entry under {@code data}, holding an {@code items} array. Where a query
     * selects several lists, the first with {@code items} wins.</p>
     *
     * @return the items, or an empty list when the data block holds none
     */
    static List<Map<String, Object>> itemsOf(final Map<String, Object> data) {
        for (final Object value : data.values()) {
            if (!(value instanceof Map)) {
                continue;
            }
            final Object items = ((Map<?, ?>) value).get("items");
            if (items instanceof List) {
                final List<Map<String, Object>> typed = new java.util.ArrayList<>();
                for (final Object item : (List<?>) items) {
                    if (item instanceof Map) {
                        @SuppressWarnings("unchecked")
                        final Map<String, Object> entry = (Map<String, Object>) item;
                        typed.add(entry);
                    }
                }
                return typed;
            }
        }
        return java.util.Collections.emptyList();
    }
}

package com.dept.contentbridge.core.services.impl;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.servlet.ServletException;

import org.apache.commons.lang3.StringUtils;
import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.request.builder.Builders;
import org.apache.sling.api.request.builder.SlingHttpServletResponseResult;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.engine.SlingRequestProcessor;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.dept.contentbridge.core.services.GraphQLSiteConfig;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Invokes an AEM Sites persisted GraphQL query in-process (no HTTP round-trip) and returns each
 * product as a raw map.
 *
 * <p>Uses {@link SlingRequestProcessor} + the Sling request/response builder API to dispatch to
 * {@code /graphql/execute.json/&lt;config&gt;/&lt;query&gt;} with the caller's own resolver, so
 * auth is whatever the caller opened - the {@code contentbridge-reader} service user for the
 * exporter, an author for direct testing. Missing configs and non-2xx responses degrade to an
 * empty list; {@link ProductCatalog} falls back to the JCR walk on empty.</p>
 */
@Component(service = GraphQLProductClient.class,
           property = {"service.description=ContentBridge - GraphQL product client"})
public class GraphQLProductClient {

    private static final Logger LOG = LoggerFactory.getLogger(GraphQLProductClient.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** The Sites persisted-query servlet is registered under this path. */
    private static final String SERVLET_PATH = "/graphql/execute";

    @Reference
    private SlingRequestProcessor requestProcessor;

    /**
     * @param config   which persisted query to run
     * @param path     value to pass as the query's path variable, or blank to use {@code defaultPath}
     * @param resolver the resolver to run the query as
     * @return the products the query returned, in order, or an empty list on any failure
     */
    List<Map<String, Object>> fetch(final GraphQLSiteConfig config, final String path,
                                    final ResourceResolver resolver) {
        if (config == null || resolver == null || requestProcessor == null) {
            return Collections.emptyList();
        }
        final String actualPath = StringUtils.defaultIfBlank(path, config.getDefaultPath());
        if (StringUtils.isBlank(actualPath)) {
            LOG.debug("GraphQL fetch for {} skipped - no path", config.getSiteRoot());
            return Collections.emptyList();
        }
        // The persisted-query URL: /graphql/execute.json/<config>/<query>?<var>=<path>
        // Rendered via the builder as: resource=/graphql/execute, extension=json,
        // suffix=/<config>/<query>, one query parameter.
        final Resource resource = resolver.resolve(SERVLET_PATH);
        final SlingHttpServletRequest request = Builders.newRequestBuilder(resource)
                .withRequestMethod("GET")
                .withExtension("json")
                .withSuffix("/" + config.getConfigName() + "/" + config.getQueryName())
                .withParameter(config.getPathVariable(), actualPath)
                .build();
        final SlingHttpServletResponseResult response = Builders.newResponseBuilder().build();
        try {
            requestProcessor.processRequest(request, response, resolver);
        } catch (final ServletException | IOException | RuntimeException e) {
            LOG.warn("GraphQL fetch failed for {} at {}", config.getSiteRoot(), actualPath, e);
            return Collections.emptyList();
        }
        if (response.getStatus() >= 300) {
            LOG.warn("GraphQL fetch for {} at {} returned status {}",
                    config.getSiteRoot(), actualPath, response.getStatus());
            return Collections.emptyList();
        }
        final String body = response.getOutputAsString();
        if (StringUtils.isBlank(body)) {
            return Collections.emptyList();
        }
        try {
            return extractItems(MAPPER.readValue(body,
                    new TypeReference<LinkedHashMap<String, Object>>() { }));
        } catch (final IOException e) {
            LOG.warn("GraphQL response for {} at {} was not JSON", config.getSiteRoot(), actualPath, e);
            return Collections.emptyList();
        }
    }

    /**
     * Unwraps the {@code data.<listName>.items} array from the GraphQL response. The list name varies
     * per query; the first non-null child under {@code data} that carries an {@code items} array wins,
     * which lets us stay unbound to a specific query shape.
     */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> extractItems(final Map<String, Object> body) {
        if (body == null || body.isEmpty()) {
            return Collections.emptyList();
        }
        final Object data = body.get("data");
        if (!(data instanceof Map)) {
            return Collections.emptyList();
        }
        for (final Object child : ((Map<String, Object>) data).values()) {
            if (!(child instanceof Map)) {
                continue;
            }
            final Object items = ((Map<String, Object>) child).get("items");
            if (items instanceof List) {
                final List<Map<String, Object>> out = new ArrayList<>();
                for (final Object item : (List<?>) items) {
                    if (item instanceof Map) {
                        out.add((Map<String, Object>) item);
                    }
                }
                return out;
            }
        }
        return Collections.emptyList();
    }
}

package com.edsMobileApp.core.servlets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import javax.servlet.http.HttpServletResponse;

import org.apache.sling.api.resource.Resource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import com.edsMobileApp.core.ContentBridgeTestContext;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.wcm.testing.mock.aem.junit5.AemContext;
import io.wcm.testing.mock.aem.junit5.AemContextExtension;

@ExtendWith(AemContextExtension.class)
class PageJsonServletTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final AemContext context = ContentBridgeTestContext.newContext();

    private PageJsonServlet servlet;

    @BeforeEach
    void setUp() {
        ContentBridgeTestContext.setUp(context);
        servlet = context.registerInjectActivateService(new PageJsonServlet());

        // The servlet is bound by resource type, so requests arrive on the endpoint node rather
        // than on a path. Mirror that here instead of invoking doGet against a bare request.
        final Resource endpoint = context.create().resource("/apps/contentbridge/api",
                "sling:resourceType", PageJsonServlet.RESOURCE_TYPE);
        context.currentResource(endpoint);
        context.requestPathInfo().setExtension("json");
    }

    private Map<String, Object> body() throws Exception {
        return MAPPER.readValue(context.response().getOutputAsString(),
                new TypeReference<Map<String, Object>>() { });
    }

    @Test
    @DisplayName("returns the app payload for a valid path")
    void happyPath() throws Exception {
        context.request().setParameterMap(Map.of("path", ContentBridgeTestContext.APP_ROOT));
        servlet.doGet(context.request(), context.response());

        assertEquals(HttpServletResponse.SC_OK, context.response().getStatus());
        assertTrue(context.response().getContentType().startsWith("application/json"));
        assertEquals("no-store", context.response().getHeader("Cache-Control"));
        assertEquals("en", ((Map<?, ?>) body().get("meta")).get("id"));
    }

    @Test
    @DisplayName("missing path yields 400 with a JSON error body")
    void missingPath() throws Exception {
        servlet.doGet(context.request(), context.response());

        assertEquals(HttpServletResponse.SC_BAD_REQUEST, context.response().getStatus());
        assertTrue(body().get("error").toString().contains("path"));
    }

    @Test
    @DisplayName("a non-existent page yields 404")
    void missingPage() throws Exception {
        context.request().setParameterMap(Map.of("path", "/content/contentbridge/us/nope"));
        servlet.doGet(context.request(), context.response());

        assertEquals(HttpServletResponse.SC_NOT_FOUND, context.response().getStatus());
    }

    @Test
    @DisplayName("a node carrying a cbJson property is served verbatim, skipping the exporter")
    void rawJsonOverrideServedVerbatim() throws Exception {
        final String raw = "{\"meta\":{\"id\":\"hand-crafted\"},\"content\":[]}";
        context.create().resource("/content/contentbridge/us/en/override-demo", "cbJson", raw);

        context.request().setParameterMap(Map.of("path", "/content/contentbridge/us/en/override-demo"));
        servlet.doGet(context.request(), context.response());

        assertEquals(HttpServletResponse.SC_OK, context.response().getStatus());
        assertEquals("no-store", context.response().getHeader("Cache-Control"));
        assertEquals(raw, context.response().getOutputAsString());
    }
}

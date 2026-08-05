package com.dept.contentbridge.core.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.apache.sling.testing.mock.sling.ResourceResolverType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import com.dept.contentbridge.core.services.impl.FormSchemaServiceImpl;
import com.dept.contentbridge.core.services.impl.PageJsonExporterImpl;
import com.dept.contentbridge.core.services.impl.UrlServiceImpl;
import com.day.cq.wcm.api.Page;

import io.wcm.testing.mock.aem.junit5.AemContext;
import io.wcm.testing.mock.aem.junit5.AemContextExtension;

/**
 * Products from a persisted GraphQL query.
 *
 * <p>No {@code SlingRequestProcessor} is registered here, which is deliberate: it exercises the path
 * an instance takes when the query cannot run at all. The response must still be well-formed and
 * fall back to Content Fragments rather than fail - that is the property worth pinning, because it
 * is what stops a misconfigured catalogue from taking a page export down.</p>
 */
@ExtendWith(AemContextExtension.class)
class GraphQlProductTest {

    private static final String SITE = "/content/binji";
    private static final String HOME = SITE + "/en";

    private final AemContext context = new AemContext(ResourceResolverType.JCR_MOCK);

    private PageJsonExporter exporter;

    @BeforeEach
    void setUp() {
        context.create().page(HOME, "", Map.of("jcr:title", "Home"));
        context.addModelsForPackage("com.dept.contentbridge.core.models.impl");
        context.registerInjectActivateService(new UrlServiceImpl(), "publishDomain", "");
        context.registerInjectActivateService(new FormSchemaServiceImpl());
        context.registerInjectActivateService(new PageJsonExporterImpl(),
                "allowedRoots", new String[] {"/content"}, "cacheMaxAge", 0);

        exporter = context.getService(PageJsonExporter.class);
        assertNotNull(exporter);
    }

    private List<?> products(final ExportOptions options) {
        final Page home = context.pageManager().getPage(HOME);
        return (List<?>) exporter.export(home, options).get("products");
    }

    @Test
    @DisplayName("a query that cannot run leaves a well-formed, empty catalogue")
    void unavailableQueryFallsBack() {
        final List<?> products = products(ExportOptions.of("v2",
                "/content/dam/binji/products", "dsn-eds-configuration", "productListByPathBinji"));

        assertNotNull(products, "products must always be an array, never absent");
        assertTrue(products.isEmpty());
    }

    @Test
    @DisplayName("no query configured is not an error - the site reads its fragments as before")
    void noQueryConfigured() {
        final List<?> products = products(ExportOptions.of("v2", null));

        assertNotNull(products);
        assertTrue(products.isEmpty());
    }

    @Test
    @DisplayName("a products path outside the allowed roots is refused")
    void pathOutsideAllowedRootsIsRefused() {
        // The path reaches a content-reading query straight from the request, so it has to be held
        // to the same boundary as everything else the endpoint serves.
        context.registerInjectActivateService(new PageJsonExporterImpl(),
                "allowedRoots", new String[] {"/content/binji"}, "cacheMaxAge", 0);

        final List<?> products = products(ExportOptions.of("v2",
                "/content/dam/other-tenant/products", "dsn-eds-configuration", "productListByPathBinji"));

        assertTrue(products.isEmpty(), "a refused path must not reach the query");
    }

    @Test
    @DisplayName("the query name is restricted to what can appear in a path segment")
    void malformedQueryNameIsRefused() {
        final List<?> products = products(ExportOptions.of("v2",
                "/content/dam/binji/products", "dsn-eds-configuration", "../../etc/passwd"));

        assertTrue(products.isEmpty());
    }

    @Test
    @DisplayName("placeholders supply the query when the request does not")
    void placeholdersSupplyTheQuery() {
        context.create().page(SITE + "/placeholders", "", Map.of(
                "gqlConfig", "dsn-eds-configuration",
                "gqlQuery", "productListByPathBinji",
                "productsPath", "/content/dam/binji/products"));

        // Nothing on the request: the site's own configuration has to be enough.
        final List<?> products = products(ExportOptions.of("v2", null));

        assertNotNull(products);
        assertTrue(products.isEmpty(), "no processor registered, so it still falls back");
    }

    @Test
    @DisplayName("hand-authored productsJson still wins when nothing else yields anything")
    void authoredProductsSurvive() {
        context.build().resource(HOME + "/jcr:content/contentbridge/extras",
                "productsJson", "[{\"sku\":\"MH13\",\"name\":\"Legacy product\"}]");

        @SuppressWarnings("unchecked")
        final List<Map<String, Object>> products =
                (List<Map<String, Object>>) products(ExportOptions.of("v2",
                        null, "dsn-eds-configuration", "productListByPathBinji"));

        assertEquals(1, products.size(), "the last fallback must still be reachable");
        assertEquals("MH13", products.get(0).get("sku"));
    }
}

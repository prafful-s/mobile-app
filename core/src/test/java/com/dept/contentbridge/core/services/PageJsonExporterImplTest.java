package com.dept.contentbridge.core.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import com.dept.contentbridge.core.ContentBridgeTestContext;
import com.day.cq.wcm.api.Page;

import io.wcm.testing.mock.aem.junit5.AemContext;
import io.wcm.testing.mock.aem.junit5.AemContextExtension;

@ExtendWith(AemContextExtension.class)
class PageJsonExporterImplTest {

    private final AemContext context = ContentBridgeTestContext.newContext();

    private PageJsonExporter exporter;

    @BeforeEach
    void setUp() {
        ContentBridgeTestContext.setUp(context);
        exporter = context.getService(PageJsonExporter.class);
        assertNotNull(exporter);
    }

    private Page page(final String path) {
        return context.pageManager().getPage(path);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> map(final Object value) {
        return (Map<String, Object>) value;
    }

    @Test
    @DisplayName("envelope carries id, meta, screens, branding, integrations, profileViewer, products")
    void envelopeShape() {
        final Map<String, Object> app = exporter.export(page(ContentBridgeTestContext.APP_ROOT));

        assertEquals(List.of("id", "meta", "screens", "branding", "integrations", "profileViewer", "products"),
                List.copyOf(app.keySet()));
        assertEquals("ContentBridge: test-app", app.get("id"));
    }

    @Test
    @DisplayName("meta mirrors the app id and reads navigation from page properties")
    void meta() {
        final Map<String, Object> meta = map(exporter.export(page(ContentBridgeTestContext.APP_ROOT)).get("meta"));

        assertEquals("ContentBridge: test-app", meta.get("id"));
        assertEquals("mobile-v2", meta.get("type"));
        assertEquals("Test App", meta.get("title"));
        assertEquals(Boolean.TRUE, meta.get("useCart"));

        final Map<String, Object> navigation = map(meta.get("navigation"));
        assertEquals("login", navigation.get("loginScreen"));
        assertEquals("user-details", navigation.get("profileScreen"));
        assertEquals("registration", navigation.get("registrationScreen"));
        assertEquals(List.of("account"), navigation.get("authorizedMenu"));
    }

    @Test
    @DisplayName("menu and screens use the same keys, honouring pageName overrides")
    void menuMatchesScreens() {
        final Map<String, Object> app = exporter.export(page(ContentBridgeTestContext.APP_ROOT));
        final Map<String, Object> meta = map(app.get("meta"));
        final Map<String, Object> screens = map(app.get("screens"));

        @SuppressWarnings("unchecked")
        final List<String> menu = (List<String>) map(meta.get("navigation")).get("menu");

        assertEquals(menu, List.copyOf(screens.keySet()));
        assertTrue(menu.contains("en"));
        assertTrue(menu.contains("subpage-1"));
        assertTrue(menu.contains("subpage3"),
                "pageName override should win over the node name, kebab-cased");
    }

    @Test
    @DisplayName("screen key is the page's node name, not its title")
    void screenKeyIsNodeName() {
        final Map<String, Object> screens =
                map(exporter.export(page(ContentBridgeTestContext.APP_ROOT)).get("screens"));

        assertTrue(screens.containsKey("en"), "the app root's node name is 'en', its title is 'Home'");
        assertFalse(screens.containsKey("Home"), "title must not leak into the screen key: " + screens.keySet());
    }

    @Test
    @DisplayName("pages flagged excludeFromApp never appear")
    void excludedPagesAreSkipped() {
        final Map<String, Object> screens =
                map(exporter.export(page(ContentBridgeTestContext.APP_ROOT)).get("screens"));
        assertFalse(screens.containsKey("hidden-page"));
    }

    @Test
    @DisplayName("screen content is an ordered list of meta/content pairs")
    void screenContent() {
        final Map<String, Object> screens =
                map(exporter.export(page(ContentBridgeTestContext.APP_ROOT)).get("screens"));
        final Map<String, Object> home = map(screens.get("en"));

        assertEquals(List.of("name", "title"), List.copyOf(map(home.get("meta")).keySet()),
                "screen meta carries name and title only - no icon");

        @SuppressWarnings("unchecked")
        final List<Map<String, Object>> content = (List<Map<String, Object>>) home.get("content");

        assertEquals(7, content.size());
        assertEquals(List.of("banner", "title", "htmlText", "card", "divider", "image", "does-not-exist"),
                content.stream().map(c -> map(c.get("meta")).get("component")).collect(java.util.stream.Collectors.toList()));
    }

    @Test
    @DisplayName("a resource with no registered model is still exported, named from its resource type")
    void unmappedResourceTypesFallBackToGenericExport() {
        final Map<String, Object> screens =
                map(exporter.export(page(ContentBridgeTestContext.APP_ROOT)).get("screens"));

        @SuppressWarnings("unchecked")
        final List<Map<String, Object>> content =
                (List<Map<String, Object>>) map(screens.get("en")).get("content");

        final Map<String, Object> unmapped = content.get(content.size() - 1);
        assertEquals("does-not-exist", map(unmapped.get("meta")).get("component"),
                "the component name comes from the last segment of the resource type");
        assertNotNull(map(unmapped.get("meta")).get("id"));
    }

    @Test
    @DisplayName("a component type this project has never seen still exports its authored fields")
    void arbitraryProjectComponent() {
        context.build().resource(
                ContentBridgeTestContext.APP_ROOT + "/jcr:content/root/container/acmeTeaser",
                "sling:resourceType", "acme/components/content/teaser",
                "headline", "Quarterly results",
                "fileReference", "/content/dam/acme/chart.png",
                "cq:lastModifiedBy", "admin");

        final Map<String, Object> screens =
                map(exporter.export(page(ContentBridgeTestContext.APP_ROOT)).get("screens"));
        @SuppressWarnings("unchecked")
        final List<Map<String, Object>> content =
                (List<Map<String, Object>>) map(screens.get("en")).get("content");

        final Map<String, Object> teaser = content.stream()
                .filter(c -> "teaser".equals(map(c.get("meta")).get("component")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("acme teaser was not exported"));

        final Map<String, Object> payload = map(teaser.get("content"));
        assertEquals("Quarterly results", payload.get("headline"));
        assertEquals(ContentBridgeTestContext.PUBLISH_DOMAIN + "/content/dam/acme/chart.png",
                payload.get("fileReference"));
        assertFalse(payload.containsKey("cq:lastModifiedBy"), "bookkeeping must not leak");
    }

    @Test
    @DisplayName("requesting a sub page returns the app envelope with only that screen")
    void singleScreenExport() {
        final Map<String, Object> app = exporter.export(page(ContentBridgeTestContext.APP_ROOT + "/subpage-1"));

        assertEquals("ContentBridge: test-app", app.get("id"), "envelope still comes from the app root");
        final Map<String, Object> screens = map(app.get("screens"));
        assertEquals(1, screens.size());
        assertTrue(screens.containsKey("subpage-1"));
    }

    @Test
    @DisplayName("branding keeps numeric types and leaves colour tokens alone")
    void branding() {
        final Map<String, Object> branding =
                map(exporter.export(page(ContentBridgeTestContext.APP_ROOT)).get("branding"));

        // No placeholders page in this fixture, so the host falls back to the configured publish
        // domain - the logo is as unreachable to the client as any other bare repository path.
        assertEquals(ContentBridgeTestContext.PUBLISH_DOMAIN + "/content/dam/contentbridge/brand/logo.png",
                branding.get("logo"));

        // A colour token is not a path and must survive the host pass untouched.
        assertEquals("#000000", branding.get("primaryColor"));
        assertEquals(4L, branding.get("roundness"));
        assertFalse(branding.containsKey("jcr:primaryType"));
    }

    @Test
    @DisplayName("extras JSON blobs are parsed, not echoed as strings")
    void extras() {
        final Map<String, Object> app = exporter.export(page(ContentBridgeTestContext.APP_ROOT));

        assertEquals("public-demo", map(map(app.get("integrations")).get("aep")).get("sandbox"));

        @SuppressWarnings("unchecked")
        final List<Map<String, Object>> products = (List<Map<String, Object>>) app.get("products");
        assertEquals(1, products.size());
        assertEquals("MH13", products.get(0).get("sku"));
    }

    @Test
    @DisplayName("malformed extras JSON degrades to an empty object instead of failing the request")
    void malformedExtras() {
        context.build().resource(
                ContentBridgeTestContext.APP_ROOT + "/jcr:content/contentbridge/extras",
                "integrationsJson", "{not json");

        final Map<String, Object> integrations =
                map(exporter.export(page(ContentBridgeTestContext.APP_ROOT)).get("integrations"));
        assertTrue(integrations.isEmpty());
    }

    @Test
    @DisplayName("allowed-root check matches whole path segments, not string prefixes")
    void allowedRoots() {
        assertTrue(exporter.isAllowed("/content/contentbridge"));
        assertTrue(exporter.isAllowed("/content/contentbridge/us/en"));
        assertFalse(exporter.isAllowed("/content/contentbridge-other/us"),
                "a sibling sharing a name prefix must not be reachable");
        assertFalse(exporter.isAllowed("/etc/passwd"));
        assertFalse(exporter.isAllowed(""));
        assertFalse(exporter.isAllowed(null));
    }
}

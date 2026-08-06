package com.dept.contentbridge.core.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

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
 * Covers AEM Edge Delivery (Franklin) content, whose tree nests
 * {@code root > section > columns > row > col > block > item} and identifies components by a
 * {@code model} property rather than by resource type.
 */
@ExtendWith(AemContextExtension.class)
class FranklinExportTest {

    private static final String PUBLISH_DOMAIN = "https://publish.example.com";
    private static final String HOME = "/content/bodea/language-masters/en";

    private final AemContext context = new AemContext(ResourceResolverType.JCR_MOCK);

    private PageJsonExporter exporter;

    @BeforeEach
    void setUp() {
        context.addModelsForPackage("com.dept.contentbridge.core.models.impl");
        context.load().json("/com/dept/contentbridge/core/franklin.json", "/content/bodea");

        context.registerInjectActivateService(new UrlServiceImpl(), "publishDomain", PUBLISH_DOMAIN);
        context.registerInjectActivateService(new FormSchemaServiceImpl());
        context.registerInjectActivateService(new PageJsonExporterImpl(),
                "allowedRoots", new String[] {"/content/contentbridge", "/content/bodea"},
                "maxDepth", 3,
                "cacheMaxAge", 0);

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

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> content(final String screenKey) {
        final Map<String, Object> screens = map(exporter.export(page(HOME)).get("screens"));
        return (List<Map<String, Object>>) map(screens.get(screenKey)).get("content");
    }

    private List<String> componentNames(final String screenKey) {
        return content(screenKey).stream()
                .map(c -> String.valueOf(map(c.get("meta")).get("component")))
                .collect(Collectors.toList());
    }

    @Test
    @DisplayName("sections, columns and row/col wrappers are flattened away")
    void structuralNodesAreFlattened() {
        final List<String> names = componentNames("en");

        assertEquals(List.of("hero", "text", "card", "card", "teaser", "title", "separator"), names);
        assertFalse(names.contains("section"), "sections must not appear as components");
        assertFalse(names.contains("columns"), "columns must not appear as components");
    }

    @Test
    @DisplayName("a container block is walked through, not exported as an empty component")
    void containerBlocksAreNotExported() {
        final List<String> names = componentNames("en");

        assertFalse(names.contains("cards"), "the cards block wraps items and carries no content");
        assertEquals(2, names.stream().filter("card"::equals).count());
    }

    @Test
    @DisplayName("component identity comes from the model property")
    void modelDrivesComponentName() {
        final Map<String, Object> hero = content("en").get(0);
        assertEquals("hero", map(hero.get("meta")).get("component"));

        final Map<String, Object> heroContent = map(hero.get("content"));
        assertEquals("<h1>Digital Assets Are Changing The Game</h1>", heroContent.get("text"));
        assertEquals("button", heroContent.get("ctastyle"));
    }

    @Test
    @DisplayName("every repository path is externalised - pages as well as assets")
    void repositoryPathsAreExternalised() {
        final Map<String, Object> heroContent = map(content("en").get(0).get("content"));
        assertEquals(PUBLISH_DOMAIN + "/content/dam/bodea/en/images/hero.png", heroContent.get("image"));

        final Map<String, Object> cardContent = map(content("en").get(2).get("content"));
        assertEquals(PUBLISH_DOMAIN + "/content/dam/bodea/en/images/card1.jpg", cardContent.get("image"));
        assertEquals(PUBLISH_DOMAIN + "/content/bodea/language-masters/en/machine-learning-in-the-digital-age",
                cardContent.get("link"),
                "a page link is as unusable as a bare asset path to a client with no AEM");
    }

    @Test
    @DisplayName("bookkeeping properties never leak into content")
    void bookkeepingIsStripped() {
        for (final Map<String, Object> component : content("en")) {
            final Map<String, Object> payload = map(component.get("content"));
            for (final String key : payload.keySet()) {
                assertFalse(key.startsWith("jcr:") || key.startsWith("sling:") || key.startsWith("cq:"),
                        "leaked system property: " + key);
                assertFalse(List.of("model", "modelFields", "name", "filter").contains(key),
                        "leaked bookkeeping property: " + key);
            }
        }
    }

    @Test
    @DisplayName("modelFields drives field order, and undeclared authored fields still survive")
    void modelFieldsOrdersContent() {
        final Map<String, Object> teaser = content("en").get(4);
        assertEquals("teaser", map(teaser.get("meta")).get("component"));

        final Map<String, Object> payload = map(teaser.get("content"));
        assertEquals(List.of("title", "teaserStyle", "imageRef", "buttonText", "btn-link"),
                List.copyOf(payload.keySet()).subList(0, 5));
        assertTrue(payload.containsKey("videoReference"),
                "a property missing from modelFields must not be silently dropped");
    }

    @Test
    @DisplayName("a component with no authored fields still exports with an empty content object")
    void emptyComponent() {
        final Map<String, Object> separator = content("en").get(6);
        assertEquals("separator", map(separator.get("meta")).get("component"));
        assertTrue(map(separator.get("content")).isEmpty());
    }

    @Test
    @DisplayName("child pages are exported as their own screens")
    void childPagesBecomeScreens() {
        final Map<String, Object> screens = map(exporter.export(page(HOME)).get("screens"));

        assertTrue(screens.containsKey("en"));
        assertTrue(screens.containsKey("about-us"));
        assertEquals(List.of("newsletter"), componentNames("about-us"));
    }

    @Test
    @DisplayName("the bodea root is served, and unrelated paths are still refused")
    void allowedRoots() {
        assertTrue(exporter.isAllowed("/content/bodea/language-masters/en"));
        assertTrue(exporter.isAllowed("/content/contentbridge/us/en"));
        assertFalse(exporter.isAllowed("/content/we-retail"));
    }
}

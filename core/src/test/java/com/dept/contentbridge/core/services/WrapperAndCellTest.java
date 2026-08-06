package com.dept.contentbridge.core.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
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
 * Things that are structure rather than content must not reach the client.
 *
 * <p>Both cases here were visible in a real we-healthcare export: an empty authored column arriving
 * as a component named after its JCR primary type, and Edge Delivery's {@code cards} wrapper
 * arriving as an empty shell next to the cards it wraps.</p>
 */
@ExtendWith(AemContextExtension.class)
class WrapperAndCellTest {

    private static final String HOME = "/content/acme/en";

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

    @SuppressWarnings("unchecked")
    private Map<String, Object> map(final Object value) {
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> list(final Object value) {
        return (List<Map<String, Object>>) value;
    }

    private List<String> componentNames(final ExportOptions options) {
        final Page home = context.pageManager().getPage(HOME);
        final Map<String, Object> screens = map(exporter.export(home, options).get("screens"));
        final List<String> names = new ArrayList<>();
        collect(list(map(screens.get("en")).get("content")), names);
        return names;
    }

    /** Flattens the v2 tree so a stray component cannot hide inside a panel. */
    private void collect(final List<Map<String, Object>> components, final List<String> names) {
        for (final Map<String, Object> component : components) {
            names.add(String.valueOf(map(component.get("meta")).get("component")));
            final Object content = component.get("content");
            if (content instanceof List) {
                collect(list(content), names);
            }
        }
    }

    @Test
    @DisplayName("an empty authored column is dropped, not exported as its JCR primary type")
    void emptyCellsAreDropped() {
        context.build().resource(HOME + "/jcr:content/root/columns",
                "sling:resourceType", "core/franklin/components/columns/v1/columns");
        // col1 holds content; col2 and col3 are columns the author left blank.
        context.build().resource(HOME + "/jcr:content/root/columns/col1/block",
                "sling:resourceType", "core/franklin/components/block/v1/block",
                "model", "text", "text", "<p>Hello</p>");
        context.build().resource(HOME + "/jcr:content/root/columns/col2");
        context.build().resource(HOME + "/jcr:content/root/columns/col3");

        final List<String> names = componentNames(ExportOptions.of("v2", null));

        assertFalse(names.contains("nt:unstructured"),
                "a JCR primary type is not a component name: " + names);
        assertTrue(names.contains("htmlText"), "the column that had content still exports: " + names);
    }

    @Test
    @DisplayName("a wrapper block carrying only editor bookkeeping is walked through, not exported")
    void editorBookkeepingDoesNotResurrectWrappers() {
        // The cards wrapper has no authored content of its own - only the Universal Editor's marker.
        context.build().resource(HOME + "/jcr:content/root/cards",
                "sling:resourceType", "core/franklin/components/block/v1/block",
                "aueComponentId", "cards");
        context.build().resource(HOME + "/jcr:content/root/cards/item",
                "sling:resourceType", "core/franklin/components/block/item/v1/item",
                "model", "card", "text", "<h2>A card</h2>", "image", "/content/dam/acme/c.png");

        final List<String> names = componentNames(ExportOptions.of("v2", null));

        assertEquals(List.of("card"), names,
                "the wrapper contributes its items and nothing else: " + names);
    }

    @Test
    @DisplayName("the editor marker never reaches the payload")
    void markerIsStripped() {
        context.build().resource(HOME + "/jcr:content/root/block",
                "sling:resourceType", "core/franklin/components/block/v1/block",
                "model", "get-offer", "aueComponentId", "get-offer", "button-label", "Subscribe");

        final Page home = context.pageManager().getPage(HOME);
        final Map<String, Object> screens = map(exporter.export(home).get("screens"));
        final Map<String, Object> content =
                map(list(map(screens.get("en")).get("content")).get(0).get("content"));

        assertEquals("Subscribe", content.get("button-label"));
        assertFalse(content.containsKey("aueComponentId"), "editor bookkeeping is not content");
    }
}

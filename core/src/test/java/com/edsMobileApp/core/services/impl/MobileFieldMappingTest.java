package com.edsMobileApp.core.services.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * The hero and product-detail rules read their Mobile Specific image-fit (and, for hero, the show
 * button switch) in addition to what they derived before. Each rule's own default still applies
 * when nothing mobile is authored, so existing content exports exactly what it did.
 */
class MobileFieldMappingTest {

    private static final String LINKED_TEXT = "<h1>Title</h1><p>Body</p><p><a href=\"/content/s/en/trip\">Book</a></p>";

    private final ComponentMappings mappings = ComponentMappings.load();

    private static Map<String, Object> authored(final Object... keyValue) {
        final Map<String, Object> out = new HashMap<>();
        for (int i = 0; i < keyValue.length; i += 2) {
            out.put((String) keyValue[i], keyValue[i + 1]);
        }
        return out;
    }

    private Map<String, Object> hero(final Map<String, Object> source) {
        return mappings.find("hero").apply(source, Map.of());
    }

    private Map<String, Object> productDetail(final Map<String, Object> source) {
        return mappings.find("product-detail").apply(source, Map.of());
    }

    // ---------- hero: imageResize ----------

    @Test
    void aHeroWithoutMobileImageResizeStillExportsCover() {
        assertEquals("cover", hero(authored("image", "/a.jpg", "text", "<h1>T</h1>")).get("imageResize"));
    }

    @Test
    void aHeroReadsMobileImageResize() {
        assertEquals("contain",
                hero(authored("image", "/a.jpg", "text", "<h1>T</h1>", "mobileimageresize", "contain"))
                        .get("imageResize"));
    }

    // ---------- hero: showButton ----------

    @Test
    void withoutTheSwitchShowButtonStillFollowsTheLink() {
        assertEquals(true, hero(authored("image", "/a.jpg", "text", LINKED_TEXT)).get("showButton"));
        assertEquals(false, hero(authored("image", "/a.jpg", "text", "<h1>T</h1>")).get("showButton"));
    }

    @Test
    void anExplicitSwitchOnShowsTheButtonEvenWithoutALink() {
        assertEquals(true,
                hero(authored("image", "/a.jpg", "text", "<h1>T</h1>", "mobileshowbutton", "true"))
                        .get("showButton"));
        assertEquals(true,
                hero(authored("image", "/a.jpg", "text", "<h1>T</h1>", "mobileshowbutton", Boolean.TRUE))
                        .get("showButton"));
    }

    @Test
    void anExplicitSwitchOffHidesTheButtonEvenWithALink() {
        assertEquals(false,
                hero(authored("image", "/a.jpg", "text", LINKED_TEXT, "mobileshowbutton", "false"))
                        .get("showButton"));
        assertEquals(false,
                hero(authored("image", "/a.jpg", "text", LINKED_TEXT, "mobileshowbutton", Boolean.FALSE))
                        .get("showButton"));
    }

    // ---------- product-detail ----------

    @Test
    void productDetailDefaultsToContain() {
        final Map<String, Object> mapped = productDetail(authored());
        assertEquals("contain", mapped.get("imageResize"));
        assertEquals(true, mapped.containsKey("product"));
    }

    @Test
    void productDetailReadsMobileImageResize() {
        assertEquals("cover", productDetail(authored("mobileimageresize", "cover")).get("imageResize"));
    }
}

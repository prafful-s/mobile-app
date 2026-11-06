package com.edsMobileApp.core.services.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * A card or product card is bold where its rich text is: a {@code strong} or {@code b} tag inside the
 * title makes the title bold, inside the subtitle makes the subtitle bold. Without one the weight stays
 * regular. A banner has no font weight properties in the component catalog, so it is not covered.
 */
class BoldFontWeightTest {

    private static Map<String, Object> map(final String rule, final String... keyValue) {
        final Map<String, Object> authored = new HashMap<>();
        for (int i = 0; i < keyValue.length; i += 2) {
            authored.put(keyValue[i], keyValue[i + 1]);
        }
        final ComponentMapping mapping = ComponentMappings.load().find(rule);
        return mapping.effectiveFor(authored).apply(authored, Map.of());
    }

    @Test
    void aCardIsRegularUnlessItsTitleOrSubtitleIsBold() {
        final String plain = "<h2>Title</h2><p>Sub</p>";
        assertEquals("regular", map("card", "image", "/a.jpg", "text", plain).get("titleFontWeight"));
        assertEquals("regular", map("card", "image", "/a.jpg", "text", plain).get("subtitleFontWeight"));

        final Map<String, Object> boldTitle = map("card", "image", "/a.jpg",
                "text", "<h3><strong>Title</strong></h3><p>Sub</p>");
        assertEquals("bold", boldTitle.get("titleFontWeight"));
        assertEquals("regular", boldTitle.get("subtitleFontWeight"));

        final Map<String, Object> boldSubtitle = map("card", "image", "/a.jpg",
                "text", "<h3>Title</h3><p><b>Sub</b></p>");
        assertEquals("regular", boldSubtitle.get("titleFontWeight"));
        assertEquals("bold", boldSubtitle.get("subtitleFontWeight"));

        assertEquals("bold", map("card", "image", "/a.jpg", "text", "<h3>Title</h3><ul><li><strong>One</strong></li></ul>")
                .get("subtitleFontWeight"));
    }

    @Test
    void aBoldCallToActionDoesNotMakeACardSubtitleBold() {
        assertEquals("regular", map("card", "image", "/a.jpg",
                "text", "<h3>Title</h3><p><a href=\"/x\"><strong>Go</strong></a></p>").get("subtitleFontWeight"));
    }

    @Test
    void aProductCardIsRegularUnlessItsNameOrDescriptionIsBold() {
        final Map<String, Object> plain = map("product-card", "productName", "Jacket",
                "productDescription", "<p>Warm</p>");
        assertEquals("regular", plain.get("titleFontWeight"));
        assertEquals("regular", plain.get("subtitleFontWeight"));

        final Map<String, Object> bold = map("product-card", "productName", "<strong>Jacket</strong>",
                "productDescription", "<p><b>Warm</b> and dry</p>");
        assertEquals("bold", bold.get("titleFontWeight"));
        assertEquals("bold", bold.get("subtitleFontWeight"));
    }

    @Test
    void aBannerCarriesNoFontWeight() {
        final Map<String, Object> mapped = map("hero", "text", "<h1><strong>Big sale</strong></h1><p><b>Sub</b></p>");
        assertEquals(null, mapped.get("titleFontWeight"));
        assertEquals(null, mapped.get("subtitleFontWeight"));
    }
}

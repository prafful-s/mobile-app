package com.edsMobileApp.core.services.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * A banner, card or product card is bold where its rich text is: a {@code strong} or {@code b} tag
 * inside the title makes the title bold, inside the subtitle makes the subtitle bold. Without one
 * a card keeps its regular weight and a banner carries no weight at all.
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
    void aBannerTitleIsBoldWhenItHoldsAStrongOrBTag() {
        assertEquals("bold", map("hero", "text", "<h1><strong>Big sale</strong></h1><p>Sub</p>")
                .get("titleFontWeight"));
        assertEquals("bold", map("hero", "text", "<h2>Big <b>sale</b></h2><p>Sub</p>").get("titleFontWeight"));
        assertNull(map("hero", "text", "<h1>Big sale</h1><p>Sub</p>").get("titleFontWeight"));
    }

    @Test
    void aBannerSubtitleIsBoldWhenItHoldsAStrongOrBTag() {
        final Map<String, Object> mapped = map("hero",
                "text", "<h1>Title</h1><p><strong>Providing affordable</strong></p>");
        assertEquals("bold", mapped.get("subtitleFontWeight"));
        assertNull(mapped.get("titleFontWeight"), "bold in the subtitle leaves the title alone");
        assertEquals("bold", map("hero", "text", "<h1>Title</h1><h3><b>Sub</b></h3>").get("subtitleFontWeight"));
        assertNull(map("hero", "text", "<h1>Title</h1><p>Sub</p>").get("subtitleFontWeight"));
    }

    @Test
    void aBoldCallToActionDoesNotMakeABannerSubtitleBold() {
        assertNull(map("hero", "text", "<h1>Title</h1><p><a href=\"/x\"><strong>Go</strong></a></p>")
                .get("subtitleFontWeight"));
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
}

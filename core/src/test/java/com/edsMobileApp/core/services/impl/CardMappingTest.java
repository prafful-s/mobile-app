package com.edsMobileApp.core.services.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The card rule layers the mobile-specific authoring ({@code mobilecardstyle}, {@code textcolor})
 * over the web {@code style} it already read. Both are expressed as a second {@link FieldRule} on a
 * target the rule file already writes, which only takes effect when its own source is authored - so
 * the tests that matter most here are the ones asserting a card with no mobile authoring exports
 * exactly what it exported before.
 */
class CardMappingTest {

    private ComponentMapping card;

    @BeforeEach
    void setUp() {
        card = ComponentMappings.load().find("card");
    }

    private Map<String, Object> map(final Map<String, Object> authored) {
        return card.effectiveFor(authored).apply(authored, Map.of());
    }

    private Map<String, Object> styles(final Map<String, Object> authored) {
        return card.effectiveFor(authored).applyStyles(authored, Map.of());
    }

    /**
     * @param keyValue alternating key/value pairs to author on top of the image and text every card
     *                 needs - without an image the rule's {@code requires} sends it to the
     *                 {@code htmlText} fallback instead.
     */
    private static Map<String, Object> authored(final String... keyValue) {
        final Map<String, Object> out = new HashMap<>();
        out.put("image", "/content/dam/x.jpg");
        out.put("text", "<h2>Title</h2>");
        for (int i = 0; i < keyValue.length; i += 2) {
            out.put(keyValue[i], keyValue[i + 1]);
        }
        return out;
    }

    @Test
    void mobileCardStyleWinsOverWebStyle() {
        assertEquals("image-text-one-line",
                map(authored("style", "teaser-card", "mobilecardstyle", "one-line")).get("cardType"));
        assertEquals("overlay",
                map(authored("style", "image-top", "mobilecardstyle", "overlay")).get("cardType"));
        assertEquals("stacked",
                map(authored("style", "teaser-card", "mobilecardstyle", "below")).get("cardType"));
        assertEquals("partial-overlay",
                map(authored("style", "", "mobilecardstyle", "partial-overlay")).get("cardType"));
    }

    @Test
    void unauthoredMobileCardStyleLeavesWebStyleUntouched() {
        assertEquals("overlay", map(authored("style", "teaser-card")).get("cardType"));
        assertEquals("partial-overlay", map(authored("style", "teaser-overlay")).get("cardType"));
        assertEquals("image-text-one-line", map(authored("style", "image-left")).get("cardType"));
        assertEquals("stacked", map(authored("style", "image-top")).get("cardType"));
        assertEquals("stacked", map(authored()).get("cardType"));
    }

    @Test
    void blankMobileCardStyleIsTreatedAsUnauthored() {
        assertEquals("overlay",
                map(authored("style", "teaser-card", "mobilecardstyle", "")).get("cardType"));
    }

    @Test
    void textColorWinsOverTheStyleDerivedColour() {
        assertEquals("#1a1a1a",
                styles(authored("style", "teaser-card", "textcolor", "#1a1a1a")).get("color"));
    }

    @Test
    void unauthoredTextColorLeavesTheStyleDerivedColour() {
        assertEquals("#ffffff", styles(authored("style", "teaser-card")).get("color"));
        assertEquals("#ffffff", styles(authored("style", "teaser-overlay")).get("color"));
        // every other style maps to "", which is blank, which the nullable rule resolves to null
        assertNull(styles(authored("style", "image-top")).get("color"));
    }

    /**
     * An earlier attempt resolved the colour with a single {@code first: ["textcolor", "style"]}
     * rule, which let an unrecognised {@code style} token through as if it were a colour. Two rules
     * on one target is what keeps the two sources' value spaces apart.
     */
    @Test
    void anUnknownStyleDoesNotLeakIntoTheColour() {
        assertNull(styles(authored("style", "some-future-token")).get("color"));
    }
}

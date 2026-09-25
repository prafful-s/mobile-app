package com.edsMobileApp.core.services.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * A {@code category-products-lister} emitted no image-fit or sizing key at all, so a product list's
 * car photos were cropped with nothing an author could say about it - carvelo's Models screen. It
 * now reads the same {@code mobilecardstyle} / {@code mobileimagestyle} vocabulary the card block
 * already uses, plus two sizing fields.
 *
 * <p>All four write only when authored. The block emitted no {@code imageResizeMode},
 * {@code imageWidth} or {@code itemHeight} before, and an unauthored block must still emit none -
 * adding a key the client did not previously receive is a behaviour change, not a config option.</p>
 */
class CategoryProductsListerTest {

    private ComponentMapping rule;

    @BeforeEach
    void setUp() {
        rule = ComponentMappings.load().find("category-products-lister");
    }

    private Map<String, Object> map(final Map<String, Object> authored) {
        return rule.effectiveFor(authored).apply(authored, Map.of());
    }

    private static Map<String, Object> authored(final String... keyValue) {
        final Map<String, Object> out = new HashMap<>();
        for (int i = 0; i < keyValue.length; i += 2) {
            out.put(keyValue[i], keyValue[i + 1]);
        }
        return out;
    }

    /**
     * The rule is {@code skeleton: true}, so every declared target is emitted whether authored or
     * not - there is no "write only when authored" in this rule file. imageResizeMode is therefore
     * a new key on every lister, carrying the cover the client was already applying, and matching
     * newArrival which has always emitted it. Nothing else moves.
     */
    @Test
    void anUnauthoredListerKeepsTodaysValuesAndGainsOnlyTheResizeMode() {
        final Map<String, Object> mapped = map(authored());
        assertEquals("image-text-one-line", mapped.get("itemRenderer"), "today's default renderer");
        assertEquals("cover", mapped.get("imageResizeMode"), "explicit, and what was already happening");
        assertEquals("productList", mapped.get("listType"));
        assertFalse(mapped.containsKey("imageWidth"), "sizing stays out - see the commit message");
        assertFalse(mapped.containsKey("itemHeight"));
    }

    @Test
    void mobileImageStyleSetsTheResizeMode() {
        assertEquals("contain", map(authored("mobileimagestyle", "contain")).get("imageResizeMode"));
        assertEquals("cover", map(authored("mobileimagestyle", "cover")).get("imageResizeMode"));
    }

    @Test
    void anUnknownImageStyleFallsBackToCoverRatherThanLeakingTheToken() {
        assertEquals("cover", map(authored("mobileimagestyle", "squish")).get("imageResizeMode"));
    }

    @Test
    void mobileCardStyleOverridesTheRenderer() {
        assertEquals("stacked", map(authored("mobilecardstyle", "below")).get("itemRenderer"));
        assertEquals("overlay", map(authored("mobilecardstyle", "overlay")).get("itemRenderer"));
        assertEquals("partial-overlay",
                map(authored("mobilecardstyle", "partial-overlay")).get("itemRenderer"));
        assertEquals("image-text-one-line",
                map(authored("mobilecardstyle", "one-line")).get("itemRenderer"));
    }

    /** The block's own itemRenderer still wins when no mobile value is authored. */
    @Test
    void anAuthoredItemRendererSurvivesWithoutMobileCardStyle() {
        assertEquals("stacked", map(authored("itemRenderer", "stacked")).get("itemRenderer"));
    }

}

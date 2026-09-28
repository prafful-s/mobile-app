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
     * not - there is no "write only when authored" here. These three keys are therefore new on
     * every lister, and the resize default is contain rather than the cover the client was applying
     * on its own. That is a deliberate estate-wide change: a cropped product photo was never wanted,
     * it was just what you got with no box defined. Any lister can go back with mobileimagestyle.
     */
    @Test
    void anUnauthoredListerGetsTheNewContainDefaults() {
        final Map<String, Object> mapped = map(authored());
        assertEquals("contain", mapped.get("imageResizeMode"));
        assertEquals(150, mapped.get("imageWidth"));
        assertEquals(150, mapped.get("itemHeight"));
        assertEquals("image-text-one-line", mapped.get("itemRenderer"), "unchanged");
        assertEquals("productList", mapped.get("listType"), "unchanged");
    }

    @Test
    void aListerCanGoBackToCover() {
        assertEquals("cover", map(authored("mobileimagestyle", "cover")).get("imageResizeMode"));
    }

    /** An authored number arrives from JCR as a Long, the compiled default is an Integer - both
     *  serialise as JSON numbers, so compare the value rather than the box. */
    @Test
    void sizingIsAuthorable() {
        final Map<String, Object> mapped = map(authored("mobileimagewidth", "120", "mobileitemheight", "90"));
        assertEquals(120L, ((Number) mapped.get("imageWidth")).longValue());
        assertEquals(90L, ((Number) mapped.get("itemHeight")).longValue());
    }

    /** The client-vocabulary names still work, as newArrival has always allowed. */
    @Test
    void theDirectKeyNamesAreAcceptedToo() {
        assertEquals("cover", map(authored("imageResizeMode", "cover")).get("imageResizeMode"));
        assertEquals(200L, ((Number) map(authored("imageWidth", "200")).get("imageWidth")).longValue());
    }

    @Test
    void mobileImageStyleSetsTheResizeMode() {
        assertEquals("contain", map(authored("mobileimagestyle", "contain")).get("imageResizeMode"));
        assertEquals("cover", map(authored("mobileimagestyle", "cover")).get("imageResizeMode"));
    }

    @Test
    void anUnknownImageStyleFallsBackToTheDefaultRatherThanLeakingTheToken() {
        assertEquals("contain", map(authored("mobileimagestyle", "squish")).get("imageResizeMode"));
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

    /** The lister's own Item variant dropdown - every option passes through, single image included. */
    @Test
    void mobileItemRendererSetsTheRenderer() {
        assertEquals("image-text-one-line",
                map(authored("mobileitemrenderer", "image-text-one-line")).get("itemRenderer"));
        assertEquals("overlay", map(authored("mobileitemrenderer", "overlay")).get("itemRenderer"));
        assertEquals("stacked", map(authored("mobileitemrenderer", "stacked")).get("itemRenderer"));
        assertEquals("image", map(authored("mobileitemrenderer", "image")).get("itemRenderer"));
    }

    @Test
    void mobileItemRendererWinsOverMobileCardStyle() {
        assertEquals("image",
                map(authored("mobileitemrenderer", "image", "mobilecardstyle", "below")).get("itemRenderer"));
    }

    @Test
    void layoutAndSortComeFromTheMobileTab() {
        final Map<String, Object> mapped = map(authored("mobilelayout", "vertical/grid", "mobilesort", "alphabetical"));
        assertEquals("vertical/grid", mapped.get("layout"));
        assertEquals("alphabetical", mapped.get("sort"));
    }

}

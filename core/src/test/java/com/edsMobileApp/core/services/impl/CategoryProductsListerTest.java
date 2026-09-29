package com.edsMobileApp.core.services.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
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
        assertEquals("contain", mapped.get("imageResize"));
        assertEquals(150, mapped.get("imageWidth"));
        assertEquals(150, mapped.get("itemHeight"));
        assertEquals("image-text-one-line", mapped.get("itemRenderer"), "unchanged");
        assertEquals("productList", mapped.get("listType"), "unchanged");
    }

    @Test
    void aListerCanGoBackToCover() {
        assertEquals("cover", map(authored("mobileimagestyle", "cover")).get("imageResize"));
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
        assertEquals("cover", map(authored("imageResizeMode", "cover")).get("imageResize"));
        assertEquals(200L, ((Number) map(authored("imageWidth", "200")).get("imageWidth")).longValue());
    }

    @Test
    void mobileImageStyleSetsTheResizeMode() {
        assertEquals("contain", map(authored("mobileimagestyle", "contain")).get("imageResize"));
        assertEquals("cover", map(authored("mobileimagestyle", "cover")).get("imageResize"));
    }

    @Test
    void anUnknownImageStyleFallsBackToTheDefaultRatherThanLeakingTheToken() {
        assertEquals("contain", map(authored("mobileimagestyle", "squish")).get("imageResize"));
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

    private Map<String, Object> withCategories(final String... tags) {
        final Map<String, Object> source = authored("mobilelayout", "horizontal");
        source.put("_categories", List.of(tags));
        return map(source);
    }

    /**
     * The client lists the catalog products the filter matches - it does not read items - so the
     * filter must cover every category in the folder: a majority vote showed 3 of a 3/3/3 folder.
     */
    @Test
    void aMixedFolderFiltersOnEveryCategoryInIt() {
        assertEquals("(Hybrid|Fuel|Electric).*", withCategories(
                "carvelo:hybrid", "carvelo:hybrid", "carvelo:fuel", "carvelo:electric", "carvelo:fuel").get("filter"));
    }

    @Test
    void aSingleCategoryFolderKeepsItsPlainFilter() {
        assertEquals("Electric.*", withCategories("carvelo:electric", "carvelo:electric", "carvelo:electric").get("filter"));
    }

    @Test
    void itemsInRowComesFromTheMobileTab() {
        assertEquals(3L, ((Number) map(authored("mobileitemsinrow", "3")).get("itemsInRow")).longValue());
        assertEquals(1, map(authored("mobilelayout", "horizontal")).get("itemsInRow"), "unauthored stays 1");
    }

    /** The client's list option is imageResize; imageResizeMode was never read. */
    @Test
    void theResizeModeIsEmittedAsImageResize() {
        final Map<String, Object> mapped = map(authored("mobileimagestyle", "contain"));
        assertEquals("contain", mapped.get("imageResize"));
        assertFalse(mapped.containsKey("imageResizeMode"));
    }

    /** An authored Category filter overrides the folder's own categories. */
    @Test
    void anAuthoredCategoryFilterWins() {
        final Map<String, Object> source = authored("mobilecategoryfilter", "Hybrid, fuel");
        source.put("_categories", List.of("carvelo:electric"));
        assertEquals("(Hybrid|Fuel).*", map(source).get("filter"));
    }

    @Test
    void aSingleAuthoredCategoryGivesAPlainFilter() {
        final Map<String, Object> source = authored("mobilecategoryfilter", "Electric");
        source.put("_categories", List.of("carvelo:hybrid", "carvelo:fuel"));
        assertEquals("Electric.*", map(source).get("filter"));
    }

    @Test
    void aBlankCategoryFilterFallsBackToTheFolder() {
        final Map<String, Object> source = authored("mobilecategoryfilter", "  ");
        source.put("_categories", List.of("carvelo:electric"));
        assertEquals("Electric.*", map(source).get("filter"));
    }

    /** No filter means the whole catalog, so it is never dropped when categories exist. */
    @Test
    void theFilterIsAlwaysEmitted() {
        assertTrue(withCategories("carvelo:accessories").containsKey("filter"));
    }

    @Test
    void layoutAndSortComeFromTheMobileTab() {
        final Map<String, Object> mapped = map(authored("mobilelayout", "vertical/grid", "mobilesort", "alphabetical"));
        assertEquals("vertical/grid", mapped.get("layout"));
        assertEquals("alphabetical", mapped.get("sort"));
    }

}

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
 * A {@code category-products-lister} becomes a {@code list} driven only by its Mobile Specific
 * fields - {@code mobilelayout}, {@code mobilesort}, {@code mobileitemsinrow},
 * {@code mobileitemrenderer}, {@code mobileimagestyle}, {@code mobileimagewidth} and
 * {@code mobileitemheight} - with the {@code filter} built from the categories in its folder.
 * Raw or legacy property names are not read.
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

    /** Only the Mobile Specific fields drive the list; raw/legacy property names are ignored. */
    @Test
    void onlyTheMobileSpecificFieldsAreRead() {
        final Map<String, Object> mapped = map(authored("layout", "horizontal", "sort", "alphabetical",
                "itemsInRow", "4", "itemRenderer", "stacked", "mobilecardstyle", "below",
                "imageResizeMode", "cover", "imageResize", "cover", "imageWidth", "200", "itemHeight", "90"));
        assertEquals("vertical/grid", mapped.get("layout"));
        assertEquals("none", mapped.get("sort"));
        assertEquals(1, mapped.get("itemsInRow"));
        assertEquals("image-text-one-line", mapped.get("itemRenderer"));
        assertEquals("contain", mapped.get("imageResize"));
        assertEquals(150, mapped.get("imageWidth"));
        assertEquals(150, mapped.get("itemHeight"));
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

    /** The lister's own Item variant dropdown - every option passes through, single image included. */
    @Test
    void mobileItemRendererSetsTheRenderer() {
        assertEquals("image-text-one-line",
                map(authored("mobileitemrenderer", "image-text-one-line")).get("itemRenderer"));
        assertEquals("overlay", map(authored("mobileitemrenderer", "overlay")).get("itemRenderer"));
        assertEquals("stacked", map(authored("mobileitemrenderer", "stacked")).get("itemRenderer"));
        assertEquals("image", map(authored("mobileitemrenderer", "image")).get("itemRenderer"));
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

    /** The filter always comes from the folder's categories; a leftover mobilecategoryfilter is ignored. */
    @Test
    void theFilterComesOnlyFromTheFolder() {
        final Map<String, Object> source = authored("mobilecategoryfilter", "Hybrid, fuel");
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

package com.edsMobileApp.core.services.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.models.factory.ModelFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;

import com.day.cq.wcm.api.Page;
import com.edsMobileApp.core.schema.SchemaConstants;
import com.edsMobileApp.core.services.FormSchemaService;

import io.wcm.testing.mock.aem.junit5.AemContext;
import io.wcm.testing.mock.aem.junit5.AemContextExtension;

/**
 * Page export only enters Cards list logic when {@code mobilerenderaslist} is true.
 * Otherwise, each card exports individually, including product and linkless cards.
 *
 * <p>Direct builder tests also exercise list fields and link resolution independently of the
 * page-export toggle gate.</p>
 */
@ExtendWith(AemContextExtension.class)
class CardsAsListTest {

    private final AemContext context = new AemContext();

    private ComponentTreeWalker walker;

    @BeforeEach
    void setUp() {
        walker = new ComponentTreeWalker(null, new UrlServiceImpl(), null, null, ComponentMappings.load());
    }

    private static Set<String> screens(final String... keys) {
        return new HashSet<>(Arrays.asList(keys));
    }

    private Map<String, Object> convert(final Resource block, final Set<String> knownScreens) {
        return walker.cardsBlockAsList(block, 0, knownScreens);
    }

    private List<Map<String, Object>> exportCards(final Boolean optIn, final boolean products,
                                                 final boolean linkless) {
        final Page page = context.create().page("/content/s/en/export-" + optIn);
        context.create().resource(page.getPath() + "/jcr:content/root");
        final String blockPath = page.getPath() + "/jcr:content/root/cards";
        if (optIn == null) {
            context.create().resource(blockPath, "filter", "cards",
                    "sling:resourceType", SchemaConstants.RT_FRANKLIN_BLOCK,
                    "mobileItemRenderer", "overlay");
        } else {
            context.create().resource(blockPath, "filter", "cards", "mobilerenderaslist", optIn,
                    "sling:resourceType", SchemaConstants.RT_FRANKLIN_BLOCK,
                    "mobileItemRenderer", "overlay");
        }
        for (final String key : List.of("mexico", "poland", "united-kingdom", "india")) {
            if (linkless) {
                context.create().resource(blockPath + "/" + key, "model", "card",
                    "sling:resourceType", SchemaConstants.RT_FRANKLIN_BLOCK_ITEM,
                        "image", "/content/dam/s/" + key + ".jpg", "text", "<h2>" + key + "</h2>");
            } else if (products) {
                productCard(blockPath + "/" + key, key);
            } else {
                card(blockPath + "/" + key, key);
            }
        }
        final FormSchemaService schemas = Mockito.mock(FormSchemaService.class);
        Mockito.when(schemas.getSchema(Mockito.anyString())).thenReturn(List.of());
        final ComponentTreeWalker exporter = new ComponentTreeWalker(Mockito.mock(ModelFactory.class),
                new UrlServiceImpl(), schemas, new StyleResolver(new String[0]), ComponentMappings.load());
        return exporter.componentsOf(page, null, true, false,
                screens("mexico", "poland", "united-kingdom", "india"));
    }

    @SuppressWarnings("unchecked")
    private static String component(final Map<String, Object> entry) {
        return (String) ((Map<String, Object>) entry.get("meta")).get("component");
    }

    @Test
    void screenCardsExportIndividuallyUnlessTheToggleIsTrue() {
        for (final Boolean optIn : new Boolean[] {null, false}) {
            final List<Map<String, Object>> entries = exportCards(optIn, false, false);
            assertEquals(4, entries.size());
            assertEquals(List.of("card", "card", "card", "card"),
                    entries.stream().map(CardsAsListTest::component).collect(java.util.stream.Collectors.toList()));
        }
    }

    @Test
    void productCardsExportIndividuallyUnlessTheToggleIsTrue() {
        for (final Boolean optIn : new Boolean[] {null, false}) {
            final List<Map<String, Object>> entries = exportCards(optIn, true, false);
            assertEquals(4, entries.size());
            assertEquals(List.of("card", "card", "card", "card"),
                    entries.stream().map(CardsAsListTest::component).collect(java.util.stream.Collectors.toList()));
        }
    }

    @Test
    void linklessCardsFallBackToIndividualExport() {
        for (final Boolean optIn : new Boolean[] {null, false}) {
            assertEquals(4, exportCards(optIn, false, true).size());
        }
        assertEquals(4, exportCards(true, false, true).size());
    }

    @Test
    void theToggleEnablesScreenListExport() {
        final List<Map<String, Object>> entries = exportCards(true, false, false);
        assertEquals(1, entries.size());
        assertEquals("list", component(entries.get(0)));
        assertEquals("screenList", content(entries.get(0)).get("listType"));
    }

    @Test
    void theToggleEnablesProductListExport() {
        final List<Map<String, Object>> entries = exportCards(true, true, false);
        assertEquals(1, entries.size());
        assertEquals("list", component(entries.get(0)));
        assertEquals("productList", content(entries.get(0)).get("listType"));
    }

    @Test
    void screenThumbnailsComeFromTheCardsImages() {
        final Resource block = context.create().resource(
                "/content/s/en/p/jcr:content/root/cards", "filter", "cards");
        card(block.getPath() + "/a", "mexico");
        card(block.getPath() + "/b", "poland");
        card(block.getPath() + "/c", "united-kingdom");
        final Map<String, String> thumbnails = new java.util.LinkedHashMap<>();

        assertNotNull(walker.cardsBlockAsList(block, 0,
                screens("mexico", "poland", "united-kingdom"), thumbnails));

        assertEquals(3, thumbnails.size());
        final UrlServiceImpl urls = new UrlServiceImpl();
        assertEquals(urls.externalize("/content/dam/s/mexico.jpg"), thumbnails.get("mexico"));
        assertEquals(urls.externalize("/content/dam/s/poland.jpg"), thumbnails.get("poland"));
        assertEquals(urls.externalize("/content/dam/s/united-kingdom.jpg"), thumbnails.get("united-kingdom"));
    }

    @SuppressWarnings("unchecked")
    private static List<String> items(final Map<String, Object> list) {
        return (List<String>) ((Map<String, Object>) list.get("content")).get("items");
    }

    private Resource card(final String path, final String leaf) {
        return context.create().resource(path, "model", "card",
                "sling:resourceType", SchemaConstants.RT_FRANKLIN_BLOCK_ITEM,
                "image", "/content/dam/s/" + leaf + ".jpg",
                "text", "<h2>" + leaf + "</h2>",
                "link", "/content/s/en/" + leaf);
    }

    private Resource placeholder(final String path) {
        return context.create().resource(path, "model", "card", "selectable", "false", "imagestyle", "");
    }

    /** One authored card plus two placeholders - the wknd-fly destinations block. */
    private Resource indiaBlock(final boolean optIn) {
        final Resource block = optIn
                ? context.create().resource("/content/s/en/d/jcr:content/root/india",
                        "filter", "cards", "mobilerenderaslist", true)
                : context.create().resource("/content/s/en/d/jcr:content/root/india", "filter", "cards");
        card(block.getPath() + "/item", "india");
        placeholder(block.getPath() + "/item_810748578");
        placeholder(block.getPath() + "/item_1991735907");
        return block;
    }

    @Test
    void withThePropertyItBecomesAListOfTheCardsThatResolve() {
        final Map<String, Object> list = convert(indiaBlock(true), screens("india"));
        assertNotNull(list, "the opted-in block must convert");
        assertEquals(List.of("india"), items(list));
    }

    @Test
    void anOptedInListCarriesTheSharedListDefaults() {
        final Map<String, Object> content = content(convert(indiaBlock(true), screens("india")));
        assertEquals("horizontal", content.get("layout"));
        assertEquals("none", content.get("sort"));
        assertEquals("Category", content.get("filterProperty"));
        assertEquals(false, content.get("filterOnlyProductScreens"));
        assertEquals("regular", content.get("titleFontWeight"));
        assertEquals("light", content.get("subtitleFontWeight"));
        assertEquals(120, content.get("imageWidth"));
        assertEquals(1, content.get("itemsInRow"));
    }

    @Test
    void screenListBuilderConvertsThreeScreenLinkedCards() {
        final Resource block = context.create().resource(
                "/content/s/en/p/jcr:content/root/trio", "filter", "cards");
        card(block.getPath() + "/a", "mexico");
        card(block.getPath() + "/b", "poland");
        card(block.getPath() + "/c", "united-kingdom");

        final Map<String, Object> list = convert(block, screens("mexico", "poland", "united-kingdom"));
        assertNotNull(list);
        assertEquals(List.of("mexico", "poland", "united-kingdom"), items(list));
    }

    @Test
    void screenListBuilderConvertsFourScreenLinkedCards() {
        final Resource block = context.create().resource(
                "/content/s/en/p/jcr:content/root/four", "filter", "cards");
        for (final String leaf : new String[] {"a", "b", "c", "d"}) {
            card(block.getPath() + "/" + leaf, leaf);
        }
        assertEquals(List.of("a", "b", "c", "d"), items(convert(block, screens("a", "b", "c", "d"))));
    }

    @Test
    void withThePropertyTheCountNoLongerMatters() {
        final Resource block = context.create().resource(
                "/content/s/en/p/jcr:content/root/six", "filter", "cards", "mobilerenderaslist", true);
        for (final String leaf : new String[] {"a", "b", "c", "d", "e", "f"}) {
            card(block.getPath() + "/" + leaf, leaf);
        }
        final Map<String, Object> list = convert(block, screens("a", "b", "c", "d", "e", "f"));
        assertNotNull(list);
        assertEquals(6, items(list).size());
    }

    /** Nothing to list, so the block is left to the normal walk exactly as it is today. */
    @Test
    void withThePropertyButNoResolvingCardThereIsStillNoList() {
        final Resource block = context.create().resource(
                "/content/s/en/p/jcr:content/root/none", "filter", "cards", "mobilerenderaslist", true);
        card(block.getPath() + "/a", "somewhere-else");
        placeholder(block.getPath() + "/b");
        assertNull(convert(block, screens("india")));
    }

    private Resource cardWithResize(final String path, final String leaf, final String resize) {
        return context.create().resource(path, "model", "card",
                "image", "/content/dam/s/" + leaf + ".jpg",
                "text", "<h2>" + leaf + "</h2>",
                "link", "/content/s/en/" + leaf,
                "mobileimagestyle", resize);
    }

    @SuppressWarnings("unchecked")
    private static Object listImageResize(final Map<String, Object> list) {
        return ((Map<String, Object>) list.get("content")).get("imageResize");
    }

    @Test
    void theListsImageResizeIgnoresTheCardsAndDefaultsToCover() {
        final Resource block = context.create().resource(
                "/content/s/en/p/jcr:content/root/contain", "filter", "cards");
        cardWithResize(block.getPath() + "/a", "mexico", "contain");
        cardWithResize(block.getPath() + "/b", "poland", "contain");
        cardWithResize(block.getPath() + "/c", "united-kingdom", "contain");

        assertEquals("cover",
                listImageResize(convert(block, screens("mexico", "poland", "united-kingdom"))),
                "the list's imageResize is block-level/static only - the cards no longer drive it");
    }

    @Test
    void aProductListsImageResizeIgnoresTheCardsAndDefaultsToCover() {
        final Resource block = context.create().resource(
                "/content/s/en/p/jcr:content/root/prodresize", "filter", "cards");
        for (final String sku : new String[] {"WJ08", "MS01", "LT02"}) {
            context.create().resource(block.getPath() + "/" + sku, "model", "card",
                    "image", "/content/dam/s/" + sku + ".jpg",
                    "text", "<h2>" + sku + "</h2>",
                    "link", "/content/s/en/product?productId=" + sku,
                    "mobileimagestyle", "contain");
        }
        assertEquals("cover", listImageResize(walker.cardsBlockAsProductList(block, 0)));
    }

    /**
    * The cards model authors the app's own list options as mobileItemRenderer / mobileItemsInRow /
    * mobileImageResize; they win over anything the cards imply and legacy lowercase properties.
     */
    @Test
    void theBlocksOwnListOptionsWinOverWhatTheCardsImply() {
        final Resource block = context.create().resource(
                "/content/s/en/p/jcr:content/root/opts", "filter", "cards",
            "mobileItemRenderer", "image", "mobileItemsInRow", 4L, "mobileImageResize", "contain",
            "mobileitemrenderer", "stacked", "mobileitemsinrow", 1L, "mobileimageresize", "cover");
        cardWithResize(block.getPath() + "/a", "mexico", "cover");
        cardWithResize(block.getPath() + "/b", "poland", "cover");
        cardWithResize(block.getPath() + "/c", "united-kingdom", "cover");

        final Map<String, Object> content =
                content(convert(block, screens("mexico", "poland", "united-kingdom")));
        assertEquals("image", content.get("itemRenderer"));
        assertEquals(4L, content.get("itemsInRow"));
        assertEquals("contain", content.get("imageResize"));
    }

    @Test
    void theBlocksItemsInRowBindsAndTheOldSpellingIsIgnored() {
        final Resource block = context.create().resource(
                "/content/s/en/p/jcr:content/root/both", "filter", "cards",
                "mobileitemsinrow", 2L, "mobilelistitemsinrow", 4L);
        card(block.getPath() + "/a", "mexico");
        card(block.getPath() + "/b", "poland");
        card(block.getPath() + "/c", "united-kingdom");

        assertEquals(2L, content(convert(block, screens("mexico", "poland", "united-kingdom")))
                .get("itemsInRow"), "mobileitemsinrow is read; the older mobilelistitemsinrow is not");
    }

    @Test
    void aProductListReadsTheBlocksOwnListOptionsToo() {
        final Resource block = context.create().resource(
                "/content/s/en/p/jcr:content/root/prodopts", "filter", "cards",
            "mobileItemRenderer", "overlay", "mobileItemsInRow", 2L, "mobileImageResize", "contain",
            "mobileitemrenderer", "stacked", "mobileitemsinrow", 1L, "mobileimageresize", "cover");
        for (final String sku : new String[] {"WJ08", "MS01", "LT02"}) {
            context.create().resource(block.getPath() + "/" + sku, "model", "card",
                    "image", "/content/dam/s/" + sku + ".jpg",
                    "text", "<h2>" + sku + "</h2>",
                    "link", "/content/s/en/product?productId=" + sku,
                    "mobileimagestyle", "contain");
        }
        final Map<String, Object> content = content(walker.cardsBlockAsProductList(block, 0));
        assertEquals("overlay", content.get("itemRenderer"));
        assertEquals(2L, content.get("itemsInRow"));
        assertEquals("contain", content.get("imageResize"));
    }

    @Test
    void anAuthoredLimitCapsAScreenList() {
        final Resource block = context.create().resource(
                "/content/s/en/p/jcr:content/root/limited", "filter", "cards", "limit", 5L);
        card(block.getPath() + "/a", "mexico");
        card(block.getPath() + "/b", "poland");
        card(block.getPath() + "/c", "united-kingdom");

        assertEquals(5L, content(convert(block, screens("mexico", "poland", "united-kingdom")))
                .get("limit"));
    }

    @Test
    void anUnauthoredOrZeroLimitIsOmitted() {
        final Resource block = context.create().resource(
                "/content/s/en/p/jcr:content/root/nolimit", "filter", "cards", "limit", 0L);
        card(block.getPath() + "/a", "mexico");
        card(block.getPath() + "/b", "poland");
        card(block.getPath() + "/c", "united-kingdom");

        assertFalse(content(convert(block, screens("mexico", "poland", "united-kingdom")))
                .containsKey("limit"), "0 means show all, so no limit is emitted");
    }

    @Test
    void anAuthoredLimitCapsAProductList() {
        final Resource block = context.create().resource(
                "/content/s/en/p/jcr:content/root/prodlimit", "filter", "cards", "limit", "2");
        for (final String sku : new String[] {"WJ08", "MS01", "LT02"}) {
            context.create().resource(block.getPath() + "/" + sku, "model", "card",
                    "image", "/content/dam/s/" + sku + ".jpg",
                    "text", "<h2>" + sku + "</h2>",
                    "link", "/content/s/en/product?productId=" + sku);
        }
        assertEquals(2L, content(walker.cardsBlockAsProductList(block, 0)).get("limit"));
    }

    /** The client's Card draws nothing for a renderer it does not know, which blanks the whole list. */
    @Test
    void anUnknownItemRendererNeverReachesTheClient() {        final Resource block = context.create().resource(
                "/content/s/en/p/jcr:content/root/badrenderer", "filter", "cards", "mobileitemrenderer", "below");
        card(block.getPath() + "/a", "mexico");
        card(block.getPath() + "/b", "poland");
        card(block.getPath() + "/c", "united-kingdom");

        assertEquals("overlay",
                content(convert(block, screens("mexico", "poland", "united-kingdom"))).get("itemRenderer"));
    }

    @Test
    void aKnownItemRendererPassesThrough() {
        final Resource block = context.create().resource(
                "/content/s/en/p/jcr:content/root/goodrenderer", "filter", "cards",
                "mobileitemrenderer", "image-text-one-line");
        card(block.getPath() + "/a", "mexico");
        card(block.getPath() + "/b", "poland");
        card(block.getPath() + "/c", "united-kingdom");

        assertEquals("image-text-one-line",
                content(convert(block, screens("mexico", "poland", "united-kingdom"))).get("itemRenderer"));
    }

    @Test
    void aProductListFallsBackToStackedForAnUnknownRenderer() {
        final Resource block = context.create().resource(
                "/content/s/en/p/jcr:content/root/prodbad", "filter", "cards", "mobileitemrenderer", "bogus");
        for (final String sku : new String[] {"WJ08", "MS01", "LT02"}) {
            context.create().resource(block.getPath() + "/" + sku, "model", "card",
                    "image", "/content/dam/s/" + sku + ".jpg",
                    "text", "<h2>" + sku + "</h2>",
                    "link", "/content/s/en/product?productId=" + sku);
        }
        assertEquals("stacked", content(walker.cardsBlockAsProductList(block, 0)).get("itemRenderer"));
    }

    /** Frescopa's "Shop all products": three cards open the same "coffee" screen. */
    @Test
    void cardsOpeningTheSameScreenAreListedOnce() {
        final Resource block = context.create().resource(
                "/content/s/en/p/jcr:content/root/dupes", "filter", "cards", "mobilerenderaslist", true);
        card(block.getPath() + "/a", "machines");
        card(block.getPath() + "/b", "coffee");
        card(block.getPath() + "/c", "coffee");
        card(block.getPath() + "/d", "coffee");
        card(block.getPath() + "/e", "accessories");

        final Map<String, Object> list = convert(block, screens("machines", "coffee", "accessories"));
        assertNotNull(list);
        assertEquals(List.of("machines", "coffee", "accessories"), items(list));
    }

    // ---------- productList: a Cards block of product-linking cards ----------

    private Resource productCard(final String path, final String sku) {
        return context.create().resource(path, "model", "card",
                "sling:resourceType", SchemaConstants.RT_FRANKLIN_BLOCK_ITEM,
                "image", "/content/dam/s/" + sku + ".jpg",
                "text", "<h2>" + sku + "</h2>",
                "link", "/content/s/en/product?productId=" + sku);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> content(final Map<String, Object> list) {
        return (Map<String, Object>) list.get("content");
    }

    @Test
    void threeProductLinkingCardsBecomeAProductList() {
        final Resource block = context.create().resource(
                "/content/s/en/p/jcr:content/root/prods", "filter", "cards");
        productCard(block.getPath() + "/a", "WJ08");
        productCard(block.getPath() + "/b", "MS01");
        productCard(block.getPath() + "/c", "LT02");

        final Map<String, Object> list = walker.cardsBlockAsProductList(block, 0);
        assertNotNull(list, "three product-linking cards must convert");
        assertEquals(List.of("WJ08", "MS01", "LT02"), content(list).get("items"));
        assertEquals("productList", content(list).get("listType"));
        assertNull(content(list).get("filter"), "no filter - the list is driven by the explicit SKUs");
        assertEquals(1, content(list).get("itemsInRow"), "defaults to a single column");
    }

    @Test
    void twoProductLinkingCardsCanBuildAProductList() {
        final Resource block = context.create().resource(
                "/content/s/en/p/jcr:content/root/pair", "filter", "cards");
        productCard(block.getPath() + "/a", "WJ08");
        productCard(block.getPath() + "/b", "MS01");
        assertEquals(List.of("WJ08", "MS01"), items(walker.cardsBlockAsProductList(block, 0)));
    }

    @Test
    void aCardWithoutAProductLinkLeavesTheBlockToTheNormalWalk() {
        final Resource block = context.create().resource(
                "/content/s/en/p/jcr:content/root/mixed", "filter", "cards");
        productCard(block.getPath() + "/a", "WJ08");
        productCard(block.getPath() + "/b", "MS01");
        card(block.getPath() + "/c", "some-screen");
        assertNull(walker.cardsBlockAsProductList(block, 0),
                "one card opens no product, so the whole block is not a product list");
    }

    @Test
    void onlyTheCardsOwnSkusAreListedRegardlessOfCategory() {
        final Resource block = context.create().resource(
                "/content/s/en/p/jcr:content/root/cat", "filter", "cards");
        productCard(block.getPath() + "/a", "MP01");
        productCard(block.getPath() + "/b", "WSH06");
        productCard(block.getPath() + "/c", "24-MG01");

        final Map<String, Object> list = walker.cardsBlockAsProductList(block, 0);
        assertNotNull(list);
        assertEquals(List.of("MP01", "WSH06", "24-MG01"), content(list).get("items"),
                "only the SKUs harvested from these cards are listed");
        assertNull(content(list).get("filter"), "no filter - the client must not broaden to the catalog");
    }

    // ---------- image width / item height from the Mobile Specific tab ----------

    private Resource trio(final String name, final Object... blockProps) {
        final Object[] props = new Object[blockProps.length + 2];
        props[0] = "filter";
        props[1] = "cards";
        System.arraycopy(blockProps, 0, props, 2, blockProps.length);
        final Resource block = context.create().resource("/content/s/en/p/jcr:content/root/" + name, props);
        card(block.getPath() + "/a", "mexico");
        card(block.getPath() + "/b", "poland");
        card(block.getPath() + "/c", "united-kingdom");
        return block;
    }

    private static final Set<String> TRIO_SCREENS = new HashSet<>(Arrays.asList("mexico", "poland", "united-kingdom"));

    @Test
    void aScreenListKeepsItsSizeDefaultsWhenNothingIsAuthored() {
        final Map<String, Object> list = convert(trio("plain"), TRIO_SCREENS);
        assertEquals(120, content(list).get("imageWidth"));
        assertNull(content(list).get("itemHeight"));
        assertEquals("cover", content(list).get("imageResize"), "the client's key is imageResize");
        assertNull(content(list).get("imageResizeMode"));
    }

    @Test
    void aScreenListReadsImageWidthAndItemHeightFromTheBlock() {
        final Map<String, Object> list = convert(
                trio("sized", "mobileimagewidth", 120L, "mobileitemheight", "90"), TRIO_SCREENS);
        assertEquals(120L, content(list).get("imageWidth"));
        assertEquals(90L, content(list).get("itemHeight"), "a text value is emitted as a number");
    }

    @Test
    void aNonNumericSizeFallsBackToTheDefault() {
        final Map<String, Object> list = convert(trio("junk", "mobileimagewidth", "wide"), TRIO_SCREENS);
        assertEquals(120, content(list).get("imageWidth"));
    }

    @Test
    void aProductListReadsImageWidthAndItemHeightFromTheBlock() {
        final Resource block = context.create().resource("/content/s/en/p/jcr:content/root/sizedprods",
                "filter", "cards", "mobileimagewidth", 110L, "mobileitemheight", 140L);
        productCard(block.getPath() + "/a", "WJ08");
        productCard(block.getPath() + "/b", "MS01");
        productCard(block.getPath() + "/c", "LT02");

        final Map<String, Object> list = walker.cardsBlockAsProductList(block, 0);
        assertEquals(110L, content(list).get("imageWidth"));
        assertEquals(140L, content(list).get("itemHeight"));
    }
}


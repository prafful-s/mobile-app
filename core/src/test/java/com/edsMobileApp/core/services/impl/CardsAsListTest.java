package com.edsMobileApp.core.services.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.sling.api.resource.Resource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.wcm.testing.mock.aem.junit5.AemContext;
import io.wcm.testing.mock.aem.junit5.AemContextExtension;

/**
 * A Cards block becomes a {@code screenList} only when it is exactly three cards that every one
 * resolve to an app screen. {@code mobilerenderaslist} on the block relaxes both halves of that, so a
 * block can be a list on whatever cards it has - wknd-fly's destinations block, one authored India
 * card beside two untouched placeholders, is the case it was added for.
 *
 * <p>The tests that matter most here are the ones asserting a block <em>without</em> the property
 * behaves exactly as it does today, since that is every block currently in the estate.</p>
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
        return walker.cardsBlockAsList(block, 0, knownScreens, null);
    }

    @SuppressWarnings("unchecked")
    private static List<String> items(final Map<String, Object> list) {
        return (List<String>) ((Map<String, Object>) list.get("content")).get("items");
    }

    private Resource card(final String path, final String leaf) {
        return context.create().resource(path, "model", "card",
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
    void withoutThePropertyTheIndiaBlockIsStillNotAList() {
        assertNull(convert(indiaBlock(false), screens("india")),
                "three children, two of which do not resolve - today's behaviour is no list");
    }

    @Test
    void withThePropertyItBecomesAListOfTheCardsThatResolve() {
        final Map<String, Object> list = convert(indiaBlock(true), screens("india"));
        assertNotNull(list, "the opted-in block must convert");
        assertEquals(List.of("india"), items(list));
    }

    @Test
    void aGenuineThreeOfThreeIsStillAListWithoutTheProperty() {
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
    void withoutThePropertyAFourCardBlockIsNeverAList() {
        final Resource block = context.create().resource(
                "/content/s/en/p/jcr:content/root/four", "filter", "cards");
        for (final String leaf : new String[] {"a", "b", "c", "d"}) {
            card(block.getPath() + "/" + leaf, leaf);
        }
        assertNull(convert(block, screens("a", "b", "c", "d")), "exactly three, or no list");
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

    @Test
    void thePropertySetToFalseIsTheSameAsAbsent() {
        final Resource block = context.create().resource(
                "/content/s/en/d/jcr:content/root/off", "filter", "cards", "mobilerenderaslist", false);
        card(block.getPath() + "/item", "india");
        placeholder(block.getPath() + "/item_2");
        placeholder(block.getPath() + "/item_3");
        assertNull(convert(block, screens("india")));
    }

    // ---------- productList: a Cards block of product-linking cards ----------

    private Resource productCard(final String path, final String sku) {
        return context.create().resource(path, "model", "card",
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
        assertEquals(".*", content(list).get("filter"), "open filter - every SKU shows");
        assertEquals(3, content(list).get("itemsInRow"), "defaults to three across");
    }

    @Test
    void twoProductLinkingCardsAreNotAProductList() {
        final Resource block = context.create().resource(
                "/content/s/en/p/jcr:content/root/pair", "filter", "cards");
        productCard(block.getPath() + "/a", "WJ08");
        productCard(block.getPath() + "/b", "MS01");
        assertNull(walker.cardsBlockAsProductList(block, 0), "two is not more than two");
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
    void everySkuShowsUnderAnOpenFilterRegardlessOfCategory() {
        final Resource block = context.create().resource(
                "/content/s/en/p/jcr:content/root/cat", "filter", "cards");
        productCard(block.getPath() + "/a", "MP01");
        productCard(block.getPath() + "/b", "WSH06");
        productCard(block.getPath() + "/c", "24-MG01");

        final Map<String, Object> list = walker.cardsBlockAsProductList(block, 0);
        assertNotNull(list);
        assertEquals(List.of("MP01", "WSH06", "24-MG01"), content(list).get("items"),
                "SKUs from any category are all kept");
        assertEquals(".*", content(list).get("filter"), "open filter never drops a category");
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
        assertEquals(130, content(list).get("imageWidth"));
        assertNull(content(list).get("itemHeight"));
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
        assertEquals(130, content(list).get("imageWidth"));
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


package com.edsMobileApp.core.services.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.apache.sling.api.resource.Resource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import com.edsMobileApp.core.schema.SchemaConstants;

import io.wcm.testing.mock.aem.junit5.AemContext;
import io.wcm.testing.mock.aem.junit5.AemContextExtension;

/**
 * A screenList's presentation is resolved block property, then site {@code screenList} config, then
 * the compiled-in default. Every caller passes what the code emitted before as its fallback, so the
 * tests that matter most here are the ones asserting an unauthored block is byte-for-byte unchanged
 * - that is the whole safety claim, since no block in the estate authors any of these yet.
 */
@ExtendWith(AemContextExtension.class)
class ListStylingTest {

    private final AemContext context = new AemContext();

    private ComponentTreeWalker walker;

    @BeforeEach
    void setUp() {
        walker = new ComponentTreeWalker(null, new UrlServiceImpl(), null, null, ComponentMappings.load());
    }

    private static Set<String> screens(final String... keys) {
        return new HashSet<>(Arrays.asList(keys));
    }

    private void card(final String path, final String leaf, final String style) {
        context.create().resource(path, "model", "card",
                "image", "/content/dam/s/" + leaf + ".jpg",
                "text", "<h2>" + leaf + "</h2>",
                "link", "/content/s/en/" + leaf,
                "style", style);
    }

    /** Three cards that all resolve - the shape that converts without needing mobilerenderaslist. */
    private Resource trio(final String name, final Object... blockProps) {
        final Object[] props = new Object[blockProps.length + 2];
        props[0] = "filter";
        props[1] = "cards";
        System.arraycopy(blockProps, 0, props, 2, blockProps.length);
        final Resource block = context.create().resource("/content/s/en/p/jcr:content/root/" + name, props);
        card(block.getPath() + "/a", "mexico", "image-top");
        card(block.getPath() + "/b", "poland", "image-top");
        card(block.getPath() + "/c", "united-kingdom", "image-top");
        return block;
    }

    @Test
    void authoredHideSubtitleOverridesDefault() {
        assertEquals(true, content(convert(trio("hide-subtitle", "mobilehidesubtitle", "true"))).get("hideSubtitle"));
        assertEquals(false, content(convert(trio("show-subtitle"))).get("hideSubtitle"));
    }

    private Map<String, Object> convert(final Resource block) {
        final Map<String, Object> list =
                walker.cardsBlockAsList(block, 0, screens("mexico", "poland", "united-kingdom"));
        assertNotNull(list, "fixture must convert to a list");
        return list;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> content(final Map<String, Object> list) {
        return (Map<String, Object>) list.get(SchemaConstants.KEY_CONTENT);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> styles(final Map<String, Object> list) {
        return (Map<String, Object>) list.get(SchemaConstants.KEY_STYLES);
    }

    // ---------- unauthored: every value must be what the code emitted before ----------

    @Test
    void anUnauthoredBlockEmitsExactlyTodaysValues() {
        final Map<String, Object> list = convert(trio("plain"));
        assertEquals(1, content(list).get("itemsInRow"), "defaults to a single column");
        assertEquals("overlay", content(list).get("itemRenderer"), "defaults to the compiled-in overlay");
        assertNull(content(list).get("textColor"), "no colour is emitted unless authored");
        assertNull(styles(list).get("color"), "no colour is emitted unless authored");
        assertEquals("horizontal", content(list).get("layout"));
        assertEquals("none", content(list).get("sort"));
    }

    // ---------- itemsInRow ----------

    @Test
    void theBlockCanPinItemsInRow() {
        assertEquals(3L, content(convert(trio("pinned", "mobileitemsinrow", 3L))).get("itemsInRow"));
    }

    @Test
    void itemsInRowIsEmittedAsANumberNotAString() {
        final Object value = content(convert(trio("num", "mobileitemsinrow", 3L))).get("itemsInRow");
        assertEquals(Long.class, value.getClass(), "JCR gives a Long; it must stay a number in the JSON");
    }

    /**
     * mobileitemsinrow is the Cards model's one Items in row field (blocks/cards/_cards.json, stored
     * lower-cased); the older mobilelistitemsinrow spelling is not read - one field maps one value.
     */
    @Test
    void theItemsInRowFieldBindsAndOlderSpellingsAreIgnored() {
        assertEquals(5L, content(convert(
                trio("oldnames", "mobileitemsinrow", 5L, "mobilelistitemsinrow", 4L))).get("itemsInRow"),
                "mobileitemsinrow binds; mobilelistitemsinrow is ignored");
    }

    // ---------- layout ----------

    @Test
    void theBlockCanSetLayout() {
        assertEquals("vertical/grid",
                content(convert(trio("layout", "mobilelayout", "vertical/grid"))).get("layout"));
    }

    // ---------- sort ----------

    @Test
    void theBlockCanSetSort() {
        assertEquals("alphabetical",
                content(convert(trio("sort", "mobilesort", "alphabetical"))).get("sort"));
    }

    // ---------- custommobilestyle (block-level) ----------

    @Test
    void theBlocksOwnCustomMobileStyleOverridesTheComputedStyles() {
        final Map<String, Object> list = convert(trio("customstyle",
                "custommobilestyle", new String[] {"marginBottom:24", "gap:20"}));
        assertEquals(24L, styles(list).get("marginBottom"));
        assertEquals(20L, styles(list).get("gap"));
        assertEquals("8", styles(list).get("marginHorizontal"), "an untouched style stays exactly as computed");
    }

    // ---------- itemRenderer ----------

    @Test
    void theBlockCanOverrideItemRenderer() {
        assertEquals("stacked",
                content(convert(trio("renderer", "mobileitemrenderer", "stacked"))).get("itemRenderer"));
    }

    @Test
    void aBlankRendererFallsBackToTheDerivedValue() {
        assertEquals("overlay",
                content(convert(trio("blankrenderer", "mobileitemrenderer", ""))).get("itemRenderer"));
    }

    // ---------- colours ----------

    @Test
    void theBlockCanSetBothColours() {
        final Map<String, Object> list = convert(trio("colours",
                "mobilelisttextcolor", "#1a1a1a", "mobilelistcolor", "#000000"));
        assertEquals("#1a1a1a", content(list).get("textColor"));
        assertEquals("#000000", styles(list).get("color"));
    }

    @Test
    void shorthandAndBareHexAreBothAccepted() {
        assertEquals("#fff", content(convert(trio("short", "mobilelisttextcolor", "#fff"))).get("textColor"));
        assertEquals("333", content(convert(trio("bare", "mobilelisttextcolor", "333"))).get("textColor"));
    }

    /**
     * The secur-financial typo: five f's. Non-blank, so without the guard it would win and the client
     * would render an unparseable colour; instead the colour is omitted entirely.
     */
    @Test
    void aMalformedHexIsIgnoredRatherThanEmitted() {
        assertNull(content(convert(trio("typo", "mobilelisttextcolor", "#fffff"))).get("textColor"));
        assertNull(styles(convert(trio("typo2", "mobilelistcolor", "not-a-colour"))).get("color"));
    }

    // ---------- the shared validator ----------

    @Test
    void hexColorAcceptsWhatTheAuthoringFieldsDescribe() {
        assertEquals("#fff", FieldRule.asHexColor("#fff"));
        assertEquals("#000000", FieldRule.asHexColor("#000000"));
        assertEquals("f5f5f5", FieldRule.asHexColor("f5f5f5"));
        assertEquals("333", FieldRule.asHexColor("333"));
        assertEquals("#1A1A1A", FieldRule.asHexColor("#1A1A1A"));
    }

    @Test
    void hexColorRejectsEverythingElse() {
        assertNull(FieldRule.asHexColor("#fffff"), "five digits is neither shorthand nor full");
        assertNull(FieldRule.asHexColor("#gggggg"));
        assertNull(FieldRule.asHexColor("red"));
        assertNull(FieldRule.asHexColor(""));
        assertNull(FieldRule.asHexColor(null));
    }
}

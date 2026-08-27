package com.dept.contentbridge.core.services.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class FieldRuleTest {

    @Test
    @DisplayName("fromParent reads the section's map, not the component's own")
    void fromParentReadsParentSource() {
        final FieldRule rule = FieldRule.of(Map.of(
                "target", "textAlignment", "fromParent", "sec-alignment", "nullable", true));

        final Object fromOwnSource = rule.fromSource(
                Map.of("sec-alignment", "left"), Map.of("sec-alignment", "center"));
        assertEquals("center", fromOwnSource, "the parent map wins even if the own map has the key");

        assertEquals(null, rule.fromSource(Map.of(), Map.of()), "unset in the parent stays null");
    }

    @Test
    @DisplayName("categoryPath: multi-tag list, prefix stripped, hyphen->space, title-cased, unique, joined with ' / '")
    void categoryPath() {
        final FieldRule rule = FieldRule.of(Map.of(
                "target", "filter", "from", "cats", "type", "categoryPath"));
        // Value is a list; some items are themselves comma-joined multi-value tag strings.
        final Object out = rule.fromSource(
                Map.of("cats", List.of(
                        "lumaproducts:women",
                        "lumaproducts:women/bottoms",
                        "lumaproducts:new-luma-yoga-collection")),
                Map.of());
        assertEquals("Women / Bottoms / New Luma Yoga Collection", out);
    }

    @Test
    @DisplayName("categoryRoot: majority vote across items, ignore prefix-only tags, tie-break by first appearance")
    void categoryRoot() {
        final FieldRule rule = FieldRule.of(Map.of(
                "target", "filter", "from", "cats", "type", "categoryRoot"));
        // Five items, all Men-rooted -> "Men".
        assertEquals("Men", rule.fromSource(
                Map.of("cats", List.of(
                        "lumaproducts:men/tops",
                        "lumaproducts:men/tops",
                        "lumaproducts:men/bottoms",
                        "lumaproducts:men/bottoms",
                        "lumaproducts:men/bottoms")),
                Map.of()));
        // Mixed with a stray Women item, Men wins by majority.
        assertEquals("Men", rule.fromSource(
                Map.of("cats", List.of(
                        "lumaproducts:men/tops",
                        "lumaproducts:women/tops",
                        "lumaproducts:men/bottoms",
                        "lumaproducts:men/bottoms")),
                Map.of()));
        // Prefix-only garbage tag contributes no vote so the real category still wins.
        assertEquals("Men", rule.fromSource(
                Map.of("cats", List.of(
                        "luma-products:,luma-products:sample-tag-1,lumaproducts:men/tops",
                        "lumaproducts:men/bottoms")),
                Map.of()));
        // Hyphenated single-segment tag (no '/') passes through as title-cased words.
        assertEquals("New Luma Yoga Collection", rule.fromSource(
                Map.of("cats", List.of("lumaproducts:new-luma-yoga-collection")), Map.of()));
        // Empty input -> null (blank-to-null), caller falls back to default.
        assertEquals(null, rule.fromSource(Map.of(), Map.of()));
    }

    @Test
    @DisplayName("categoryRootRegex: majority-voted root with a trailing .*")
    void categoryRootRegex() {
        final FieldRule rule = FieldRule.of(Map.of(
                "target", "filter", "from", "cats", "type", "categoryRootRegex"));
        assertEquals("Women.*", rule.fromSource(
                Map.of("cats", List.of(
                        "lumaproducts:women/tops",
                        "lumaproducts:women/bottoms",
                        "lumaproducts:women/jackets")),
                Map.of()));
        // Empty input keeps the empty string so the default (not ".*") wins.
        assertEquals(null, rule.fromSource(Map.of(), Map.of()));
    }

    @Test
    @DisplayName("nullable defaults to false, opts in when set")
    void nullable() {
        final FieldRule off = FieldRule.of(Map.of("target", "subtitle", "from", "subtitle"));
        assertFalse(off.isNullable());

        final FieldRule on = FieldRule.of(Map.of(
                "target", "subtitle", "from", "subtitle", "nullable", true));
        assertTrue(on.isNullable());
    }
}

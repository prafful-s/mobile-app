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
    @DisplayName("consumedProperties: 'from' by itself")
    void consumedFromOnly() {
        final FieldRule rule = FieldRule.of(Map.of("target", "title", "from", "jcr:title"));
        assertEquals(List.of("jcr:title"), rule.consumedProperties());
    }

    @Test
    @DisplayName("consumedProperties: 'first' array")
    void consumedFirst() {
        final FieldRule rule = FieldRule.of(Map.of(
                "target", "image",
                "first", List.of("image", "fileReference", "imageRef")));
        assertEquals(List.of("image", "fileReference", "imageRef"),
                rule.consumedProperties());
    }

    @Test
    @DisplayName("consumedProperties: 'extract' names the source rich-text field")
    void consumedExtract() {
        final FieldRule rule = FieldRule.of(Map.of(
                "target", "title",
                "extract", Map.of("from", "text", "select", "h1")));
        assertEquals(List.of("text"), rule.consumedProperties());
    }

    @Test
    @DisplayName("consumedProperties: 'const' consumes nothing")
    void consumedConst() {
        final FieldRule rule = FieldRule.of(Map.of("target", "titleVariant", "const", "titleLarge"));
        assertTrue(rule.consumedProperties().isEmpty(),
                "a constant-only rule reads no CRX property");
    }

    @Test
    @DisplayName("fromParent reads the section's map, not the component's own, and consumes nothing")
    void fromParentReadsParentSource() {
        final FieldRule rule = FieldRule.of(Map.of(
                "target", "textAlignment", "fromParent", "sec-alignment", "nullable", true));

        assertTrue(rule.consumedProperties().isEmpty(),
                "fromParent reads the section, not the component's own authored properties");

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
    @DisplayName("categoryRoot: top-level segment of first tag, title-cased, single string")
    void categoryRoot() {
        final FieldRule rule = FieldRule.of(Map.of(
                "target", "filter", "from", "cats", "type", "categoryRoot"));
        // First fragment's tag list ordered as author placed them; take root of first tag.
        assertEquals("Men", rule.fromSource(
                Map.of("cats", List.of("lumaproducts:men/tops", "lumaproducts:men/bottoms")),
                Map.of()));
        // Root-only tag (no '/') passes through with hyphens turned to spaces + title case.
        assertEquals("New Luma Yoga Collection", rule.fromSource(
                Map.of("cats", List.of("lumaproducts:new-luma-yoga-collection")), Map.of()));
        // Empty list resolves to null via blank-to-null; the caller falls back to default.
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

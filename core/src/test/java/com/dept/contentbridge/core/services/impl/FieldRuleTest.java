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
    @DisplayName("nullable defaults to false, opts in when set")
    void nullable() {
        final FieldRule off = FieldRule.of(Map.of("target", "subtitle", "from", "subtitle"));
        assertFalse(off.isNullable());

        final FieldRule on = FieldRule.of(Map.of(
                "target", "subtitle", "from", "subtitle", "nullable", true));
        assertTrue(on.isNullable());
    }
}

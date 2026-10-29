package com.edsMobileApp.core.services.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/** A mapping may name the property holding its {@code key:value} style rows; custommobilestyle is the default. */
class CustomStylePropertyTest {

    private static ComponentMapping mapping(final Object... extra) {
        final java.util.Map<String, Object> definition = new java.util.LinkedHashMap<>();
        definition.put("match", List.of("sample"));
        definition.put("component", "sample");
        definition.put("fields", List.of());
        for (int i = 0; i < extra.length; i += 2) {
            definition.put((String) extra[i], extra[i + 1]);
        }
        return ComponentMapping.of(definition);
    }

    @Test
    void aMappingWithoutCustomStyleUsesCustomMobileStyle() {
        assertEquals("custommobilestyle", mapping().customStyleProperty());
    }

    @Test
    void aMappingDeclaresItsOwnStyleProperty() {
        final ComponentMapping declared = mapping("customStyle", "mobileappsamplestyle");

        assertEquals("mobileappsamplestyle", declared.customStyleProperty());
        assertTrue(declared.consumedProperties().contains("mobileappsamplestyle"),
                "the style rows must not reappear as an extra");
    }

    @Test
    void rowsAreReadFromTheNamedProperty() {
        final Map<String, Object> authored = Map.of(
                "mobileappsamplestyle", new String[] {"gap:8", "alignItems:center"},
                "custommobilestyle", new String[] {"gap:99"});

        assertEquals(Map.of("gap", 8L, "alignItems", "center"),
                CustomMobileStyle.asMap(authored, "mobileappsamplestyle"));
        assertEquals(Map.of("gap", 99L), CustomMobileStyle.asMap(authored));
        assertFalse(CustomMobileStyle.asMap(Map.of(), "mobileappsamplestyle").containsKey("gap"));
    }
}

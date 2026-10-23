package com.edsMobileApp.core.services.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

class TextMappingTest {

    private final ComponentMapping rule = ComponentMappings.load().find("text");

    @Test
    void unavailableStylesAreOmittedWithoutChangingContentDefaults() {
        assertEquals(Map.of(), rule.applyStyles(Map.of(), Map.of()));
        assertEquals(Map.of("text", ""), rule.apply(Map.of(), Map.of()));
        for (final String value : new String[] {"", "   "}) {
            assertEquals(Map.of(), rule.applyStyles(Map.of("padding", value, "color", value), Map.of()));
        }
        final Map<String, Object> authored = new HashMap<>();
        authored.put("padding", null);
        authored.put("color", null);
        assertEquals(Map.of(), rule.applyStyles(authored, Map.of()));
    }

    @Test
    void availableStylesAreEmittedIndependently() {
        assertEquals(Map.of("padding", "16"), rule.applyStyles(Map.of("padding", "16"), Map.of()));
        assertEquals(Map.of("padding", 0), rule.applyStyles(Map.of("padding", 0), Map.of()));
        for (final String color : new String[] {"#abc", "#123456", "abc", "123456"}) {
            assertEquals(Map.of("color", color), rule.applyStyles(Map.of("color", color), Map.of()));
        }
        assertEquals(Map.of("padding", "16", "color", "#123456"),
                rule.applyStyles(Map.of("padding", "16", "color", "#123456"), Map.of()));
    }

    @Test
    void invalidColorIsOmittedWithoutDroppingAvailablePadding() {
        for (final String color : new String[] {"red", "#fffff"}) {
            assertEquals(Map.of(), rule.applyStyles(Map.of("color", color), Map.of()));
            assertEquals(Map.of("padding", "16"),
                    rule.applyStyles(Map.of("padding", "16", "color", color), Map.of()));
        }
    }
}

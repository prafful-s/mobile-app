package com.edsMobileApp.core.services.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * A title's size, weight and textAlignment each come from exactly one property - its Mobile
 * Specific field (mobilesize, mobileweight, mobiletextalignment) - or a default: medium, bold,
 * left. The web titleType, weight and textAlignment properties are not read.
 */
class TitleMappingTest {

    private ComponentMapping rule;

    @BeforeEach
    void setUp() {
        rule = ComponentMappings.load().find("title");
    }

    private Map<String, Object> map(final String... keyValue) {
        final Map<String, Object> authored = new HashMap<>();
        authored.put("title", "Choose your car");
        for (int i = 0; i < keyValue.length; i += 2) {
            authored.put(keyValue[i], keyValue[i + 1]);
        }
        return rule.effectiveFor(authored).apply(authored, Map.of());
    }

    @Test
    void unauthoredMobileFieldsGiveTheDefaults() {
        final Map<String, Object> mapped = map("titleType", "h2", "weight", "light", "textAlignment", "right");
        assertEquals("medium", mapped.get("size"), "titleType is not read");
        assertEquals("bold", mapped.get("weight"));
        assertEquals("left", mapped.get("textAlignment"));
    }

    @Test
    void mobileFieldsWinWhenAuthored() {
        final Map<String, Object> mapped = map("titleType", "h2",
                "mobilesize", "xsmall", "mobileweight", "light", "mobiletextalignment", "center");
        assertEquals("xsmall", mapped.get("size"));
        assertEquals("light", mapped.get("weight"));
        assertEquals("center", mapped.get("textAlignment"));
    }

    @Test
    void everyMobileSizePassesThrough() {
        for (final String size : new String[] {"xsmall", "small", "medium", "large", "xlarge"}) {
            assertEquals(size, map("mobilesize", size).get("size"));
        }
    }

    /** The empty Default option in the dropdown counts as not authored. */
    @Test
    void anEmptyMobileValueFallsBack() {
        final Map<String, Object> mapped = map("titleType", "h4", "mobilesize", "", "mobileweight", "");
        assertEquals("medium", mapped.get("size"));
        assertEquals("bold", mapped.get("weight"));
    }
}

package com.edsMobileApp.core.services.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * The map rule turns an authored {@code map} block into the app's map component:
 * {@code displayAs} in content and the pin/location colours and height in styles,
 * each falling back to the model's own defaults when the author left it blank.
 */
class MapMappingTest {

    private final ComponentMappings mappings = ComponentMappings.load();

    private Map<String, Object> content(final Map<String, Object> source) {
        return mappings.find("map").apply(source, Map.of());
    }

    private Map<String, Object> styles(final Map<String, Object> source) {
        return mappings.find("map").applyStyles(source, Map.of());
    }

    private static Map<String, Object> authored(final Object... keyValue) {
        final Map<String, Object> out = new HashMap<>();
        for (int i = 0; i < keyValue.length; i += 2) {
            out.put((String) keyValue[i], keyValue[i + 1]);
        }
        return out;
    }

    @Test
    void aModalMapExportsDisplayAsAndColouredStyles() {
        final Map<String, Object> source = authored(
                "mobileDisplayAs", "modal",
                "mobileColor", "blue",
                "mobileHeight", 500L,
                "mobileLocationColor", "green");

        assertEquals("modal", content(source).get("displayAs"));

        final Map<String, Object> styles = styles(source);
        assertEquals("blue", styles.get("color"));
        assertEquals(500L, styles.get("height"));
        assertEquals("green", styles.get("locationColor"));
    }

    @Test
    void anUnauthoredMapFallsBackToModelDefaults() {
        assertEquals("fullMap", content(authored()).get("displayAs"));

        final Map<String, Object> styles = styles(authored());
        assertEquals("blue", styles.get("color"));
        assertEquals(500, styles.get("height"));
        assertEquals("green", styles.get("locationColor"));
    }

    @Test
    void authoredCoordinatesExportAsMapRows() {
        final String[] labels = {"Apple Fifth Avenue", "Apple Grand Central", "Apple SoHo",
                "Apple Williamsburg (Brooklyn)", "Apple Columbus Circle"};
        final String[] latitudes = {"40.7640", "40.7532", "40.7240", "40.7104", "40.7681"};
        final String[] longitudes = {"73.9730", "73.9766", "74.0008", "73.9600", "73.9819"};
        final String[] lines = new String[labels.length];
        final List<Map<String, Object>> expected = new java.util.ArrayList<>();
        for (int index = 0; index < labels.length; index++) {
            final String latitude = latitudes[index] + "\u00b0\u202fN";
            final String longitude = "\u2013" + longitudes[index] + "\u00b0\u202fW";
            lines[index] = labels[index] + "," + latitude + "," + longitude;
            expected.add(Map.of("id", "row-" + index, "label", labels[index],
                    "latitude", latitude, "longitude", longitude));
        }

        assertEquals(expected, content(authored("mobileMapCoordinates", lines)).get("mapCoordinates"));
    }

    @Test
    void theMapBlocksOwnPropertiesDoNotLeak() {
        final Map<String, Object> mapped = content(authored(
                "mobileDisplayAs", "fullMap",
                "mobileText", "Warsaw Airport",
                "mobileMapCoordinates", new String[] {"Warsaw Airport,52.1657,20.9671"}));

        assertEquals("fullMap", mapped.get("displayAs"));
        assertFalse(mapped.containsKey("mobileText"), "the block's own text does not leak");
        assertFalse(mapped.containsKey("mobileMapCoordinates"), "raw coordinate lines do not leak");
        assertFalse(mapped.containsKey("text"), "no stale text field");
    }
}

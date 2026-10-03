package com.edsMobileApp.core.services.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * One field maps one value: button, flight-search, flights, hero and image each read a single
 * property per output value - the one their EDS model authors - and ignore older spellings.
 */
class SingleFieldMappingTest {

    private static Map<String, Object> map(final String rule, final String... keyValue) {
        final Map<String, Object> authored = new HashMap<>();
        for (int i = 0; i < keyValue.length; i += 2) {
            authored.put(keyValue[i], keyValue[i + 1]);
        }
        final ComponentMapping mapping = ComponentMappings.load().find(rule);
        return mapping.effectiveFor(authored).apply(authored, Map.of());
    }

    @Test
    void aButtonsLabelIsItsLinkText() {
        assertEquals("Book now", map("button", "linkText", "Book now", "label", "x", "text", "y").get("label"));
        assertEquals("", map("button", "label", "x", "text", "y", "link", "/home").get("label"),
                "label and text are not read");
    }

    @Test
    void flightSearchEventTypeIsTheMobileField() {
        assertEquals("mobile.search", map("flight-search", "mobilebuttoneventtype", "mobile.search",
                "buttoneventtype", "web.search").get("eventType"));
        assertEquals("", map("flight-search", "buttoneventtype", "web.search").get("eventType"),
                "the web event type is not read");
    }

    @Test
    void flightsEventTypeIsTheMobileField() {
        assertEquals("mobile.select", map("flights", "mobilebuttoneventtype", "mobile.select",
                "buttoneventtype", "web.select").get("eventType"));
        assertEquals("", map("flights", "buttoneventtype", "web.select").get("eventType"),
                "the web event type is not read");
    }

    /** flights reads nothing else off the block, so it is a skeleton rule: always a flightResults. */
    @Test
    void aFlightsBlockWithNoMobileEventStillBecomesFlightResults() {
        final Map<String, Object> mapped = map("flights", "flightlistcontentfragment", "/content/dam/f");
        assertEquals("", mapped.get("eventType"));
        assertEquals("checkout", mapped.get("link"));
        assertNull(mapped.get("flightlistcontentfragment"), "the block's own properties do not leak");
    }

        @Test
        void mobileEventTypesHaveNoFallbackAction() {
        for (String rule : new String[] {"button", "action-button", "flight-search", "flights"}) {
            assertEquals("mobile.action", map(rule, "mobilebuttoneventtype", "mobile.action",
                "buttoneventtype", "web.action").get("eventType"), rule);
            assertEquals("", map(rule).get("eventType"), rule + " with no authored event");
            assertEquals("", map(rule, "mobilebuttoneventtype", "",
                "buttoneventtype", "web.action").get("eventType"), rule + " with a blank mobile event");
            assertEquals("", map(rule, "mobilebuttoneventtype", "   ",
                "buttoneventtype", "web.action").get("eventType"), rule + " with a whitespace mobile event");
        }
        }

        @Test
        void aHerosImageIsItsImageField() {
        assertEquals("/content/dam/hero.jpg", map("hero", "image", "/content/dam/hero.jpg",
                "fileReference", "/content/dam/old.jpg", "text", "<h1>Hi</h1>").get("image"));
        assertNull(map("hero", "fileReference", "/content/dam/old.jpg", "imageRef", "/content/dam/older.jpg",
                "text", "<h1>Hi</h1>").get("image"), "fileReference and imageRef are not read");
    }

    @Test
    void anImagesImageIsItsImageField() {
        assertEquals("/content/dam/a.jpg", map("image", "image", "/content/dam/a.jpg",
                "src", "/content/dam/b.jpg").get("image"));
        assertNull(map("image", "fileReference", "/content/dam/old.jpg", "src", "/content/dam/b.jpg",
                "imageRef", "/content/dam/c.jpg", "link", "/home").get("image"),
                "fileReference, src and imageRef are not read");
    }
}

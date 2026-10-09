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

    /** The button's Mobile Specific tab overrides the web fields; with nothing authored the web ones still apply. */
    @Test
    void aButtonsMobileFieldsOverrideTheWebOnes() {
        final Map<String, Object> mobile = map("button", "linkType", "cta-button", "alignment", "left",
                "mobilebuttonvariant", "elevated", "mobilealignment", "stretch", "mobileicon", "credit-card",
                "mobilemessage", "Thank you");
        assertEquals("elevated", mobile.get("variant"));
        assertEquals("stretch", mobile.get("alignment"));
        assertEquals("credit-card", mobile.get("icon"));
        assertEquals("Thank you", mobile.get("message"));
        assertNull(mobile.get("mobilemessage"), "the mobile fields do not leak");
        assertNull(mobile.get("mobilebuttonvariant"), "the mobile fields do not leak");
        assertNull(mobile.get("mobilealignment"), "the mobile fields do not leak");
        assertNull(mobile.get("mobileicon"), "the mobile fields do not leak");

        final Map<String, Object> web = map("button", "linkType", "secondary-button", "alignment", "left");
        assertEquals("outlined", web.get("variant"));
        assertEquals("left", web.get("alignment"));
        assertEquals("", web.get("icon"));
        assertEquals("", web.get("message"));
    }

    /** An action-button's Mobile Specific tab carries the same icon and message the core button's does. */
    @Test
    void anActionButtonReadsItsMobileIconAndMessage() {
        final Map<String, Object> mapped = map("action-button", "label", "Go", "mobileicon", "credit-card",
                "mobilemessage", "Thank you", "mobilebuttonvariant", "elevated", "mobilealignment", "left");
        assertEquals("credit-card", mapped.get("icon"));
        assertEquals("Thank you", mapped.get("message"));
        assertEquals("elevated", mapped.get("variant"));
        assertEquals("left", mapped.get("alignment"));
        assertNull(mapped.get("mobileicon"), "the mobile fields do not leak");
        assertNull(mapped.get("mobilemessage"), "the mobile fields do not leak");

        final Map<String, Object> bare = map("action-button", "label", "Go");
        assertEquals("", bare.get("icon"));
        assertEquals("", bare.get("message"));
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

    @Test
    void flightSearchReadsItsMobileRedirectOrKeepsItsDefault() {
        assertEquals("results?source=mobile#flights", map("flight-search", "mobileRedirectUrl",
                "/content/site/en/results.html?source=mobile#flights").get("link"));
        assertEquals("flights", map("flight-search").get("link"));
        assertEquals("flights", map("flight-search", "mobileRedirectUrl", " ").get("link"));
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

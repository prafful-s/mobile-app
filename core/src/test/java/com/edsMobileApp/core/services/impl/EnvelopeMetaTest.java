package com.edsMobileApp.core.services.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.sling.api.wrappers.ValueMapDecorator;
import org.junit.jupiter.api.Test;

import com.day.cq.wcm.api.Page;

class EnvelopeMetaTest {

    @Test
    void mobileCommerceViewEventsMapToEventAndOmitOnlyCustomEventAndEventType() {
        final Map<String, Object> authored = new HashMap<>(Map.of(
                "event", "other.event",
                "customEvent", "authored.custom",
                "mobilepageeventtype", "authored.type",
                "mobileIcon", "tag",
                "mobileIconInactive", "tag-outline",
                "mobilePreset", "product",
                "adobeTargetMboxMobile", "product-mbox"));
        final Page page = page(authored);
        final Map<String, Object> expected = EnvelopeMeta.screenMeta(page, page, "product", List.of("product"), "");
        expected.remove("customEvent");
        expected.remove("eventType");

        for (String event : List.of("commerce.productViews", "commerce.categoryViews")) {
            authored.put("mobilepageeventtype", event);
            expected.put("event", event);
            final Map<String, Object> actual =
                    EnvelopeMeta.screenMeta(page, page, "product", List.of("product"), "");

            assertFalse(actual.containsKey("customEvent"), event);
            assertFalse(actual.containsKey("eventType"), event);
            assertEquals(expected, actual, event);

            authored.remove("event");
            assertEquals(expected, EnvelopeMeta.screenMeta(page, page, "product", List.of("product"), ""), event);
            authored.put("event", "other.event");
        }
    }

    @Test
    void otherPageEventsMapToCustomEvent() {
        final Map<String, Object> authored = new HashMap<>(Map.of(
                "customEvent", "authored.custom",
                "event", "authored.event"));
        final Page page = page(authored);

        for (String event : List.of("navigation.open", "commerce.purchases", "Commerce.productViews",
                "commerce.categoryViews.custom", " commerce.productViews ")) {
            authored.put("mobilepageeventtype", event);
            final Map<String, Object> meta = EnvelopeMeta.screenMeta(page, page, "product", List.of(), "");

            assertEquals("customEvent", meta.get("event"), event);
            assertEquals(event, meta.get("customEvent"), event);
            assertFalse(meta.containsKey("eventType"), event);
        }
    }

    @Test
        void customSelectionUsesSeparateEventNameField() {
        final Map<String, Object> authored = new HashMap<>(Map.of(
            "mobilepageeventtype", "custom",
            "customEvent", "navigation.open"));
        final Page page = page(authored);
        final Map<String, Object> meta = EnvelopeMeta.screenMeta(page, page, "navigation", List.of(), "");

        assertEquals("customEvent", meta.get("event"));
        assertEquals("navigation.open", meta.get("customEvent"));
        assertFalse(meta.containsKey("eventType"));

        authored.remove("customEvent");
        final Map<String, Object> withoutEventName =
            EnvelopeMeta.screenMeta(page, page, "navigation", List.of(), "");
        assertEquals("customEvent", withoutEventName.get("event"));
        assertEquals("", withoutEventName.get("customEvent"));
        assertFalse(withoutEventName.containsKey("eventType"));
        }

        @Test
    void authoredPageEventTakesPrecedenceOverLegacyCommerceEvent() {
        final Page page = page(new HashMap<>(Map.of(
                "event", "commerce.productViews",
                "mobilepageeventtype", "authored.type",
                "customEvent", "authored.custom")));
        final Map<String, Object> meta = EnvelopeMeta.screenMeta(page, page, "product", List.of(), "");

        assertEquals("customEvent", meta.get("event"));
        assertEquals("authored.type", meta.get("customEvent"));
        assertFalse(meta.containsKey("eventType"));
    }

    @Test
    void blankPageEventPreservesLegacyEventFields() {
        final Map<String, Object> authored = new HashMap<>(Map.of(
                "event", "customEvent",
                "customEvent", "navigation.open"));
        final Page page = page(authored);

        for (String event : List.of("", " ")) {
            authored.put("mobilepageeventtype", event);
            final Map<String, Object> meta = EnvelopeMeta.screenMeta(page, page, "navigation", List.of(), "");

            assertEquals("customEvent", meta.get("event"));
            assertEquals("navigation.open", meta.get("customEvent"));
            assertFalse(meta.containsKey("eventType"));
        }
    }

    @Test
    void missingEventPropertiesStillEmitEmptyStrings() {
        final Page page = page(new HashMap<>());
        final Map<String, Object> meta = EnvelopeMeta.screenMeta(page, page, "product", List.of(), "");

        assertEquals("", meta.get("event"));
        assertEquals("", meta.get("customEvent"));
        assertFalse(meta.containsKey("eventType"));
    }

    private static Page page(final Map<String, Object> authored) {
        final Page page = mock(Page.class);
        when(page.getProperties()).thenReturn(new ValueMapDecorator(authored));
        when(page.getPath()).thenReturn("/content/site/product");
        when(page.getName()).thenReturn("product");
        when(page.getTitle()).thenReturn("Product");
        return page;
    }
}

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
    void otherEventsPreserveAuthoredEventFields() {
        final Map<String, Object> authored = new HashMap<>(Map.of(
                "customEvent", "authored.custom",
                "event", "authored.event"));
        final Page page = page(authored);

        for (String event : List.of("", "commerce.purchases", "Commerce.productViews",
                "commerce.categoryViews.custom", " commerce.productViews ")) {
            authored.put("mobilepageeventtype", event);
            final Map<String, Object> meta = EnvelopeMeta.screenMeta(page, page, "product", List.of(), "");

            assertEquals("authored.event", meta.get("event"));
            assertEquals("authored.custom", meta.get("customEvent"), event);
            assertEquals(event, meta.get("eventType"), event);
        }
    }

    @Test
    void legacyCommerceEventDoesNotTriggerMobileEventMapping() {
        final Page page = page(new HashMap<>(Map.of(
                "event", "commerce.productViews",
                "mobilepageeventtype", "authored.type",
                "customEvent", "authored.custom")));
        final Map<String, Object> meta = EnvelopeMeta.screenMeta(page, page, "product", List.of(), "");

        assertEquals("commerce.productViews", meta.get("event"));
        assertEquals("authored.custom", meta.get("customEvent"));
        assertEquals("authored.type", meta.get("eventType"));
    }

    @Test
    void missingEventPropertiesStillEmitEmptyStrings() {
        final Page page = page(new HashMap<>());
        final Map<String, Object> meta = EnvelopeMeta.screenMeta(page, page, "product", List.of(), "");

        assertEquals("", meta.get("event"));
        assertEquals("", meta.get("customEvent"));
        assertEquals("", meta.get("eventType"));
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

package com.edsMobileApp.core.services.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.wrappers.ValueMapDecorator;

import com.day.cq.wcm.api.Page;

/**
 * The hero and product-detail rules read their Mobile Specific image-fit (and, for hero, the show
 * button switch) in addition to what they derived before. Each rule's own default still applies
 * when nothing mobile is authored, so existing content exports exactly what it did.
 */
class MobileFieldMappingTest {

    private static final String LINKED_TEXT = "<h1>Title</h1><p>Body</p><p><a href=\"/content/s/en/trip\">Book</a></p>";

    private final ComponentMappings mappings = ComponentMappings.load();

        @Test
        @SuppressWarnings("unchecked")
        void confirmationButtonUsesOnlyAuthoredComponentEvents() throws Exception {
        final PageJsonExporterImpl exporter = new PageJsonExporterImpl();
        exporter.activate(Map.of());
        final Page page = mock(Page.class);
        when(page.getPath()).thenReturn("/content/confirmation-test/en/review-order");
        final Method confirm = PageJsonExporterImpl.class.getDeclaredMethod("confirmButton", Page.class, int.class);
        confirm.setAccessible(true);
        final Map<String, Object> original = (Map<String, Object>) confirm.invoke(exporter, page, 1);
        final Map<String, Object> originalContent = (Map<String, Object>) original.get("content");
        assertEquals("Confirm", originalContent.get("label"));
        assertFalse(originalContent.containsKey("events"));
        assertFalse(originalContent.containsKey("customEvent"));
        assertEquals("checkoutForm", originalContent.get("formId"));
        assertEquals("en", originalContent.get("linkTo"));
        assertEquals(Map.of("marginTop", "16"), original.get("styles"));

        final Field defaultsField = PageJsonExporterImpl.class.getDeclaredField("siteDefaults");
        defaultsField.setAccessible(true);
        final SiteDefaults defaults = (SiteDefaults) defaultsField.get(exporter);
        final Field cacheField = SiteDefaults.class.getDeclaredField("bySite");
        cacheField.setAccessible(true);
        final Map<String, Optional<Map<String, Object>>> cache =
            (Map<String, Optional<Map<String, Object>>>) cacheField.get(defaults);
        cache.put("confirmation-test", Optional.of(Map.of("confirmButton", Map.of(
            "content", Map.of("events", "order.confirmed", "customEvent", "order.confirmed"),
            "styles", Map.of("marginTop", "24")))));

        final Resource root = mock(Resource.class);
        when(page.getContentResource()).thenReturn(root);
        when(root.getValueMap()).thenReturn(new ValueMapDecorator(Map.of("mobilebuttoneventtype", "page.event")));
        final Resource unrelated = mock(Resource.class);
        when(unrelated.getName()).thenReturn("registration");
        when(unrelated.getResourceType()).thenReturn("edsMobileApp/components/create-account");
        when(unrelated.getValueMap()).thenReturn(new ValueMapDecorator(Map.of("mobilebuttoneventtype", "registration.event")));
        when(unrelated.getChildren()).thenReturn(List.of());
        final Resource section = mock(Resource.class);
        when(section.getName()).thenReturn("section");
        when(section.getValueMap()).thenReturn(new ValueMapDecorator(Map.of()));
        final Resource orderSummary = mock(Resource.class);
        when(orderSummary.getName()).thenReturn("order-summary");
        when(orderSummary.getResourceType()).thenReturn("edsMobileApp/components/order-summary");
        final Map<String, Object> properties = new HashMap<>();
        properties.put("mobilebuttoneventtype", "authored.order.confirmed");
        when(orderSummary.getValueMap()).thenReturn(new ValueMapDecorator(properties));
        when(root.getChildren()).thenReturn(List.of(unrelated, section));
        when(section.getChildren()).thenReturn(List.of(orderSummary));

        final Map<String, Object> configured = (Map<String, Object>) confirm.invoke(exporter, page, 1);
        final Map<String, Object> configuredContent = (Map<String, Object>) configured.get("content");
        assertEquals("authored.order.confirmed", configuredContent.get("events"));
        assertEquals("authored.order.confirmed", configuredContent.get("customEvent"));
        assertEquals("Confirm", configuredContent.get("label"));
        assertEquals("en", configuredContent.get("linkTo"));
        assertEquals(Map.of("marginTop", "24"), configured.get("styles"));
        assertEquals(original.get("meta"), configured.get("meta"));
        assertFalse(originalContent.containsKey("events"));

        for (final String blank : List.of("", " ")) {
            properties.put("mobilebuttoneventtype", blank);
            final Map<String, Object> noEvent = (Map<String, Object>) confirm.invoke(exporter, page, 1);
            final Map<String, Object> noEventContent = (Map<String, Object>) noEvent.get("content");
            assertFalse(noEventContent.containsKey("events"));
            assertFalse(noEventContent.containsKey("customEvent"));
        }
        properties.remove("mobilebuttoneventtype");
        final Map<String, Object> missing = (Map<String, Object>) confirm.invoke(exporter, page, 1);
        final Map<String, Object> missingContent = (Map<String, Object>) missing.get("content");
        assertFalse(missingContent.containsKey("events"));
        assertFalse(missingContent.containsKey("customEvent"));

        when(page.getPath()).thenReturn("/content/another-site/en/review-order");
        final Map<String, Object> otherSite = (Map<String, Object>) confirm.invoke(exporter, page, 1);
        assertEquals(originalContent, otherSite.get("content"));
        assertEquals(original.get("styles"), otherSite.get("styles"));
        }

    @Test
    void experienceDecisioningSurvivesReducedExport() throws Exception {
        final Map<String, Object> source = authored(
                "mobileDecisioningType", "experience",
                "mobileSurface", "luma.home.campaignArea1",
                "mobileFallbackImage", "https://example.com/offer.png",
                "mobileContentAlignment", "left",
                "mobileVerticalAlignment", "bottom",
                "mobileShowTitle", false,
                "mobileShowButton", false,
                "mobileShowDebugMessage", false);
        final ComponentMapping rule = mappings.find("decisioning-container").effectiveFor(source);
        final Map<String, Object> content = rule.apply(source, Map.of());
        assertEquals("offer", rule.getComponent());
        assertEquals("luma.home.campaignArea1", content.get("surface"));
        assertEquals("https://example.com/offer.png", content.get("fallbackImage"));
        assertEquals("left", content.get("contentAlignment"));
        assertEquals("bottom", content.get("verticalAlignment"));
        assertEquals(false, content.get("showTitle"));
        assertEquals(false, content.get("showButton"));
        assertEquals(false, content.get("showDebugMessage"));
        assertSurvivesReducedExport(rule, content);
    }

    @Test
    void offerDecisioningSurvivesReducedExport() throws Exception {
        final Map<String, Object> source = authored(
                "mobileDecisioningType", "offer", "mobileDecisionScope", "luma-offer");
        final String variant = mappings.find("decisioning-container").variantRuleFor(source);
        assertEquals("decisioning-offer", variant);
        final ComponentMapping rule = mappings.find(variant).effectiveFor(source);
        final Map<String, Object> content = rule.apply(source, Map.of());
        assertEquals("offerRenderer", rule.getComponent());
        assertEquals("luma-offer", content.get("decisionScope"));
        assertSurvivesReducedExport(rule, content);
    }

    @Test
    void decisioningMapsSharedFieldsAndVariantSpecificTargets() {
        for (final String type : List.of("experience", "offer")) {
            final boolean offer = "offer".equals(type);
            final String sourceField = offer ? "mobileDecisionScope" : "mobileSurface";
            final String targetField = offer ? "decisionScope" : "surface";
            final Map<String, Object> source = authored(
                    "mobileDecisioningType", type,
                    sourceField, "luma-campaign",
                    "mobileNickname", "Campaign");
            final ComponentMapping base = mappings.find("decisioning-container");
            final String variant = base.variantRuleFor(source);
            final ComponentMapping rule = variant == null ? base : mappings.find(variant);
            final Map<String, Object> content = rule.apply(source, Map.of());
            assertEquals("luma-campaign", content.get(targetField));
            assertEquals(false, content.containsKey(offer ? "surface" : "decisionScope"));
            assertEquals("Campaign", content.get("nickname"));
            assertEquals("cover", content.get("imageResize"));
            for (final String resize : List.of("contain", "cover")) {
                source.put("mobileImageResize", resize);
                assertEquals(resize, rule.apply(source, Map.of()).get("imageResize"));
            }
        }
    }

    @Test
    void inboxAndContentCardsSurviveReducedExport() throws Exception {
        for (final String name : List.of("inbox", "content-card")) {
            final Map<String, Object> source = authored(
                    "mobileSurface", "luma.home.campaignArea1",
                    "mobileBorderColor", "#000000",
                    "mobileBorderRadius", 12,
                    "mobileBackgroundColor", "#ffffff");
            final ComponentMapping rule = mappings.find(name);
            assertEquals("inbox".equals(name) ? "inbox" : "contentCards", rule.getComponent());
            final Map<String, Object> content = rule.apply(source, Map.of());
            assertEquals("luma.home.campaignArea1", content.get("surface"));
            final Map<String, Object> styles = rule.applyStyles(source, Map.of());
            assertEquals("#000000", styles.get("borderColor"));
            assertEquals(12, ((Number) styles.get("borderRadius")).intValue());
            assertEquals("#ffffff", styles.get("backgroundColor"));
            assertEquals(9, ((Number) rule.applyStyles(Map.of(), Map.of()).get("borderRadius")).intValue());
            assertSurvivesReducedExport(rule, content);
        }
    }

    private static void assertSurvivesReducedExport(final ComponentMapping rule,
            final Map<String, Object> content) throws Exception {
        final List<Map<String, Object>> components = List.of(Map.of(
                "meta", Map.of("component", rule.getComponent()), "content", content));
        final Method filter = PageJsonExporterImpl.class.getDeclaredMethod("keepEnabledComponents", List.class);
        filter.setAccessible(true);
        assertEquals(components, filter.invoke(null, components));
    }

    @Test
    void cartReadsTheAuthoredLowercaseVisibilityFlags() {
        final Map<String, Object> content = mappings.find("cart").apply(Map.of(
                "showdiscount", false,
                "showpromocode", false,
                "showshippingcharge", false), Map.of());
        assertEquals(false, content.get("showDiscount"));
        assertEquals(false, content.get("showPromoCode"));
        assertEquals(false, content.get("showShippingCharge"));
    }

    private static Map<String, Object> authored(final Object... keyValue) {
        final Map<String, Object> out = new HashMap<>();
        for (int i = 0; i < keyValue.length; i += 2) {
            out.put((String) keyValue[i], keyValue[i + 1]);
        }
        return out;
    }

    private Map<String, Object> hero(final Map<String, Object> source) {
        return mappings.find("hero").apply(source, Map.of());
    }

    private Map<String, Object> productDetail(final Map<String, Object> source) {
        return mappings.find("product-detail").apply(source, Map.of());
    }

    // ---------- hero: imageResize ----------

    @Test
    void aHeroWithoutMobileImageResizeStillExportsCover() {
        assertEquals("cover", hero(authored("image", "/a.jpg", "text", "<h1>T</h1>")).get("imageResize"));
    }

    @Test
    void aHeroReadsMobileImageResize() {
        assertEquals("contain",
                hero(authored("image", "/a.jpg", "text", "<h1>T</h1>", "mobileimageresize", "contain"))
                        .get("imageResize"));
    }

    // ---------- hero: showButton ----------

    @Test
    void withoutTheSwitchShowButtonStillFollowsTheLink() {
        assertEquals(true, hero(authored("image", "/a.jpg", "text", LINKED_TEXT)).get("showButton"));
        assertEquals(false, hero(authored("image", "/a.jpg", "text", "<h1>T</h1>")).get("showButton"));
    }

    @Test
    void anExplicitSwitchOnShowsTheButtonEvenWithoutALink() {
        assertEquals(true,
                hero(authored("image", "/a.jpg", "text", "<h1>T</h1>", "mobileshowbutton", "true"))
                        .get("showButton"));
        assertEquals(true,
                hero(authored("image", "/a.jpg", "text", "<h1>T</h1>", "mobileshowbutton", Boolean.TRUE))
                        .get("showButton"));
    }

    @Test
    void anExplicitSwitchOffHidesTheButtonEvenWithALink() {
        assertEquals(false,
                hero(authored("image", "/a.jpg", "text", LINKED_TEXT, "mobileshowbutton", "false"))
                        .get("showButton"));
        assertEquals(false,
                hero(authored("image", "/a.jpg", "text", LINKED_TEXT, "mobileshowbutton", Boolean.FALSE))
                        .get("showButton"));
    }

    // ---------- product-detail ----------

    @Test
    void productDetailDefaultsToContain() {
        final Map<String, Object> mapped = productDetail(authored());
        assertEquals("contain", mapped.get("imageResize"));
        assertEquals(true, mapped.containsKey("product"));
    }

    @Test
    void productDetailReadsMobileImageResize() {
        assertEquals("cover", productDetail(authored("mobileimageresize", "cover")).get("imageResize"));
    }
}

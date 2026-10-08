package com.edsMobileApp.core.services.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.apache.sling.models.factory.ModelFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import com.day.cq.wcm.api.Page;
import com.edsMobileApp.core.models.impl.GenericComponent;
import com.edsMobileApp.core.schema.SchemaConstants;
import com.edsMobileApp.core.services.ExportOptions;
import com.edsMobileApp.core.services.FormSchemaService;

import io.wcm.testing.mock.aem.junit5.AemContext;
import io.wcm.testing.mock.aem.junit5.AemContextExtension;

/**
 * The hero and product-detail rules read their Mobile Specific image-fit (and, for hero, the show
 * button switch) in addition to what they derived before. Each rule's own default still applies
 * when nothing mobile is authored, so existing content exports exactly what it did.
 */
@ExtendWith(AemContextExtension.class)
class MobileFieldMappingTest {

    private static final String LINKED_TEXT = "<h1>Title</h1><p>Body</p><p><a href=\"/content/s/en/trip\">Book</a></p>";

    private final ComponentMappings mappings = ComponentMappings.load();
    private final AemContext context = new AemContext();

        @Test
        @SuppressWarnings("unchecked")
        void confirmationButtonUsesOnlyAuthoredComponentEvents() throws Exception {
        final PageJsonExporterImpl exporter = new PageJsonExporterImpl();
        exporter.activate(Map.of());
        final Page page = mock(Page.class);
        when(page.getPath()).thenReturn("/content/confirmation-test/en/order-summary");
        when(page.getContentResource()).thenThrow(new IllegalStateException("Confirmation must use mapped content"));
        final Map<String, Object> original = mappedConfirmation(exporter, page, Map.of());
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

        final Map<String, Object> properties = new HashMap<>();
        properties.put("mobilebuttoneventtype", "authored.order.confirmed");

        final Map<String, Object> configured = mappedConfirmation(exporter, page, properties);
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
            final Map<String, Object> noEvent = mappedConfirmation(exporter, page, properties);
            final Map<String, Object> noEventContent = (Map<String, Object>) noEvent.get("content");
            assertFalse(noEventContent.containsKey("events"));
            assertFalse(noEventContent.containsKey("customEvent"));
        }
        properties.remove("mobilebuttoneventtype");
        final Map<String, Object> missing = mappedConfirmation(exporter, page, properties);
        final Map<String, Object> missingContent = (Map<String, Object>) missing.get("content");
        assertFalse(missingContent.containsKey("events"));
        assertFalse(missingContent.containsKey("customEvent"));

        when(page.getPath()).thenReturn("/content/another-site/en/order-summary");
        final Map<String, Object> otherSite = mappedConfirmation(exporter, page, properties);
        assertEquals(originalContent, otherSite.get("content"));
        assertEquals(original.get("styles"), otherSite.get("styles"));
        }

    @SuppressWarnings("unchecked")
    private Map<String, Object> mappedConfirmation(final PageJsonExporterImpl exporter, final Page page,
                                                   final Map<String, Object> authored) throws Exception {
        final Map<String, Object> summaryContent = mappings.find("order-summary").apply(authored, Map.of());
        assertEquals(((String) authored.getOrDefault("mobilebuttoneventtype", "")).trim(), summaryContent.get("eventType"));
        final Map<String, Object> summary = new HashMap<>();
        summary.put("meta", new HashMap<>(Map.of("id", "summary-id", "component", "cartSummary", "order", 3)));
        summary.put("content", summaryContent);
        final Map<String, Object> panel = Map.of("meta", Map.of("component", "panel"), "content", List.of(summary));
        final Method review = PageJsonExporterImpl.class.getDeclaredMethod("addCommerceActions", List.class, Page.class);
        review.setAccessible(true);
        final List<Map<String, Object>> result = (List<Map<String, Object>>) review.invoke(exporter, List.of(panel), page);
        assertEquals(1, result.size());
        assertEquals("panel", ((Map<String, Object>) result.get(0).get("meta")).get("component"));
        final List<Map<String, Object>> children = (List<Map<String, Object>>) result.get(0).get("content");
        assertEquals(2, children.size());
        assertEquals(0, ((Map<String, Object>) children.get(0).get("meta")).get("order"));
        final Map<String, Object> emittedContent = (Map<String, Object>) children.get(0).get("content");
        assertFalse(emittedContent.containsKey("eventType"));
        assertFalse(emittedContent.containsKey("link"));
        assertFalse(emittedContent.containsKey("_orderSummary"));
        assertEquals("TOTAL", emittedContent.get("totalLabel"));
        assertEquals(1, ((Map<String, Object>) children.get(1).get("meta")).get("order"));
        assertEquals(3, ((Map<String, Object>) summary.get("meta")).get("order"));
        return children.get(1);
    }

        @Test
        @SuppressWarnings("unchecked")
        void orderSummaryRedirectOverridesDefaultsAndIgnoresWebConfirmPath() throws Exception {
        final PageJsonExporterImpl exporter = new PageJsonExporterImpl();
        exporter.activate(Map.of());
        final Page page = mock(Page.class);
        when(page.getPath()).thenReturn("/content/confirmation-test/en/another-page");
        final Field defaultsField = PageJsonExporterImpl.class.getDeclaredField("siteDefaults");
        defaultsField.setAccessible(true);
        final Field cacheField = SiteDefaults.class.getDeclaredField("bySite");
        cacheField.setAccessible(true);
        final Map<String, Optional<Map<String, Object>>> cache =
            (Map<String, Optional<Map<String, Object>>>) cacheField.get(defaultsField.get(exporter));
        cache.put("confirmation-test", Optional.of(Map.of("confirmButton", Map.of(
            "content", Map.of("linkTo", "site-default")))));
        final Map<String, Object> authored = new HashMap<>();
        authored.put("confirm-path", "/content/luma-site/language-masters/en/web-confirmation");
        authored.put("mobileRedirectUrl", "/content/luma-site/language-masters/en/order-confirmation.html?source=mobile#complete");
        assertEquals("order-confirmation?source=mobile#complete",
            ((Map<String, Object>) mappedConfirmation(exporter, page, authored).get("content")).get("linkTo"));
        for (final String blank : List.of("", " ")) {
            authored.put("mobileRedirectUrl", blank);
            assertEquals("site-default",
                ((Map<String, Object>) mappedConfirmation(exporter, page, authored).get("content")).get("linkTo"));
        }
        authored.remove("mobileRedirectUrl");
        assertEquals("site-default",
            ((Map<String, Object>) mappedConfirmation(exporter, page, authored).get("content")).get("linkTo"));
        }

        @Test
        @SuppressWarnings("unchecked")
        void orderSummariesWorkOnAnyScreenWithoutReplacingOtherContent() throws Exception {
        final PageJsonExporterImpl exporter = new PageJsonExporterImpl();
        exporter.activate(Map.of());
        final Page page = mock(Page.class);
        when(page.getPath()).thenReturn("/content/confirmation-test/en/review-purchase");
        when(page.getName()).thenReturn("review-purchase");
        final Map<String, Object> heading = Map.of("meta", Map.of("id", "heading", "component", "htmlText", "order", 0),
            "content", Map.of("text", "Your order"));
        final Map<String, Object> firstSummary = Map.of(
            "meta", Map.of("id", "first-summary", "component", "cartSummary", "order", 0),
            "content", mappings.find("order-summary").apply(Map.of(
                "mobileRedirectUrl", "/content/luma-site/language-masters/en/order-confirmation",
                "mobilebuttoneventtype", "commerce.purchases"), Map.of()));
        final Map<String, Object> cart = Map.of("meta", Map.of("id", "cart", "component", "cartSummary", "order", 1),
            "content", Map.of("totalLabel", "CART TOTAL"));
        final Map<String, Object> secondSummary = Map.of(
            "meta", Map.of("id", "second-summary", "component", "cartSummary", "order", 2),
            "content", mappings.find("order-summary").apply(Map.of(
                "mobileRedirectUrl", "sign-in?source=order#complete",
                "mobilebuttoneventtype", "other.purchase"), Map.of()));
        final List<Map<String, Object>> fields = List.of(Map.of("name", "email", "type", "text"));
        final Map<String, Object> form = Map.of("meta", Map.of("id", "form", "component", "form", "order", 3),
            "content", fields);
        final Map<String, Object> panel = Map.of("meta", Map.of("id", "panel", "component", "panel", "order", 1),
            "styles", Map.of("marginTop", "8"), "content", List.of(firstSummary, cart, secondSummary, form));
        final Method review = PageJsonExporterImpl.class.getDeclaredMethod("addCommerceActions", List.class, Page.class);
        review.setAccessible(true);
        {
            final List<Map<String, Object>> content = (List<Map<String, Object>>) review.invoke(exporter,
                List.of(heading, panel), page);
            assertEquals(2, content.size());
            assertEquals(heading, content.get(0));
            assertEquals(panel.get("styles"), content.get(1).get("styles"));
            final List<Map<String, Object>> children = (List<Map<String, Object>>) content.get(1).get("content");
            assertEquals(6, children.size());
            assertEquals("first-summary", ((Map<String, Object>) children.get(0).get("meta")).get("id"));
            assertEquals(cart.get("content"), children.get(2).get("content"));
            assertEquals("second-summary", ((Map<String, Object>) children.get(3).get("meta")).get("id"));
            assertEquals(fields, children.get(5).get("content"));
            final Map<String, Object> firstConfirm = (Map<String, Object>) children.get(1).get("content");
            assertEquals("order-confirmation", firstConfirm.get("linkTo"));
            assertEquals("commerce.purchases", firstConfirm.get("events"));
            assertEquals("commerce.purchases", firstConfirm.get("customEvent"));
            final Map<String, Object> secondConfirm = (Map<String, Object>) children.get(4).get("content");
            assertEquals("sign-in?source=order#complete", secondConfirm.get("linkTo"));
            assertEquals("other.purchase", secondConfirm.get("events"));
            assertNotEquals(((Map<String, Object>) children.get(1).get("meta")).get("id"),
                ((Map<String, Object>) children.get(4).get("meta")).get("id"));
            for (int index = 0; index < children.size(); index++) {
            assertEquals(index, ((Map<String, Object>) children.get(index).get("meta")).get("order"));
                if (children.get(index).get("content") instanceof Map) {
                    assertFalse(((Map<String, Object>) children.get(index).get("content")).containsKey("_orderSummary"));
                }
            }
        }
        assertEquals(4, ((List<?>) panel.get("content")).size());
        assertEquals(List.of(heading, cart), review.invoke(exporter, List.of(heading, cart), page));
        }

        @Test
        @SuppressWarnings("unchecked")
        void buildScreenAddsConfirmationToAnAuthoredBlockOnAnyPage() throws Exception {
        final PageJsonExporterImpl exporter = new PageJsonExporterImpl();
        exporter.activate(Map.of());
        final UrlServiceImpl urlService = new UrlServiceImpl();
        final ModelFactory modelFactory = mock(ModelFactory.class);
        when(modelFactory.getModelFromResource(any()))
            .thenAnswer(invocation -> new GenericComponent(invocation.getArgument(0), urlService));
        final FormSchemaServiceImpl schemas = new FormSchemaServiceImpl();
        schemas.activate();
        final ComponentTreeWalker walker = new ComponentTreeWalker(modelFactory, urlService, schemas,
            new StyleResolver(new String[0]), mappings);
        final Field walkerField = PageJsonExporterImpl.class.getDeclaredField("walker");
        walkerField.setAccessible(true);
        walkerField.set(exporter, walker);
        final Page page = context.create().page("/content/confirmation-test/en/review-purchase");
        final String root = page.getPath() + "/jcr:content/root";
        context.create().resource(root);
        context.create().resource(root + "/summary", "sling:resourceType", "eds/components/order-summary",
            "mobileRedirectUrl", "/content/luma-site/language-masters/en/order-confirmation",
            "mobilebuttoneventtype", "commerce.purchases");
        final Method build = PageJsonExporterImpl.class.getDeclaredMethod("buildScreen", Page.class, Page.class,
            String.class, ExportOptions.class, List.class, String.class, Set.class, Map.class, String.class);
        build.setAccessible(true);
        for (final boolean reduced : List.of(false, true)) {
            final Map<String, Object> screen = (Map<String, Object>) build.invoke(exporter, page, page, "",
                ExportOptions.of(reduced), List.of(), null, Set.of(), Map.of(), "");
            assertEquals("review-purchase", ((Map<String, Object>) screen.get("meta")).get("name"));
            final List<Map<String, Object>> content = (List<Map<String, Object>>) screen.get("content");
            assertEquals(2, content.size());
            assertEquals("cartSummary", ((Map<String, Object>) content.get(0).get("meta")).get("component"));
            assertFalse(((Map<String, Object>) content.get(0).get("content")).containsKey("_orderSummary"));
            assertEquals("button", ((Map<String, Object>) content.get(1).get("meta")).get("component"));
            final Map<String, Object> confirm = (Map<String, Object>) content.get(1).get("content");
            assertEquals("order-confirmation", confirm.get("linkTo"));
            assertEquals("commerce.purchases", confirm.get("events"));
            assertEquals("commerce.purchases", confirm.get("customEvent"));
        }
        }

    @Test
    @SuppressWarnings("unchecked")
        void cartActionsFollowEveryCartBlockOnAnyScreen() throws Exception {
        final PageJsonExporterImpl exporter = new PageJsonExporterImpl();
        exporter.activate(Map.of());
        final UrlServiceImpl urlService = new UrlServiceImpl();
        final ModelFactory modelFactory = mock(ModelFactory.class);
        when(modelFactory.getModelFromResource(any()))
            .thenAnswer(invocation -> new GenericComponent(invocation.getArgument(0), urlService));
        final FormSchemaServiceImpl schemas = new FormSchemaServiceImpl();
        schemas.activate();
        final ComponentTreeWalker walker = new ComponentTreeWalker(modelFactory, urlService, schemas,
            new StyleResolver(new String[0]), mappings);
        final Field walkerField = PageJsonExporterImpl.class.getDeclaredField("walker");
        walkerField.setAccessible(true);
        walkerField.set(exporter, walker);
        final Page page = context.create().page("/content/confirmation-test/en/review-items");
        final String root = page.getPath() + "/jcr:content/root";
        context.create().resource(root);
        context.create().resource(root + "/first-cart", "sling:resourceType", "eds/components/cart", "label", "FIRST",
            "mobileButtonEventOnEmptyCart", " commerce.cartCleared ",
            "mobileButtonEventOnCheckout", " commerce.checkouts ",
            "mobileRedirectUrl", "/content/luma-site/language-masters/en/review-purchase.html?source=mobile#order");
        context.create().resource(root + "/second-cart", "sling:resourceType", "eds/components/cart", "label", "SECOND");
        context.create().resource(root + "/summary", "sling:resourceType", "eds/components/order-summary");
        final Method build = PageJsonExporterImpl.class.getDeclaredMethod("buildScreen", Page.class, Page.class,
            String.class, ExportOptions.class, List.class, String.class, Set.class, Map.class, String.class);
        build.setAccessible(true);
        Map<String, Object> previousRowMeta = null;
        for (final boolean reduced : List.of(false, true)) {
            final Map<String, Object> screen = (Map<String, Object>) build.invoke(exporter, page, page, "",
                ExportOptions.of(reduced), List.of(), null, Set.of(), Map.of(), "");
            assertEquals("review-items", ((Map<String, Object>) screen.get("meta")).get("name"));
            final List<Map<String, Object>> content = (List<Map<String, Object>>) screen.get("content");
            assertEquals(6, content.size());
            assertEquals("FIRST", ((Map<String, Object>) content.get(0).get("content")).get("label"));
            assertEquals("SECOND", ((Map<String, Object>) content.get(2).get("content")).get("label"));
            for (final int cartIndex : List.of(0, 2)) {
            final Map<String, Object> cartContent = (Map<String, Object>) content.get(cartIndex).get("content");
            assertFalse(cartContent.containsKey("_cart"));
            assertFalse(cartContent.containsKey("emptyCartEvent"));
            assertFalse(cartContent.containsKey("checkoutEvent"));
            assertFalse(cartContent.containsKey("checkoutLink"));
            assertFalse(cartContent.containsKey("mobileRedirectUrl"));
            final Map<String, Object> row = content.get(cartIndex + 1);
            final Map<String, Object> rowMeta = (Map<String, Object>) row.get("meta");
            assertEquals("panel", rowMeta.get("component"));
            assertEquals("horizontal", rowMeta.get("layout"));
            assertEquals(cartIndex + 1, rowMeta.get("order"));
            final List<Map<String, Object>> buttons = (List<Map<String, Object>>) row.get("content");
            assertEquals(2, buttons.size());
            final Map<String, Object> empty = (Map<String, Object>) buttons.get(0).get("content");
            assertEquals("Empty cart", empty.get("label"));
            assertEquals(cartIndex == 0 ? "commerce.cartCleared" : mappings.find("cart").apply(Map.of(), Map.of()).get("emptyCartEvent"), empty.get("events"));
            assertEquals("", empty.get("linkTo"));
            final Map<String, Object> review = (Map<String, Object>) buttons.get(1).get("content");
            assertEquals("Review order", review.get("label"));
            assertEquals(cartIndex == 0 ? "commerce.checkouts" : mappings.find("cart").apply(Map.of(), Map.of()).get("checkoutEvent"), review.get("events"));
            assertEquals(cartIndex == 0 ? "review-purchase?source=mobile#order" : "checkout", review.get("linkTo"));
            assertEquals(0, ((Map<String, Object>) buttons.get(0).get("meta")).get("order"));
            assertEquals(1, ((Map<String, Object>) buttons.get(1).get("meta")).get("order"));
            }
            assertNotEquals(((Map<String, Object>) content.get(1).get("meta")).get("id"),
                ((Map<String, Object>) content.get(3).get("meta")).get("id"));
            final List<Map<String, Object>> firstButtons = (List<Map<String, Object>>) content.get(1).get("content");
            final List<Map<String, Object>> secondButtons = (List<Map<String, Object>>) content.get(3).get("content");
            for (int index = 0; index < 2; index++) {
            assertNotEquals(((Map<String, Object>) firstButtons.get(index).get("meta")).get("id"),
                ((Map<String, Object>) secondButtons.get(index).get("meta")).get("id"));
            }
            final Map<String, Object> rowMeta = (Map<String, Object>) content.get(1).get("meta");
            if (previousRowMeta != null) {
            assertEquals(previousRowMeta, rowMeta);
            }
            previousRowMeta = rowMeta;
            assertEquals("cartSummary", ((Map<String, Object>) content.get(4).get("meta")).get("component"));
            assertFalse(((Map<String, Object>) content.get(4).get("content")).containsKey("_orderSummary"));
            assertEquals("Confirm", ((Map<String, Object>) content.get(5).get("content")).get("label"));
        }
        final Page emptyCartPage = context.create().page("/content/confirmation-test/en/cart");
        final Map<String, Object> emptyScreen = (Map<String, Object>) build.invoke(exporter,
            emptyCartPage, emptyCartPage, "", ExportOptions.of(false), List.of(), null, Set.of(), Map.of(), "");
        assertEquals(List.of(), emptyScreen.get("content"));
        }

        @Test
        @SuppressWarnings("unchecked")
        void blankCartButtonEventsKeepExistingDefaults() throws Exception {
            final PageJsonExporterImpl exporter = new PageJsonExporterImpl();
            exporter.activate(Map.of());
            final Page page = mock(Page.class);
            when(page.getPath()).thenReturn("/content/cart-test/en/basket");
            final Method actions = PageJsonExporterImpl.class.getDeclaredMethod("addCommerceActions", List.class, Page.class);
            actions.setAccessible(true);
            for (final String blank : List.of("", " \t ")) {
                final Map<String, Object> cart = Map.of(
                        "meta", Map.of("id", "cart-id", "component", "cartSummary", "order", 0),
                        "content", mappings.find("cart").apply(Map.of(
                                "mobileButtonEventOnEmptyCart", blank,
                                "mobileButtonEventOnCheckout", blank,
                                "mobileRedirectUrl", blank), Map.of()));
                final List<Map<String, Object>> result = (List<Map<String, Object>>) actions.invoke(exporter, List.of(cart), page);
                final List<Map<String, Object>> buttons = (List<Map<String, Object>>) result.get(1).get("content");
                assertEquals(mappings.find("cart").apply(Map.of(), Map.of()).get("emptyCartEvent"),
                    ((Map<String, Object>) buttons.get(0).get("content")).get("events"));
                assertEquals(mappings.find("cart").apply(Map.of(), Map.of()).get("checkoutEvent"),
                    ((Map<String, Object>) buttons.get(1).get("content")).get("events"));
                assertEquals("checkout", ((Map<String, Object>) buttons.get(1).get("content")).get("linkTo"));
                assertFalse(((Map<String, Object>) result.get(0).get("content")).containsKey("checkoutLink"));
            }
        }

        @Test
        @SuppressWarnings("unchecked")
        void cartActionsPreserveNestedPanelsAndSurroundingContent() throws Exception {
        final PageJsonExporterImpl exporter = new PageJsonExporterImpl();
        exporter.activate(Map.of());
        final Page page = mock(Page.class);
        when(page.getPath()).thenReturn("/content/cart-test/en/basket");
        final Map<String, Object> heading = Map.of("meta", Map.of("component", "htmlText", "order", 0),
            "content", Map.of("text", "Your items"));
        final Map<String, Object> cart = Map.of("meta", Map.of("id", "cart-id", "component", "cartSummary", "order", 1),
            "content", mappings.find("cart").apply(Map.of("mobileRedirectUrl",
                "/content/luma-site/language-masters/en/nested-checkout.html"), Map.of()));
        final Map<String, Object> innerPanel = Map.of("meta", Map.of("component", "panel", "order", 1),
            "styles", Map.of("marginTop", "8"), "content", List.of(cart));
        final Map<String, Object> outerPanel = Map.of("meta", Map.of("component", "panel", "order", 0),
            "styles", Map.of("padding", "16"), "content", List.of(heading, innerPanel, heading));
        final Method actions = PageJsonExporterImpl.class.getDeclaredMethod("addCommerceActions", List.class, Page.class);
        actions.setAccessible(true);
        final List<Map<String, Object>> result = (List<Map<String, Object>>) actions.invoke(exporter,
            List.of(outerPanel), page);
        assertEquals(1, result.size());
        assertEquals(outerPanel.get("styles"), result.get(0).get("styles"));
        final List<Map<String, Object>> outerChildren = (List<Map<String, Object>>) result.get(0).get("content");
        assertEquals(3, outerChildren.size());
        assertEquals(heading.get("content"), outerChildren.get(0).get("content"));
        assertEquals(heading.get("content"), outerChildren.get(2).get("content"));
        assertEquals(innerPanel.get("styles"), outerChildren.get(1).get("styles"));
        final List<Map<String, Object>> innerChildren = (List<Map<String, Object>>) outerChildren.get(1).get("content");
        assertEquals(2, innerChildren.size());
        assertEquals("cart-id", ((Map<String, Object>) innerChildren.get(0).get("meta")).get("id"));
        assertFalse(((Map<String, Object>) innerChildren.get(0).get("content")).containsKey("_cart"));
        assertFalse(((Map<String, Object>) innerChildren.get(0).get("content")).containsKey("checkoutLink"));
        final List<Map<String, Object>> buttons = (List<Map<String, Object>>) innerChildren.get(1).get("content");
        assertEquals("nested-checkout", ((Map<String, Object>) buttons.get(1).get("content")).get("linkTo"));
        assertEquals("nested-checkout", ((Map<String, Object>) cart.get("content")).get("checkoutLink"));
        assertEquals("panel", ((Map<String, Object>) innerChildren.get(1).get("meta")).get("component"));
        assertEquals(1, ((Map<String, Object>) innerChildren.get(1).get("meta")).get("order"));
        assertEquals(List.of(cart), innerPanel.get("content"));
        }

        @Test
        @SuppressWarnings("unchecked")
    void checkoutPreservesOnlyAuthoredEvents() throws Exception {
        final PageJsonExporterImpl exporter = new PageJsonExporterImpl();
        exporter.activate(Map.of());
        final Page page = mock(Page.class);
        when(page.getPath()).thenReturn("/content/checkout-test/en/checkout");
        final Field defaultsField = PageJsonExporterImpl.class.getDeclaredField("siteDefaults");
        defaultsField.setAccessible(true);
        final Field cacheField = SiteDefaults.class.getDeclaredField("bySite");
        cacheField.setAccessible(true);
        final Map<String, Optional<Map<String, Object>>> cache =
                (Map<String, Optional<Map<String, Object>>>) cacheField.get(defaultsField.get(exporter));
        cache.put("checkout-test", Optional.of(Map.of("checkoutForm", Map.of("eventType", "site.event"))));
        final Method patch = PageJsonExporterImpl.class.getDeclaredMethod("patchCheckoutFormMeta", List.class, Page.class);
        patch.setAccessible(true);
        for (final String event : List.of("authored.checkout.event", "")) {
            final Map<String, Object> form = new HashMap<>();
            form.put("meta", Map.of("id", "checkout-id", "order", 0, "component", "form", "eventType", event));
            patch.invoke(exporter, List.of(form), page);
            final Map<String, Object> meta = (Map<String, Object>) form.get("meta");
            assertEquals(event, meta.get("eventType"));
            assertEquals("checkout-id", meta.get("id"));
            assertEquals("order-summary", meta.get("link"));
            assertEquals("checkoutForm", meta.get("formId"));
            assertEquals("Checkout", meta.get("submitButtonLabel"));
        }
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
    void orderSummaryUsesItsAuthoredScreenName() throws Exception {
        final PageJsonExporterImpl exporter = new PageJsonExporterImpl();
        exporter.activate(Map.of());
        final Page page = mock(Page.class);
        when(page.getPath()).thenReturn("/content/luma-site/en/order-summary");
        final Method key = PageJsonExporterImpl.class.getDeclaredMethod("screenKey", Page.class);
        key.setAccessible(true);
        for (final String name : List.of("order-summary", "review-order")) {
            when(page.getName()).thenReturn(name);
            assertEquals(name, key.invoke(exporter, page));
        }
    }

    @Test
    void orderSummaryMapsItsMobileRedirectToAScreenKey() {
        assertEquals("order-confirmation?source=mobile#complete", mappings.find("order-summary").apply(
                Map.of("mobileRedirectUrl", "/content/luma-site/language-masters/en/order-confirmation.html?source=mobile#complete"),
                Map.of()).get("link"));
        assertEquals("", mappings.find("order-summary").apply(Map.of(), Map.of()).get("link"));
        assertEquals("", mappings.find("order-summary").apply(Map.of("mobileRedirectUrl", " "), Map.of()).get("link"));
    }

    @Test
    void cartMapsItsMobileRedirectToAScreenKeyWithCheckoutFallback() {
        assertEquals("review-purchase?source=mobile#order", mappings.find("cart").apply(
                Map.of("mobileRedirectUrl", " /content/luma-site/language-masters/en/review-purchase.html?source=mobile#order "),
                Map.of()).get("checkoutLink"));
        assertEquals("checkout", mappings.find("cart").apply(Map.of(), Map.of()).get("checkoutLink"));
        for (final String blank : List.of("", " \t ")) {
            assertEquals("checkout", mappings.find("cart").apply(Map.of("mobileRedirectUrl", blank), Map.of()).get("checkoutLink"));
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void checkoutAuthoredRedirectWinsOverSiteDefault() throws Exception {
        final PageJsonExporterImpl exporter = new PageJsonExporterImpl();
        exporter.activate(Map.of());
        final Page page = mock(Page.class);
        when(page.getPath()).thenReturn("/content/luma-site/en/checkout");
        final Method patch = PageJsonExporterImpl.class.getDeclaredMethod("patchCheckoutFormMeta", List.class, Page.class);
        patch.setAccessible(true);
        for (final String redirect : List.of("confirmation", "", " ")) {
            final Map<String, Object> form = new HashMap<>();
            form.put("meta", Map.of("id", "checkout-id", "order", 0, "component", "form", "link", redirect));
            patch.invoke(exporter, List.of(form), page);
            final Map<String, Object> meta = (Map<String, Object>) form.get("meta");
            assertEquals("confirmation".equals(redirect) ? "confirmation" : "order-summary", meta.get("link"));
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void navigationTargetsUseTheExportedScreenAliases() throws Exception {
        final PageJsonExporterImpl exporter = new PageJsonExporterImpl();
        exporter.activate(Map.of());
        final Page page = mock(Page.class);
        when(page.getPath()).thenReturn("/content/luma-site/en/checkout");
        final Map<String, Object> form = Map.of("meta", Map.of("component", "form",
                "link", "review-order?source=mobile#summary", "navigateOnSubmit", "sign-in"));
        final Map<String, Object> button = Map.of("meta", Map.of("component", "button"),
                "content", Map.of("linkTo", "sign-in#account"));
        final Map<String, Object> external = Map.of("meta", Map.of("component", "button"),
                "content", Map.of("linkTo", "https://example.com/sign-in#account", "link", "#account"));
        final Map<String, Object> panel = Map.of("meta", Map.of("component", "panel"),
                "content", List.of(form, button, external));
        final Method normalize = PageJsonExporterImpl.class.getDeclaredMethod("normalizeScreenTargets", List.class, Page.class);
        normalize.setAccessible(true);
        final List<Map<String, Object>> result = (List<Map<String, Object>>) normalize.invoke(exporter, List.of(panel), page);
        final List<Map<String, Object>> children = (List<Map<String, Object>>) result.get(0).get("content");
        assertEquals("review-order?source=mobile#summary", ((Map<String, Object>) children.get(0).get("meta")).get("link"));
        assertEquals("login", ((Map<String, Object>) children.get(0).get("meta")).get("navigateOnSubmit"));
        assertEquals("login#account", ((Map<String, Object>) children.get(1).get("content")).get("linkTo"));
        assertEquals(external, children.get(2));
        assertEquals("review-order?source=mobile#summary", ((Map<String, Object>) form.get("meta")).get("link"));
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

    @Test
    void webViewSurvivesReducedExport() throws Exception {
        final ComponentMapping rule = mappings.find("web-view");
        assertEquals("webView", rule.getComponent());
        final Map<String, Object> content = rule.apply(
                Map.of("mobileUrl", "https://www.adobe.com"), Map.of());
        assertEquals(Map.of("url", "https://www.adobe.com"), content);
        assertSurvivesReducedExport(rule, content);
    }

    @Test
    void mapSurvivesReducedExport() throws Exception {
        final ComponentMapping rule = mappings.find("map");
        assertEquals("map", rule.getComponent());
        final Map<String, Object> content = rule.apply(
                Map.of("mobileDisplayAs", "fullMap"), Map.of());
        assertEquals(Map.of("displayAs", "fullMap"), content);
        assertSurvivesReducedExport(rule, content);
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

    private List<Map<String, Object>> exportProductDetail(final Object hideCart, final boolean hierarchical) {
        return exportProductDetail(hideCart, hierarchical, true);
    }

    private List<Map<String, Object>> exportProductDetail(final Object hideCart, final boolean hierarchical,
                                                         final Object customDetails) {
        final Page page = context.create().page("/content/s/en/product-" + hideCart + "-" + hierarchical
                + "-" + customDetails);
        context.create().resource(page.getPath() + "/jcr:content/root");
        final Map<String, Object> properties = authored("model", "product-detail", "filter", "product-detail",
                "sling:resourceType", SchemaConstants.RT_FRANKLIN_BLOCK,
                "folder", "/content/dam/s/products");
        if (customDetails != null) {
            properties.put("mobileappcustomproductdetails", customDetails);
        }
        if (hideCart != null) {
            properties.put("mobilehideaddtocart", hideCart);
        }
        context.create().resource(page.getPath() + "/jcr:content/root/product-detail", properties);
        final FormSchemaService schemas = mock(FormSchemaService.class);
        when(schemas.getSchema(any())).thenReturn(List.of());
        final ComponentTreeWalker walker = new ComponentTreeWalker(mock(ModelFactory.class),
                new UrlServiceImpl(), schemas, new StyleResolver(new String[0]), mappings);
        return walker.componentsOf(page, null, hierarchical, false, Set.of());
    }

    @Test
    void productDetailKeepsProductCardUnlessCustomMobileDetailsAreEnabled() {
        for (final Object customDetails : new Object[] {null, false, "false"}) {
            for (final Object hideCart : new Object[] {false, true}) {
                final List<Map<String, Object>> entries = exportProductDetail(hideCart, true, customDetails);
                assertEquals(1, entries.size());
                assertEquals("productCard", ((Map<?, ?>) entries.get(0).get("meta")).get("component"));
                assertEquals("contain", ((Map<?, ?>) entries.get(0).get("content")).get("imageResize"));
            }
        }
    }

    @Test
    void productDetailAcceptsStringCustomMobileDetailsToggle() {
        final List<Map<String, Object>> entries = exportProductDetail(false, true, "true");
        assertEquals(1, entries.size());
        assertEquals("productImage", ((Map<?, ?>) entries.get(0).get("meta")).get("component"));
    }

    @Test
    void productDetailExportsOnlyImageWithContentDefaults() {
        for (final Object hideCart : new Object[] {null, false, "false", true, "true"}) {
            final List<Map<String, Object>> entries = exportProductDetail(hideCart, true);
            assertEquals(1, entries.size());
            final Map<?, ?> meta = (Map<?, ?>) entries.get(0).get("meta");
            assertEquals("productImage", meta.get("component"));
            assertEquals(0, meta.get("order"));
            assertEquals(true, meta.get("id") instanceof String);
            assertEquals(false, entries.get(0).containsKey("styles"));
            assertEquals(Map.of("fullWidth", true, "imageResize", "contain"), entries.get(0).get("content"));
        }
    }

    @Test
    void productDetailComponentsSurviveFinalExporterFiltering() throws Exception {
        final Method filter = PageJsonExporterImpl.class.getDeclaredMethod("keepEnabledComponents", List.class);
        filter.setAccessible(true);
        for (final Object hideCart : new Object[] {false, true}) {
            final List<Map<String, Object>> entries = exportProductDetail(hideCart, true);
            assertEquals(entries, filter.invoke(null, entries));
        }
    }

    @Test
    void mobileHideCartDoesNotChangeLegacyProductDetailExport() {
        final List<Map<String, Object>> entries = exportProductDetail(true, false);
        assertEquals(1, entries.size());
        assertEquals("product-detail", ((Map<?, ?>) entries.get(0).get("meta")).get("component"));
    }
}

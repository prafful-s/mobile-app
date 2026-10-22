package com.edsMobileApp.core.services.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * A product card becomes a card, and reads the same Mobile Specific card fields a card item block
 * does - card type and image resize mode - while a product card with neither authored exports
 * exactly what it always did.
 */
class ProductCardMappingTest {

    private final ComponentMapping rule = ComponentMappings.load().find("product-card");

    private Map<String, Object> map(final Object... props) {
        assertNotNull(rule);
        final Map<String, Object> source = new java.util.LinkedHashMap<>();
        source.put("productName", "Jacket");
        for (int i = 0; i < props.length; i += 2) {
            source.put(String.valueOf(props[i]), props[i + 1]);
        }
        return rule.apply(source, Map.of());
    }

    @Test
    void withNothingAuthoredTheCardIsStackedAndContained() {
        final Map<String, Object> mapped = map();
        assertEquals("stacked", mapped.get("cardType"));
        assertEquals("contain", mapped.get("imageResize"));
    }

    @Test
    void anAuthoredCardTypeIsUsed() {
        assertEquals("overlay", map("mobilecardstyle", "overlay").get("cardType"));
        assertEquals("partial-overlay", map("mobilecardstyle", "partial-overlay").get("cardType"));
        assertEquals("image-text-one-line", map("mobilecardstyle", "image-text-one-line").get("cardType"));
        assertEquals("stacked", map("mobilecardstyle", "stacked").get("cardType"));
    }

    @Test
    void anAuthoredImageResizeModeWinsOverTheDefault() {
        assertEquals("cover", map("mobileimagestyle", "cover").get("imageResize"));
        assertEquals("contain", map("mobileimagestyle", "contain").get("imageResize"));
        assertEquals("contain", map("mobileimagestyle", "").get("imageResize"));
    }

    @Test
    void withNoShowButtonAuthoredTheProductCardCarriesNoButtonFields() {
        final Map<String, Object> mapped = map("buttonText", "Buy", "ctastyle", "button");
        assertEquals(null, mapped.get("showButton"));
        assertEquals("Buy", mapped.get("buttonLabel"));
        assertEquals("contained", mapped.get("buttonVariant"));
    }

    @Test
    void theProductCardShowsAButtonFromItsButtonTextWhenAsked() {
        final Map<String, Object> mapped = map("mobileshowbutton", "true", "buttonText", "Buy now",
                "ctastyle", "button-secondary");
        assertEquals(true, mapped.get("showButton"));
        assertEquals("Buy now", mapped.get("buttonLabel"));
        assertEquals("outlined", mapped.get("buttonVariant"));
        assertEquals("elevated", map("mobilebuttonvariant", "elevated", "ctastyle", "button")
                .get("buttonVariant"));
    }

    @Test
    void theAddBorderSwitchBecomesAnOutlineBorderOnTheCard() {
        assertNotNull(rule);
        final Map<String, Object> source = new java.util.LinkedHashMap<>();
        source.put("productName", "Jacket");
        source.put("addBorder", "true");
        assertEquals("solid 1px", rule.applyStyles(source, Map.of()).get("border"));
        source.put("addBorder", true);
        assertEquals("solid 1px", rule.applyStyles(source, Map.of()).get("border"));
        source.put("addBorder", "false");
        assertEquals(null, rule.applyStyles(source, Map.of()).get("border"), "no border when the switch is off");
        source.remove("addBorder");
        assertEquals(null, rule.applyStyles(source, Map.of()).get("border"));
        assertEquals(null, rule.applyStyles(source, Map.of()).get("showBorder"), "never the showBorder key the app cannot take");
    }
}

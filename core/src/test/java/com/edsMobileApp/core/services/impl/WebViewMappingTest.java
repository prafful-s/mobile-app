package com.edsMobileApp.core.services.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.Map;

import org.junit.jupiter.api.Test;

class WebViewMappingTest {

    private final ComponentMappings mappings = ComponentMappings.load();

    @Test
    void exportsOnlyTheMobileUrl() {
        final ComponentMapping rule = mappings.find("web-view");
        assertNotNull(rule);
        assertEquals("webView", rule.getComponent());
        assertEquals(Map.of("url", "https://www.adobe.com"), rule.apply(Map.of(
                "mobileUrl", "https://www.adobe.com",
                "mobileNickname", "Adobe",
                "url", "https://example.com"), Map.of()));
    }
}
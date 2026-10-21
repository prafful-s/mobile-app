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

    @Test
    void aVideoBlockMapsToTheVideoComponentWithTheCatalogDefaults() {
        final ComponentMapping rule = mappings.find("video");
        assertNotNull(rule);
        assertEquals("video", rule.getComponent());
        assertEquals(Map.of("url", "https://example.com/medicare-101.mp4", "autoplay", true, "loop", true,
                "muted", true, "controls", true), rule.apply(Map.of(
                "videoUrl", "https://example.com/medicare-101.mp4",
                "custom-class", "wh-video"), Map.of()));
    }

    @Test
    void anAuthoredVideoSwitchAndImageResizeAreCarriedThrough() {
        final Map<String, Object> mapped = mappings.find("video").apply(Map.of(
                "videoUrl", "https://example.com/v.mp4", "autoplay", "false", "muted", "false",
                "mobileimageresize", "contain"), Map.of());
        assertEquals(false, mapped.get("autoplay"));
        assertEquals(false, mapped.get("muted"));
        assertEquals(true, mapped.get("loop"));
        assertEquals("contain", mapped.get("imageResize"));
    }
}

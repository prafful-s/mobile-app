package com.edsMobileApp.core.services.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The mobile client cannot render SVG, so a DAM asset authored as one is emitted with a
 * {@code .png} extension instead - see {@link UrlServiceImpl#externalize}. Everything else about
 * externalisation has to keep behaving exactly as it did, which is most of what these assert.
 */
class UrlServiceImplTest {

    private static final String DOMAIN = "https://publish-p189874-e1977911.adobeaemcloud.com";

    private UrlServiceImpl urlService;

    @BeforeEach
    void setUp() {
        urlService = new UrlServiceImpl();
    }

    @Test
    void anSvgAssetIsEmittedAsPng() {
        assertEquals(DOMAIN + "/content/dam/secur-financial/en/images/visual-4.png",
                urlService.externalize("/content/dam/secur-financial/en/images/visual-4.svg"));
    }

    @Test
    void theSwapIsCaseInsensitiveAndOnlyTouchesTheExtension() {
        assertEquals(DOMAIN + "/content/dam/x/LOGO.png", urlService.externalize("/content/dam/x/LOGO.SVG"));
        // a path that merely contains "svg" keeps it - only a trailing extension is rewritten
        assertEquals(DOMAIN + "/content/dam/svg-assets/hero.png",
                urlService.externalize("/content/dam/svg-assets/hero.svg"));
        assertEquals(DOMAIN + "/content/dam/svg-assets/hero.jpg",
                urlService.externalize("/content/dam/svg-assets/hero.jpg"));
    }

    @Test
    void otherExtensionsAreUntouched() {
        assertEquals(DOMAIN + "/content/dam/x/a.png", urlService.externalize("/content/dam/x/a.png"));
        assertEquals(DOMAIN + "/content/dam/x/a.jpeg", urlService.externalize("/content/dam/x/a.jpeg"));
        assertEquals(DOMAIN + "/content/site/en/page", urlService.externalize("/content/site/en/page"));
    }

    /** We cannot know a .png is served on a host we do not control, so those are left alone. */
    @Test
    void anAbsoluteUrlIsLeftAlone() {
        assertEquals("https://example.com/logo.svg", urlService.externalize("https://example.com/logo.svg"));
        assertEquals("http://example.com/logo.svg", urlService.externalize("http://example.com/logo.svg"));
        assertEquals("//example.com/logo.svg", urlService.externalize("//example.com/logo.svg"));
        assertEquals("data:image/svg+xml;base64,AAAA", urlService.externalize("data:image/svg+xml;base64,AAAA"));
    }

    @Test
    void blankAndRelativeValuesKeepTodaysBehaviour() {
        assertEquals(null, urlService.externalize(null));
        assertEquals("", urlService.externalize(""));
        assertEquals("#", urlService.externalize("#"));
        assertEquals("relative/path.svg", urlService.externalize("relative/path.svg"));
    }

    @Test
    void aPathIsStillTrimmedAndPrefixed() {
        assertEquals(DOMAIN + "/content/dam/x/a.png", urlService.externalize("  /content/dam/x/a.svg  "));
    }
}

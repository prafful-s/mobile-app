package com.edsMobileApp.core;

import org.apache.sling.testing.mock.sling.ResourceResolverType;

import com.edsMobileApp.core.services.impl.FormSchemaServiceImpl;
import com.edsMobileApp.core.services.impl.PageJsonExporterImpl;
import com.edsMobileApp.core.services.impl.UrlServiceImpl;

import io.wcm.testing.mock.aem.junit5.AemContext;

/**
 * Shared fixture: loads the sample content and activates both services with predictable settings so
 * every test asserts against the same baseline.
 */
public final class ContentBridgeTestContext {

    public static final String APP_ROOT = "/content/contentbridge/us/en";
    public static final String PUBLISH_DOMAIN = "https://publish-p189874-e1977911.adobeaemcloud.com";

    private ContentBridgeTestContext() {
        // static helpers only
    }

    public static AemContext newContext() {
        return new AemContext(ResourceResolverType.JCR_MOCK);
    }

    public static void setUp(final AemContext context) {
        context.addModelsForPackage("com.edsMobileApp.core.models.impl");
        context.load().json("/com/edsMobileApp/core/content.json", "/content/contentbridge");

        context.registerInjectActivateService(new UrlServiceImpl());
        context.registerInjectActivateService(new FormSchemaServiceImpl());
        context.registerInjectActivateService(new PageJsonExporterImpl(),
                "maxDepth", 3);
    }
}

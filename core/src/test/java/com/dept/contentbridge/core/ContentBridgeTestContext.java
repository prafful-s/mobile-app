package com.dept.contentbridge.core;

import org.apache.sling.testing.mock.sling.ResourceResolverType;

import com.dept.contentbridge.core.services.impl.FormSchemaServiceImpl;
import com.dept.contentbridge.core.services.impl.PageJsonExporterImpl;
import com.dept.contentbridge.core.services.impl.UrlServiceImpl;

import io.wcm.testing.mock.aem.junit5.AemContext;

/**
 * Shared fixture: loads the sample content and activates both services with predictable settings so
 * every test asserts against the same baseline.
 */
public final class ContentBridgeTestContext {

    public static final String APP_ROOT = "/content/contentbridge/us/en";
    public static final String PUBLISH_DOMAIN = "https://publish.example.com";

    private ContentBridgeTestContext() {
        // static helpers only
    }

    public static AemContext newContext() {
        return new AemContext(ResourceResolverType.JCR_MOCK);
    }

    public static void setUp(final AemContext context) {
        context.addModelsForPackage("com.dept.contentbridge.core.models.impl");
        context.load().json("/com/dept/contentbridge/core/content.json", "/content/contentbridge");

        context.registerInjectActivateService(new UrlServiceImpl(),
                "publishDomain", PUBLISH_DOMAIN);
        context.registerInjectActivateService(new FormSchemaServiceImpl());
        context.registerInjectActivateService(new PageJsonExporterImpl(),
                "allowedRoots", new String[] {"/content/contentbridge"},
                "maxDepth", 3,
                "cacheMaxAge", 300);
    }
}

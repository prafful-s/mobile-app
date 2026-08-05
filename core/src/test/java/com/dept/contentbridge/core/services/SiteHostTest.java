package com.dept.contentbridge.core.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.List;
import java.util.Map;

import org.apache.sling.testing.mock.sling.ResourceResolverType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import com.dept.contentbridge.core.services.impl.FormSchemaServiceImpl;
import com.dept.contentbridge.core.services.impl.PageJsonExporterImpl;
import com.dept.contentbridge.core.services.impl.UrlServiceImpl;
import com.day.cq.wcm.api.Page;

import io.wcm.testing.mock.aem.junit5.AemContext;
import io.wcm.testing.mock.aem.junit5.AemContextExtension;

/**
 * Asset hosts come from each site's own placeholders page, so two sites in one repository can point
 * at different delivery domains.
 */
@ExtendWith(AemContextExtension.class)
class SiteHostTest {

    private static final String SITE = "/content/acme";
    private static final String HOME = SITE + "/us/en";
    private static final String AUTHOR_HOST = "https://author-p153659-e1620914.adobeaemcloud.com";
    private static final String PUBLISH_HOST = "https://publish-p153659-e1620914.adobeaemcloud.com";

    private final AemContext context = new AemContext(ResourceResolverType.JCR_MOCK);

    private void buildSite() {
        context.create().page(HOME, "", Map.of("jcr:title", "Home"));
        context.build().resource(HOME + "/jcr:content/root/image",
                "sling:resourceType", "core/franklin/components/image/v1/image",
                "image", "/content/dam/acme/hero.png",
                "link", "/content/acme/us/en/news");
        context.registerInjectActivateService(new UrlServiceImpl(), "publishDomain", "");
    }

    private PageJsonExporter exporter() {
        context.addModelsForPackage("com.dept.contentbridge.core.models.impl");
        context.registerInjectActivateService(new FormSchemaServiceImpl());
        context.registerInjectActivateService(new PageJsonExporterImpl(),
                "allowedRoots", new String[] {"/content"}, "cacheMaxAge", 0);
        final PageJsonExporter exporter = context.getService(PageJsonExporter.class);
        assertNotNull(exporter);
        return exporter;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> map(final Object value) {
        return (Map<String, Object>) value;
    }

    private String imageValue() {
        final Page home = context.pageManager().getPage(HOME);
        final Map<String, Object> screens = map(exporter().export(home).get("screens"));
        @SuppressWarnings("unchecked")
        final List<Map<String, Object>> content =
                (List<Map<String, Object>>) map(screens.get("Home")).get("content");
        return String.valueOf(map(content.get(0).get("content")).get("image"));
    }

    @Test
    @DisplayName("hostname on the placeholders page is rewritten from author to publish")
    void authorHostBecomesPublishHost() {
        buildSite();
        context.create().page(SITE + "/placeholders", "", Map.of("hostname", AUTHOR_HOST));

        assertEquals(PUBLISH_HOST + "/content/dam/acme/hero.png", imageValue());
    }

    @Test
    @DisplayName("a hostname already pointing at publish is left alone")
    void publishHostIsNotRewrittenTwice() {
        buildSite();
        context.create().page(SITE + "/placeholders", "", Map.of("hostname", PUBLISH_HOST));

        assertEquals(PUBLISH_HOST + "/content/dam/acme/hero.png", imageValue());
    }

    @Test
    @DisplayName("hostname stored as a key/value row is found too")
    void keyValueRowShape() {
        buildSite();
        context.create().page(SITE + "/placeholders", "", Map.of("jcr:title", "Placeholders"));
        context.build().resource(SITE + "/placeholders/jcr:content/data/row0",
                "key", "hostname", "value", AUTHOR_HOST);

        assertEquals(PUBLISH_HOST + "/content/dam/acme/hero.png", imageValue());
    }

    @Test
    @DisplayName("no placeholders page leaves DAM paths relative")
    void noPlaceholders() {
        buildSite();

        assertEquals("/content/dam/acme/hero.png", imageValue());
    }

    @Test
    @DisplayName("page links are prefixed too, not just assets")
    void pageLinksArePrefixed() {
        buildSite();
        context.create().page(SITE + "/placeholders", "", Map.of("hostname", AUTHOR_HOST));

        final Page home = context.pageManager().getPage(HOME);
        final Map<String, Object> screens = map(exporter().export(home).get("screens"));
        @SuppressWarnings("unchecked")
        final List<Map<String, Object>> content =
                (List<Map<String, Object>>) map(screens.get("Home")).get("content");

        assertEquals(PUBLISH_HOST + "/content/acme/us/en/news",
                map(content.get(0).get("content")).get("link"));
    }
}

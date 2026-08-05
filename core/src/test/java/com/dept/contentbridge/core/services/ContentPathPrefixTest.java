package com.dept.contentbridge.core.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.List;
import java.util.Map;

import org.apache.sling.testing.mock.sling.ResourceResolverType;
import org.junit.jupiter.api.BeforeEach;
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
 * Everything under {@code /content} gets the site's host, wherever it appears - a bare property, an
 * entry in a multi-value, or a link buried in authored rich text.
 */
@ExtendWith(AemContextExtension.class)
class ContentPathPrefixTest {

    private static final String SITE = "/content/acme";
    private static final String HOME = SITE + "/en";
    private static final String AUTHOR = "https://author-p1-e2.adobeaemcloud.com";
    private static final String PUBLISH = "https://publish-p1-e2.adobeaemcloud.com";

    private final AemContext context = new AemContext(ResourceResolverType.JCR_MOCK);

    private PageJsonExporter exporter;

    @BeforeEach
    void setUp() {
        context.create().page(HOME, "", Map.of("jcr:title", "Home"));
        context.create().page(SITE + "/placeholders", "", Map.of("hostname", AUTHOR));

        context.addModelsForPackage("com.dept.contentbridge.core.models.impl");
        context.registerInjectActivateService(new UrlServiceImpl(), "publishDomain", "");
        context.registerInjectActivateService(new FormSchemaServiceImpl());
        context.registerInjectActivateService(new PageJsonExporterImpl(),
                "allowedRoots", new String[] {"/content"}, "cacheMaxAge", 0);

        exporter = context.getService(PageJsonExporter.class);
        assertNotNull(exporter);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> map(final Object value) {
        return (Map<String, Object>) value;
    }

    private Map<String, Object> exportFirstComponent(final Object... properties) {
        final Object[] all = new Object[properties.length + 2];
        all[0] = "sling:resourceType";
        all[1] = "core/franklin/components/block/v1/block";
        System.arraycopy(properties, 0, all, 2, properties.length);
        context.build().resource(HOME + "/jcr:content/root/block", all);

        final Page home = context.pageManager().getPage(HOME);
        final Map<String, Object> screens = map(exporter.export(home).get("screens"));
        @SuppressWarnings("unchecked")
        final List<Map<String, Object>> content =
                (List<Map<String, Object>>) map(screens.get("Home")).get("content");
        return map(content.get(0).get("content"));
    }

    @Test
    @DisplayName("an asset path is prefixed")
    void assetPath() {
        assertEquals(PUBLISH + "/content/dam/acme/hero.png",
                exportFirstComponent("model", "hero", "image", "/content/dam/acme/hero.png").get("image"));
    }

    @Test
    @DisplayName("a page path is prefixed")
    void pagePath() {
        assertEquals(PUBLISH + "/content/acme/en/plans.html",
                exportFirstComponent("model", "hero", "link", "/content/acme/en/plans.html").get("link"));
    }

    @Test
    @DisplayName("links inside authored rich text are prefixed")
    void linksInsideMarkup() {
        final String authored = "<h1>Plans</h1>\n<p><a href=\"/content/acme/en/plans.html\" "
                + "title=\"Plans\">See plans</a></p>";

        assertEquals("<h1>Plans</h1>\n<p><a href=\"" + PUBLISH + "/content/acme/en/plans.html\" "
                        + "title=\"Plans\">See plans</a></p>",
                exportFirstComponent("model", "hero", "text", authored).get("text"));
    }

    @Test
    @DisplayName("an img src inside rich text is prefixed")
    void imgSrcInsideMarkup() {
        final String authored = "<p><img src=\"/content/dam/acme/inline.png\"></p>";

        assertEquals("<p><img src=\"" + PUBLISH + "/content/dam/acme/inline.png\"></p>",
                exportFirstComponent("model", "hero", "text", authored).get("text"));
    }

    @Test
    @DisplayName("authored markup is otherwise byte-for-byte unchanged")
    void markupIsNotReformatted() {
        // Entities, self-closing tags and irregular spacing must survive: re-serialising authored
        // HTML through a parser would silently rewrite what the author typed.
        final String authored = "<p>A &amp; B &nbsp; <br/> <em>x</em></p>";

        assertEquals(authored, exportFirstComponent("model", "hero", "text", authored).get("text"));
    }

    @Test
    @DisplayName("external links and non-paths are untouched")
    void externalAndNonPaths() {
        final Map<String, Object> content = exportFirstComponent("model", "hero",
                "link", "https://example.com/page",
                "anchor", "#top",
                "colour", "#005971",
                "label", "Learn more");

        assertEquals("https://example.com/page", content.get("link"));
        assertEquals("#top", content.get("anchor"));
        assertEquals("#005971", content.get("colour"));
        assertEquals("Learn more", content.get("label"));
    }

    @Test
    @DisplayName("multi-value properties are prefixed entry by entry")
    void multiValue() {
        context.build().resource(HOME + "/jcr:content/root/block",
                "sling:resourceType", "core/franklin/components/block/v1/block",
                "model", "gallery",
                "images", new String[] {"/content/dam/acme/a.png", "https://cdn.example.com/b.png"});

        final Page home = context.pageManager().getPage(HOME);
        final Map<String, Object> screens = map(exporter.export(home).get("screens"));
        @SuppressWarnings("unchecked")
        final List<Map<String, Object>> content =
                (List<Map<String, Object>>) map(screens.get("Home")).get("content");

        assertEquals(List.of(PUBLISH + "/content/dam/acme/a.png", "https://cdn.example.com/b.png"),
                map(content.get(0).get("content")).get("images"));
    }
}

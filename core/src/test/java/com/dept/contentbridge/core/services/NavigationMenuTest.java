package com.dept.contentbridge.core.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.apache.sling.api.resource.ModifiableValueMap;
import org.apache.sling.api.resource.Resource;
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
 * The menu is read from the authored navigation page, not by listing child pages.
 */
@ExtendWith(AemContextExtension.class)
class NavigationMenuTest {

    private static final String SITE = "/content/acme/us/en";

    private final AemContext context = new AemContext(ResourceResolverType.JCR_MOCK);

    /**
     * Builds a site whose nav is an authored link list, mirroring how Edge Delivery stores it: an
     * HTML {@code <ul>} inside a text component, several sections deep.
     */
    private void buildSite(final String navMarkup) {
        context.create().page(SITE, "", Map.of("jcr:title", "Home"));
        context.create().page(SITE + "/campaigns", "", Map.of("jcr:title", "Campaigns"));
        context.create().page(SITE + "/news", "", Map.of("jcr:title", "News"));
        context.create().page(SITE + "/about-us", "", Map.of("jcr:title", "About Us"));
        context.create().page(SITE + "/unlisted", "", Map.of("jcr:title", "Unlisted"));
        context.create().page(SITE + "/footer", "", Map.of("jcr:title", "Footer"));

        context.create().page(SITE + "/nav", "", Map.of("jcr:title", "Header"));
        context.build().resource(SITE + "/nav/jcr:content/root",
                "sling:resourceType", "core/franklin/components/root/v1/root");
        context.build().resource(SITE + "/nav/jcr:content/root/section_logo",
                "sling:resourceType", "core/franklin/components/section/v1/section");
        context.build().resource(SITE + "/nav/jcr:content/root/section_logo/image",
                "sling:resourceType", "core/franklin/components/image/v1/image",
                "image", "/content/dam/acme/logo.png",
                "image-link", SITE);
        context.build().resource(SITE + "/nav/jcr:content/root/section_menu",
                "sling:resourceType", "core/franklin/components/section/v1/section");
        context.build().resource(SITE + "/nav/jcr:content/root/section_menu/text",
                "sling:resourceType", "core/franklin/components/text/v1/text",
                "text", navMarkup);

        context.registerInjectActivateService(new UrlServiceImpl(), "publishDomain", "");
    }

    private PageJsonExporter exporter(final Object... config) {
        context.addModelsForPackage("com.dept.contentbridge.core.models.impl");
        context.registerInjectActivateService(new FormSchemaServiceImpl());
        context.registerInjectActivateService(new PageJsonExporterImpl(), config);
        final PageJsonExporter exporter = context.getService(PageJsonExporter.class);
        assertNotNull(exporter);
        return exporter;
    }

    private PageJsonExporter defaultExporter() {
        return exporter("allowedRoots", new String[] {"/content"}, "cacheMaxAge", 0);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> map(final Object value) {
        return (Map<String, Object>) value;
    }

    private Page home() {
        return context.pageManager().getPage(SITE);
    }

    @SuppressWarnings("unchecked")
    private List<String> menu(final Map<String, Object> app) {
        return (List<String>) map(map(app.get("meta")).get("navigation")).get("menu");
    }

    private static final String NAV = "<ul>"
            + "<li><a title=\"Adventures\" href=\"" + SITE + "/campaigns.html\">Campaigns</a></li>"
            + "<li><a href=\"" + SITE + "/news.html\">News</a></li>"
            + "<li><a href=\"" + SITE + "/about-us.html\">About Us</a></li>"
            + "</ul>";

    @Test
    @DisplayName("menu follows the nav page order, not JCR child order")
    void menuComesFromNav() {
        buildSite(NAV);
        final Map<String, Object> app = defaultExporter().export(home());

        assertEquals(List.of("Home", "Campaigns", "News", "About Us"), menu(app));
    }

    @Test
    @DisplayName("a page off the nav is still a screen; furniture is not")
    void unlistedPagesAreStillScreens() {
        buildSite(NAV);
        final Map<String, Object> screens = map(defaultExporter().export(home()).get("screens"));

        assertTrue(screens.containsKey("Unlisted"),
                "the nav decides the menu, not what the app can render: " + screens.keySet());
        assertFalse(screens.containsKey("Header"), "the nav page is furniture, not a destination");
        assertFalse(screens.containsKey("Footer"), "the footer page is furniture, not a destination");
    }

    @Test
    @DisplayName("every menu entry has a screen behind it")
    void everyMenuEntryIsRenderable() {
        buildSite(NAV);
        final Map<String, Object> app = defaultExporter().export(home());
        final Map<String, Object> screens = map(app.get("screens"));

        // The menu is a subset of the screens, not equal to them: a menu entry with no screen is a
        // dead link, but a screen with no menu entry is just a page reached some other way.
        for (final String entry : menu(app)) {
            assertTrue(screens.containsKey(entry),
                    "menu entry '" + entry + "' has no screen: " + screens.keySet());
        }
        assertTrue(screens.size() > menu(app).size(),
                "the fixture has an unlisted page, so screens must outnumber the menu");
    }

    @Test
    @DisplayName("external, mailto and anchor links are skipped")
    void nonPageLinksAreSkipped() {
        buildSite("<ul>"
                + "<li><a href=\"https://example.com\">External</a></li>"
                + "<li><a href=\"mailto:a@b.com\">Mail</a></li>"
                + "<li><a href=\"#top\">Anchor</a></li>"
                + "<li><a href=\"" + SITE + "/news.html\">News</a></li>"
                + "</ul>");

        assertEquals(List.of("Home", "News"), menu(defaultExporter().export(home())));
    }

    @Test
    @DisplayName("nested markup inside the anchor is handled - the reason for jsoup over a regex")
    void nestedMarkupInAnchors() {
        buildSite("<ul><li><a href=\"" + SITE + "/news.html\"><strong>News</strong></a></li></ul>");

        assertTrue(menu(defaultExporter().export(home())).contains("News"));
    }

    @Test
    @DisplayName("hrefs without .html, and site-relative hrefs, both resolve")
    void hrefVariants() {
        buildSite("<ul>"
                + "<li><a href=\"" + SITE + "/campaigns\">Campaigns</a></li>"
                + "<li><a href=\"news.html\">News</a></li>"
                + "</ul>");

        assertEquals(List.of("Home", "Campaigns", "News"), menu(defaultExporter().export(home())));
    }

    @Test
    @DisplayName("a repeated link appears once")
    void duplicatesCollapse() {
        buildSite("<ul>"
                + "<li><a href=\"" + SITE + "/news.html\">News</a></li>"
                + "<li><a href=\"" + SITE + "/news\">News again</a></li>"
                + "</ul>");

        assertEquals(List.of("Home", "News"), menu(defaultExporter().export(home())));
    }

    @Test
    @DisplayName("includeHomeScreen=false leaves the home page out when the nav omits it")
    void homeScreenCanBeSuppressed() {
        buildSite(NAV);
        final PageJsonExporter exporter = exporter(
                "allowedRoots", new String[] {"/content"},
                "cacheMaxAge", 0,
                "includeHomeScreen", false);

        assertEquals(List.of("Campaigns", "News", "About Us"), menu(exporter.export(home())));
    }

    @Test
    @DisplayName("a site with no nav page falls back to listing child pages")
    void fallbackWithoutNavPage() {
        context.create().page(SITE, "", Map.of("jcr:title", "Home"));
        context.create().page(SITE + "/one", "", Map.of("jcr:title", "One"));
        context.create().page(SITE + "/two", "", Map.of("jcr:title", "Two"));
        context.registerInjectActivateService(new UrlServiceImpl(), "publishDomain", "");

        assertEquals(List.of("Home", "One", "Two"), menu(defaultExporter().export(home())));
    }

    @Test
    @DisplayName("a nav page with no resolvable links falls back rather than returning nothing")
    void fallbackWhenNavHasNoUsableLinks() {
        buildSite("<p>Coming soon</p>");

        final List<String> menu = menu(defaultExporter().export(home()));
        assertTrue(menu.contains("Campaigns"), "expected the child-page fallback");
        assertTrue(menu.size() > 1);
    }

    @Test
    @DisplayName("links authored against another site root are re-based onto this one")
    void linksFromARelocatedSiteStillResolve() {
        // Exactly the page-0.1 situation: the package installs under /content/acme/us/en but every
        // href inside it still points at the path the content was authored on.
        buildSite("<ul>"
                + "<li><a href=\"/content/bodea/language-masters/en/campaigns.html\">Campaigns</a></li>"
                + "<li><a href=\"/content/bodea/language-masters/en/news.html\">News</a></li>"
                + "</ul>");

        assertEquals(List.of("Home", "Campaigns", "News"), menu(defaultExporter().export(home())));
    }

    @Test
    @DisplayName("navPath on the site root overrides the configured page names")
    void explicitNavPathWins() {
        buildSite(NAV);
        context.create().page(SITE + "/custom-nav", "", Map.of("jcr:title", "Custom Nav"));
        context.build().resource(SITE + "/custom-nav/jcr:content/root/text",
                "sling:resourceType", "core/franklin/components/text/v1/text",
                "text", "<ul><li><a href=\"" + SITE + "/about-us.html\">About Us</a></li></ul>");
        // Set the property on the existing jcr:content rather than trying to re-create that node.
        final Resource siteContent = context.resourceResolver().getResource(SITE + "/jcr:content");
        assertNotNull(siteContent);
        siteContent.adaptTo(ModifiableValueMap.class).put("navPath", SITE + "/custom-nav");

        assertEquals(List.of("Home", "About Us"), menu(defaultExporter().export(home())));
    }
}

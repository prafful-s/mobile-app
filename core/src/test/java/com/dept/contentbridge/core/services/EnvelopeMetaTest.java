package com.dept.contentbridge.core.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.apache.sling.api.resource.ModifiableValueMap;

import org.apache.sling.testing.mock.sling.ResourceResolverType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
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
 * The v2 envelope meta: derived where AEM knows the answer, authored where it does not.
 */
@ExtendWith(AemContextExtension.class)
class EnvelopeMetaTest {

    private static final String SITE = "/content/acme";
    private static final String HOME = SITE + "/us/en";

    private final AemContext context = new AemContext(ResourceResolverType.JCR_MOCK);

    private PageJsonExporter exporter;

    @BeforeEach
    void setUp() {
        context.create().page(HOME, "", Map.of("jcr:title", "Home", "jcr:language", "en_US"));
        context.create().page(HOME + "/offers", "", Map.of("jcr:title", "Offers"));
        context.create().page(HOME + "/documents", "", Map.of("jcr:title", "Documents"));

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

    private Map<String, Object> export(final boolean v2) {
        final Page home = context.pageManager().getPage(HOME);
        return v2 ? exporter.export(home, ExportOptions.of(false))
                : exporter.export(home);
    }

    private Map<String, Object> appMeta() {
        return map(export(true).get("meta"));
    }

    private Map<String, Object> screenMeta(final String key) {
        return map(map(map(export(true).get("screens")).get(key)).get("meta"));
    }

    @Test
    @DisplayName("locale comes from the page's language, not from parsing the path")
    void localeIsDerived() {
        assertEquals("en-us", appMeta().get("locale"));
    }

    @Test
    @DisplayName("a page with a language but no country still yields a usable tag")
    void localeWithoutCountry() {
        context.create().page("/content/beta/fr", "", Map.of("jcr:title", "Accueil", "jcr:language", "fr"));

        final Page page = context.pageManager().getPage("/content/beta/fr");
        assertEquals("fr", map(exporter.export(page,
                ExportOptions.of(false)).get("meta")).get("locale"));
    }

    @Test
    @DisplayName("app fields an author sets on the placeholders page are carried through")
    void authoredAppFields() {
        context.create().page(SITE + "/placeholders", "", Map.of(
                "brand", "Acme Financial",
                "vertical", "FSI",
                "template", "mobile-v2/generic-fsi-v4",
                "description", "Retail banking app",
                "enableTargetRec", "true"));

        final Map<String, Object> meta = appMeta();
        assertEquals("Acme Financial", meta.get("brand"));
        assertEquals("FSI", meta.get("vertical"));
        assertEquals("mobile-v2/generic-fsi-v4", meta.get("template"));
        assertEquals("Retail banking app", meta.get("description"));
        assertEquals(Boolean.TRUE, meta.get("enableTargetRec"),
                "a sheet stores flags as text; they are emitted as real booleans");
    }

    @Test
    @DisplayName("fields the client switches on are always present, authored or not")
    void defaultsAreEmitted() {
        final Map<String, Object> meta = appMeta();

        assertEquals("optional", meta.get("authentication"));
        assertEquals("mainNavigation", meta.get("displayInbox"));
        assertEquals(Boolean.FALSE, meta.get("enableTargetRec"));
        assertEquals(Boolean.FALSE, meta.get("enableBrandingTargeting"));
        assertEquals("", meta.get("brandingMboxName"));
    }

    @Test
    @DisplayName("rules and modules are present but empty, so the shape is fixed")
    void rulesAndModules() {
        final Map<String, Object> app = export(true);

        assertEquals(List.of(), app.get("rules"));
        assertEquals(List.of(), app.get("modules"));
    }

    @Test
    @DisplayName("the right icon block is always present, defaults to the reference cart shortcut")
    void rightIconIsAlwaysPresent() {
        // The shape is fixed, so the key exists either way. Unconfigured falls back to the
        // LUMA MOBILE reference default rather than blank, so the app bar is never empty.
        final Map<String, Object> unset = map(appMeta().get("rightIcon"));
        assertEquals("shopping-cart", unset.get("icon"));
        assertEquals("shopping-cart", unset.get("name"));
        assertEquals("cart", unset.get("target"));

        context.create().page(SITE + "/placeholders", "", Map.of(
                "rightIcon", "search-icon", "rightIconTarget", "search"));

        final Map<String, Object> icon = map(appMeta().get("rightIcon"));
        assertEquals("search-icon", icon.get("icon"));
        assertEquals("search-icon", icon.get("name"));
        assertEquals("search", icon.get("target"));
    }

    @Test
    @DisplayName("every app key the target defines is present, even on a site that configures none")
    void appShapeIsFixed() {
        final Map<String, Object> meta = appMeta();

        for (final String key : List.of("id", "docs", "type", "brand", "title", "locale", "useCart",
                "template", "vertical", "rightIcon", "thumbnail", "navigation", "description",
                "displayInbox", "authentication", "enableTargetRec", "brandingMboxName",
                "targetRecMboxName", "enableBrandingTargeting")) {
            assertTrue(meta.containsKey(key), "meta is missing " + key + ": " + meta.keySet());
        }
        assertEquals("", meta.get("docs"), "blank rather than absent");
        assertEquals("", meta.get("brand"), "this fixture sets no brand and no theme");
    }

    @Test
    @DisplayName("every screen key the target defines is present, even on a page that configures none")
    void screenShapeIsFixed() {
        final Map<String, Object> meta = screenMeta("en");

        for (final String key : List.of("icon", "iconInactive", "name", "event", "title", "preset",
                "template", "customEvent", "isRootScreen", "navigationOrder", "includeInNavigation")) {
            assertTrue(meta.containsKey(key), "screen meta is missing " + key + ": " + meta.keySet());
        }
        // icon/iconInactive are the page's own mobileIcon/mobileIconInactive, verbatim - empty
        // when the page authors neither, not a guessed fallback.
        assertEquals("", meta.get("icon"), "no mobileIcon authored");
        assertEquals("", meta.get("iconInactive"));
        assertEquals("", meta.get("event"));
        assertEquals("home", meta.get("preset"), "content default when no role or authored preset");
        assertEquals("", meta.get("customEvent"));
        assertFalse(meta.containsKey("thumbnail"), "not part of the reference contract");
    }

    @Test
    @DisplayName("navigation order and membership are derived from the menu, not authored twice")
    void navigationIsDerived() {
        @SuppressWarnings("unchecked")
        final List<String> menu = (List<String>) map(appMeta().get("navigation")).get("menu");
        assertTrue(menu.size() > 1, "fixture has several screens: " + menu);

        final Map<String, Object> first = screenMeta(menu.get(0));
        assertEquals(0, first.get("navigationOrder"));
        assertEquals(Boolean.TRUE, first.get("includeInNavigation"));

        final Map<String, Object> second = screenMeta(menu.get(1));
        assertEquals(1, second.get("navigationOrder"));
    }

    @Test
    @DisplayName("the site root is flagged as the root screen and others are not")
    void rootScreenIsFlagged() {
        assertEquals(Boolean.TRUE, screenMeta("en").get("isRootScreen"));
        assertEquals(Boolean.FALSE, screenMeta("offers").get("isRootScreen"));
    }

    @Test
    @DisplayName("a screen off the menu says so, with a null order rather than a fake one")
    void screenOutsideTheMenu() {
        context.create().page(SITE + "/placeholders", "", Map.of("jcr:title", "Placeholders"));

        // Exporting a single page that is not in the menu still has to produce valid screen meta.
        final Page orphan = context.pageManager().getPage(HOME + "/documents");
        final Map<String, Object> screens = map(exporter.export(orphan,
                ExportOptions.of(false)).get("screens"));
        final Map<String, Object> meta = map(map(screens.get("documents")).get("meta"));

        if (Boolean.FALSE.equals(meta.get("includeInNavigation"))) {
            assertNull(meta.get("navigationOrder"), "no order is honest; 0 would be a lie");
        }
    }

    @Test
    @DisplayName("screen template defaults, and an authored one wins")
    void screenTemplate() {
        assertEquals("FreeformScreen", screenMeta("en").get("template"));

        context.create().page(SITE + "/us/en/atm", "",
                Map.of("jcr:title", "ATM", "template", "MapScreen", "mobileIcon", "atm"));

        assertEquals("MapScreen", screenMeta("atm").get("template"));
        assertEquals("atm", screenMeta("atm").get("icon"), "the page's own mobileIcon property");
    }

    @Test
    @DisplayName("the Franklin page template maps to the DSN FreeformScreen, not \"Page\"")
    void templateIgnoresCqTemplate() {
        context.create().page(SITE + "/us/en/map", "",
                Map.of("jcr:title", "Map",
                        "cq:template", "/libs/core/franklin/templates/page"));

        assertEquals("FreeformScreen", screenMeta("map").get("template"),
                "the app only understands DSN screen templates, so cq:template is not surfaced");
    }

    @Test
    @DisplayName("an authored template property still wins over the default")
    void authoredTemplateBeatsCqTemplate() {
        context.create().page(SITE + "/us/en/custom", "",
                Map.of("jcr:title", "Custom",
                        "template", "CustomScreen",
                        "cq:template", "/libs/core/franklin/templates/page"));

        assertEquals("CustomScreen", screenMeta("custom").get("template"));
    }

    @Test
    @DisplayName("brand is derived from the theme property when not in placeholders")
    void brandFallbackFromTheme() {
        context.resourceResolver().getResource(HOME + "/jcr:content")
                .adaptTo(ModifiableValueMap.class).put("theme", "acme-theme");

        assertEquals("Acme", appMeta().get("brand"));
    }

    @Test
    @DisplayName("placeholders brand wins over the theme fallback")
    void placeholdersBrandTakesPrecedence() {
        context.create().page(SITE + "/placeholders", "", Map.of("brand", "Acme Official"));
        context.resourceResolver().getResource(HOME + "/jcr:content")
                .adaptTo(ModifiableValueMap.class).put("theme", "acme-theme");

        assertEquals("Acme Official", appMeta().get("brand"));
    }

    /**
     * Brand/template/vertical config defaults are per-site ({@code sites/<site>/defaults.json}),
     * not a shared OSGi config - so these three tests run against a dedicated
     * {@code /content/acme-branded} site (backed by the test fixture
     * {@code core/src/test/resources/contentbridge/sites/acme-branded/defaults.json}) rather than
     * the shared {@code SITE}/{@code HOME} constant, so the fixture's values do not leak into the
     * other tests in this file that assert on an unbranded site.
     */
    private static final String BRANDED_SITE = "/content/acme-branded";
    private static final String BRANDED_HOME = BRANDED_SITE + "/us/en";

    private Map<String, Object> exportBrandedSite() {
        context.create().page(BRANDED_HOME, "", Map.of("jcr:title", "Home", "jcr:language", "en_US"));
        final Page home = context.pageManager().getPage(BRANDED_HOME);
        return map(exporter.export(home, ExportOptions.of(false)).get("meta"));
    }

    @Test
    @DisplayName("brand falls back to the site-level defaults.json when placeholders set none")
    void brandFromConfigDefault() {
        final Map<String, Object> meta = exportBrandedSite();

        assertEquals("SecurFinancial", meta.get("brand"), "site defaults fill in when nothing is authored");
    }

    @Test
    @DisplayName("placeholders brand wins over the site-level defaults.json")
    void placeholdersBrandWinsOverConfig() {
        context.create().page(BRANDED_SITE + "/placeholders", "", Map.of("brand", "Authored Brand"));

        final Map<String, Object> meta = exportBrandedSite();

        assertEquals("Authored Brand", meta.get("brand"), "an authored placeholder still wins");
    }

    @Test
    @DisplayName("template and vertical fall back to the site-level defaults.json")
    void templateAndVerticalFromConfigDefault() {
        final Map<String, Object> meta = exportBrandedSite();

        assertEquals("mobile-v2/generic-fsi-v4", meta.get("template"), "site defaults fill in when unauthored");
        assertEquals("FSI", meta.get("vertical"), "site defaults fill in when unauthored");
    }

    @Test
    @DisplayName("description falls back to jcr:description on the root page when not in placeholders")
    void descriptionFallbackFromPage() {
        context.resourceResolver().getResource(HOME + "/jcr:content")
                .adaptTo(ModifiableValueMap.class).put("jcr:description", "Healthcare app for members");

        assertEquals("Healthcare app for members", appMeta().get("description"));
    }

    @Test
    @DisplayName("screen preset is derived from the screen's navigation role")
    void screenPresetFromNavigationRole() {
        context.create().page(HOME + "/my-login", "", Map.of("jcr:title", "Login"));
        context.create().page(HOME + "/my-register", "", Map.of("jcr:title", "Register"));
        context.resourceResolver().getResource(HOME + "/jcr:content")
                .adaptTo(ModifiableValueMap.class)
                .putAll(Map.of("loginScreen", "my-login", "registrationScreen", "my-register"));

        assertEquals("login", screenMeta("my-login").get("preset"));
        assertEquals("registration", screenMeta("my-register").get("preset"));
        assertEquals("home", screenMeta("offers").get("preset"), "no role falls back to the content default");
    }

    @Test
    @DisplayName("an explicit preset property wins over the navigation role")
    void explicitPresetWinsOverRole() {
        context.create().page(HOME + "/my-login", "", Map.of(
                "jcr:title", "Login",
                "preset", "custom"));
        context.resourceResolver().getResource(HOME + "/jcr:content")
                .adaptTo(ModifiableValueMap.class)
                .put("loginScreen", "my-login");

        assertEquals("custom", screenMeta("my-login").get("preset"));
    }
}

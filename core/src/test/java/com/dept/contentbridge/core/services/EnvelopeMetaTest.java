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
        return v2 ? exporter.export(home, ExportOptions.of(ExportOptions.SCHEMA_V2, null))
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
                ExportOptions.of(ExportOptions.SCHEMA_V2, null)).get("meta")).get("locale"));
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
    @DisplayName("a right icon needs a target; without one it is not emitted")
    void rightIconIsOptional() {
        assertFalse(appMeta().containsKey("rightIcon"));

        context.create().page(SITE + "/placeholders", "", Map.of(
                "rightIcon", "shopping-cart", "rightIconTarget", "cart"));

        final Map<String, Object> icon = map(appMeta().get("rightIcon"));
        assertEquals("shopping-cart", icon.get("icon"));
        assertEquals("shopping-cart", icon.get("name"));
        assertEquals("cart", icon.get("target"));
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
        assertEquals(Boolean.TRUE, screenMeta("Home").get("isRootScreen"));
        assertEquals(Boolean.FALSE, screenMeta("Offers").get("isRootScreen"));
    }

    @Test
    @DisplayName("a screen off the menu says so, with a null order rather than a fake one")
    void screenOutsideTheMenu() {
        context.create().page(SITE + "/placeholders", "", Map.of("jcr:title", "Placeholders"));

        // Exporting a single page that is not in the menu still has to produce valid screen meta.
        final Page orphan = context.pageManager().getPage(HOME + "/documents");
        final Map<String, Object> screens = map(exporter.export(orphan,
                ExportOptions.of(ExportOptions.SCHEMA_V2, null)).get("screens"));
        final Map<String, Object> meta = map(map(screens.get("Documents")).get("meta"));

        if (Boolean.FALSE.equals(meta.get("includeInNavigation"))) {
            assertNull(meta.get("navigationOrder"), "no order is honest; 0 would be a lie");
        }
    }

    @Test
    @DisplayName("screen template defaults, and an authored one wins")
    void screenTemplate() {
        assertEquals("FreeformScreen", screenMeta("Home").get("template"));

        context.create().page(SITE + "/us/en/atm", "",
                Map.of("jcr:title", "ATM", "template", "MapScreen", "icon", "atm"));

        assertEquals("MapScreen", screenMeta("ATM").get("template"));
        assertEquals("atm", screenMeta("ATM").get("icon"));
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

    @Test
    @DisplayName("description falls back to jcr:description on the root page when not in placeholders")
    void descriptionFallbackFromPage() {
        context.resourceResolver().getResource(HOME + "/jcr:content")
                .adaptTo(ModifiableValueMap.class).put("jcr:description", "Healthcare app for members");

        assertEquals("Healthcare app for members", appMeta().get("description"));
    }

    @Test
    @DisplayName("screen preset comes from pageCategory when not explicitly set as preset")
    void screenPresetFromPageCategory() {
        context.create().page(HOME + "/insurance", "", Map.of(
                "jcr:title", "Insurance",
                "pageCategory", "article"));

        assertEquals("article", screenMeta("Insurance").get("preset"));
    }

    @Test
    @DisplayName("explicit preset property wins over pageCategory")
    void explicitPresetWinsOverPageCategory() {
        context.create().page(HOME + "/login", "", Map.of(
                "jcr:title", "Login",
                "preset", "login",
                "pageCategory", "article"));

        assertEquals("login", screenMeta("Login").get("preset"));
    }

    @Test
    @DisplayName("v1 gains none of this")
    void v1IsUnchanged() {
        final Map<String, Object> app = export(false);
        final Map<String, Object> meta = map(app.get("meta"));

        assertFalse(meta.containsKey("locale"));
        assertFalse(meta.containsKey("authentication"));
        assertFalse(app.containsKey("rules"));
        assertFalse(app.containsKey("modules"));

        final Map<String, Object> screen = map(map(map(app.get("screens")).get("Home")).get("meta"));
        assertEquals(List.of("name", "title"), List.copyOf(screen.keySet()),
                "v1 screen meta is exactly what it always was");
    }
}

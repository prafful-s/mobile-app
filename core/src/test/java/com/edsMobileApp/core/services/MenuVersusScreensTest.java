package com.edsMobileApp.core.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.apache.sling.testing.mock.sling.ResourceResolverType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import com.edsMobileApp.core.services.impl.FormSchemaServiceImpl;
import com.edsMobileApp.core.services.impl.PageJsonExporterImpl;
import com.edsMobileApp.core.services.impl.UrlServiceImpl;
import com.day.cq.wcm.api.Page;

import io.wcm.testing.mock.aem.junit5.AemContext;
import io.wcm.testing.mock.aem.junit5.AemContextExtension;

/**
 * The menu and the screen list answer two different questions.
 *
 * <p>{@code meta.navigation.menu} is what an author curated on the nav fragment. {@code screens} is
 * every page the app can reach. A plan detail page opened from a card is a screen and not a menu
 * entry; exporting only the menu would leave it unrenderable.</p>
 */
@ExtendWith(AemContextExtension.class)
class MenuVersusScreensTest {

    private static final String SITE = "/content/acme/en";

    private final AemContext context = new AemContext(ResourceResolverType.JCR_MOCK);

    private PageJsonExporter exporter;

    @BeforeEach
    void setUp() {
        context.create().page(SITE, "", Map.of("jcr:title", "Home"));

        // Two pages the nav links to...
        context.create().page(SITE + "/plans", "", Map.of("jcr:title", "Plans"));
        context.create().page(SITE + "/contact", "", Map.of("jcr:title", "Contact"));
        // ...one it does not, reached from a card on the plans page...
        context.create().page(SITE + "/plan-detail", "", Map.of("jcr:title", "Plan Detail"));
        // ...and the furniture, which is neither.
        context.create().page(SITE + "/nav", "", Map.of("jcr:title", "Nav"));
        context.create().page(SITE + "/footer", "", Map.of("jcr:title", "Footer"));

        context.build().resource(SITE + "/nav/jcr:content/root/nav",
                "sling:resourceType", "core/franklin/components/block/v1/block",
                "model", "text",
                "text", "<ul>"
                        + "<li><a href=\"/content/acme/en/plans\">Plans</a></li>"
                        + "<li><a href=\"/content/acme/en/contact\">Contact</a></li>"
                        + "</ul>");

        context.addModelsForPackage("com.edsMobileApp.core.models.impl");
        context.registerInjectActivateService(new UrlServiceImpl());
        context.registerInjectActivateService(new FormSchemaServiceImpl());
        context.registerInjectActivateService(new PageJsonExporterImpl());

        exporter = context.getService(PageJsonExporter.class);
        assertNotNull(exporter);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> map(final Object value) {
        return (Map<String, Object>) value;
    }

    private Map<String, Object> app() {
        return exporter.export(context.pageManager().getPage(SITE),
                ExportOptions.of(false));
    }

    @SuppressWarnings("unchecked")
    private List<String> menu() {
        return (List<String>) map(map(app().get("meta")).get("navigation")).get("menu");
    }

    private Map<String, Object> screenMeta(final String key) {
        return map(map(map(app().get("screens")).get(key)).get("meta"));
    }

    @Test
    @DisplayName("the menu holds only what the nav fragment links to")
    void menuComesFromTheNavFragment() {
        final List<String> menu = menu();

        assertTrue(menu.contains("plans"), menu.toString());
        assertTrue(menu.contains("contact"), menu.toString());
        assertFalse(menu.contains("plan-detail"), "the nav does not link it: " + menu);
    }

    @Test
    @DisplayName("screens hold every page under the site, menu or not")
    void screensHoldEveryPage() {
        final Map<String, Object> screens = map(app().get("screens"));

        assertTrue(screens.containsKey("en"));
        assertTrue(screens.containsKey("plans"));
        assertTrue(screens.containsKey("contact"));
        assertTrue(screens.containsKey("plan-detail"),
                "a page off the menu is still reachable and must render: " + screens.keySet());
    }

    @Test
    @DisplayName("nav and footer are furniture and are never screens")
    void furnitureIsExcluded() {
        final Map<String, Object> screens = map(app().get("screens"));

        assertFalse(screens.containsKey("nav"), screens.keySet().toString());
        assertFalse(screens.containsKey("footer"), screens.keySet().toString());
    }

    @Test
    @DisplayName("a screen records whether the menu contains it")
    void screenMetaRecordsMembership() {
        final List<String> menu = menu();

        final Map<String, Object> plans = screenMeta("plans");
        assertEquals(Boolean.TRUE, plans.get("includeInNavigation"));
        assertEquals(menu.indexOf("plans"), plans.get("navigationOrder"));

        final Map<String, Object> detail = screenMeta("plan-detail");
        assertEquals(Boolean.FALSE, detail.get("includeInNavigation"),
                "off the menu, but exported all the same");
        assertEquals(null, detail.get("navigationOrder"));
    }
}

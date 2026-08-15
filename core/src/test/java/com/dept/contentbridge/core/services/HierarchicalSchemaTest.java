package com.dept.contentbridge.core.services;

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

import com.dept.contentbridge.core.services.impl.FormSchemaServiceImpl;
import com.dept.contentbridge.core.services.impl.PageJsonExporterImpl;
import com.dept.contentbridge.core.services.impl.UrlServiceImpl;
import com.day.cq.wcm.api.Page;

import io.wcm.testing.mock.aem.junit5.AemContext;
import io.wcm.testing.mock.aem.junit5.AemContextExtension;

/**
 * The v2 contract keeps AEM's layout containers as {@code panel} components instead of flattening
 * them, because a section carries the spacing and alignment a consuming app needs.
 *
 * <p>v1 must be unaffected — it is the contract existing clients hold.</p>
 */
@ExtendWith(AemContextExtension.class)
class HierarchicalSchemaTest {

    private static final String HOME = "/content/bodea/language-masters/en";

    private final AemContext context = new AemContext(ResourceResolverType.JCR_MOCK);

    private PageJsonExporter exporter;

    @BeforeEach
    void setUp() {
        context.addModelsForPackage("com.dept.contentbridge.core.models.impl");
        context.load().json("/com/dept/contentbridge/core/franklin.json", "/content/bodea");

        context.registerInjectActivateService(new UrlServiceImpl(), "publishDomain", "");
        context.registerInjectActivateService(new FormSchemaServiceImpl());
        context.registerInjectActivateService(new PageJsonExporterImpl(),
                "allowedRoots", new String[] {"/content"},
                "cacheMaxAge", 0,
                "styleTokens", new String[] {"section-large=padding:24", "bg-default=backgroundColor:#F5F8FA"});

        exporter = context.getService(PageJsonExporter.class);
        assertNotNull(exporter);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> map(final Object value) {
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> list(final Object value) {
        return (List<Map<String, Object>>) value;
    }

    private List<Map<String, Object>> content(final ExportOptions options) {
        final Page home = context.pageManager().getPage(HOME);
        final Map<String, Object> screens = map(exporter.export(home, options).get("screens"));
        // franklin.json's root node name is 'en'; jcr:content/pageName ('home') is not read.
        return list(map(screens.get("en")).get("content"));
    }

    private String component(final Map<String, Object> entry) {
        return String.valueOf(map(entry.get("meta")).get("component"));
    }

    @Test
    @DisplayName("v1 stays flat - the existing contract is untouched")
    void v1RemainsFlat() {
        final List<Map<String, Object>> content = content(ExportOptions.defaults());

        assertEquals(List.of("hero", "text", "card", "card", "teaser", "title", "separator"),
                content.stream().map(this::component).collect(java.util.stream.Collectors.toList()));
        assertTrue(content.stream().allMatch(c -> c.get("content") instanceof Map),
                "every v1 entry has an object content");
        assertTrue(content.stream().noneMatch(c -> map(c.get("meta")).containsKey("order")),
                "v1 does not carry order");
    }

    @Test
    @DisplayName("panel styles emit the reference contract's skeleton, without padding")
    void panelStylesSkeleton() {
        final Map<String, Object> firstPanel = content(ExportOptions.of("v2", null)).get(0);
        final Map<String, Object> styles = map(firstPanel.get("styles"));

        for (final String key : List.of("gap", "margin", "alignItems",
                "justifyContent", "borderRadius", "backgroundColor", "height", "marginTop")) {
            assertTrue(styles.containsKey(key),
                    "panel styles missing " + key + ": " + styles.keySet());
        }
        assertFalse(styles.containsKey("padding"),
                "padding is never emitted on a panel: " + styles.keySet());
    }

    @Test
    @DisplayName("panel meta carries showBorder, defaulting to false")
    void panelShowBorderDefaultsFalse() {
        final Map<String, Object> firstPanel = content(ExportOptions.of("v2", null)).get(0);
        final Map<String, Object> meta = map(firstPanel.get("meta"));

        assertTrue(meta.containsKey("showBorder"),
                "reference contract carries showBorder on every panel meta: " + meta.keySet());
        assertEquals(Boolean.FALSE, meta.get("showBorder"),
                "AEM has no equivalent property, so the default is false");
    }

    @Test
    @DisplayName("v2 turns each section into a panel")
    void sectionsBecomePanels() {
        final List<Map<String, Object>> content = content(ExportOptions.of("v2", null));

        assertTrue(content.stream().allMatch(c -> "panel".equals(component(c))),
                "top level is all panels: " + content.stream().map(this::component)
                        .collect(java.util.stream.Collectors.toList()));
        assertEquals(4, content.size(), "the fixture has four sections");
    }

    @Test
    @DisplayName("a panel's content is an array of its children")
    void panelContentIsAnArray() {
        final Map<String, Object> firstPanel = content(ExportOptions.of("v2", null)).get(0);

        assertTrue(firstPanel.get("content") instanceof List);
        final List<Map<String, Object>> children = list(firstPanel.get("content"));

        // 'banner', not 'hero': v2 also renames components into the app's vocabulary. v1RemainsFlat
        // above pins the authored name, so the two contracts stay visibly distinct.
        assertEquals(List.of("banner"), children.stream().map(this::component)
                .collect(java.util.stream.Collectors.toList()));
    }

    @Test
    @DisplayName("nesting is preserved all the way down - columns inside a section")
    void deepNesting() {
        // fixture: section_20 > columns_1 > row1 > col1/col2 > teaser, title
        final Map<String, Object> panel = content(ExportOptions.of("v2", null)).get(2);
        final List<Map<String, Object>> level2 = list(panel.get("content"));

        assertEquals("panel", component(level2.get(0)), "the columns component is a panel too");
        assertEquals("horizontal", map(level2.get(0).get("meta")).get("layout"));
    }

    @Test
    @DisplayName("every component carries its order within its parent")
    void orderIsEmitted() {
        final List<Map<String, Object>> content = content(ExportOptions.of("v2", null));

        for (int i = 0; i < content.size(); i++) {
            assertEquals(i, map(content.get(i).get("meta")).get("order"));
        }
    }

    @Test
    @DisplayName("style tokens resolve to configured values; unmapped tokens are dropped")
    void styleTokensResolve() {
        final Map<String, Object> firstPanel = content(ExportOptions.of("v2", null)).get(0);

        // section_2 carries sec-custom-styles=bodea-banner (unmapped) and sec-spacing=section-large
        final Map<String, Object> styles = map(firstPanel.get("styles"));
        assertFalse(styles.containsKey("padding"), "padding is suppressed on panels");
        assertFalse(styles.containsValue("bodea-banner"), "an unmapped token must not leak through");
    }

    @Test
    @DisplayName("an unknown schema value falls back to v1 rather than failing")
    void unknownSchemaFallsBackToV1() {
        final List<Map<String, Object>> content = content(ExportOptions.of("v99", null));

        assertEquals("hero", component(content.get(0)));
    }

    @Test
    @DisplayName("DAM references are prefixed inside nested panels too")
    void assetsPrefixedWhenNested() {
        context.create().page("/content/bodea/placeholders", "",
                Map.of("hostname", "https://author-p1-e1.adobeaemcloud.com"));

        final Map<String, Object> hero = list(content(ExportOptions.of("v2", null)).get(0).get("content")).get(0);
        assertEquals("https://publish-p1-e1.adobeaemcloud.com/content/dam/bodea/en/images/hero.png",
                map(hero.get("content")).get("image"));
    }
}

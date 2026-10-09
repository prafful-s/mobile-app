package com.edsMobileApp.core.services.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.sling.api.resource.ModifiableValueMap;
import org.apache.sling.models.factory.ModelFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;

import com.day.cq.wcm.api.Page;
import com.edsMobileApp.core.models.impl.GenericComponent;
import com.edsMobileApp.core.schema.SchemaConstants;
import com.edsMobileApp.core.services.FormSchemaService;

import io.wcm.testing.mock.aem.junit5.AemContext;
import io.wcm.testing.mock.aem.junit5.AemContextExtension;

/**
 * A columns block is a layout container: its columns are not leaves but cells that group the basic
 * elements inside them (text, image, ...). Each column is exported as its own {@code panel} whose
 * {@code content} is those elements, already mapped by their own rules, and whose layout is the
 * column's authored {@code itemAlignment}.
 */
@ExtendWith(AemContextExtension.class)
class ColumnPanelTest {

    private final AemContext context = new AemContext();

    private ComponentTreeWalker walker;

    @BeforeEach
    void setUp() {
        final UrlServiceImpl urlService = new UrlServiceImpl();
        final ModelFactory modelFactory = Mockito.mock(ModelFactory.class);
        Mockito.when(modelFactory.getModelFromResource(Mockito.any()))
                .thenAnswer(invocation -> new GenericComponent(invocation.getArgument(0), urlService));
        final FormSchemaService schemas = new FormSchemaService() {
            @Override
            public List<Map<String, Object>> getSchema(final String formName) {
                return "sign-in".equals(formName) ? List.of(Map.of("name", "email")) : List.of();
            }

            @Override
            public Map<String, List<Map<String, Object>>> getAllSchemas() {
                return Map.of();
            }

            @Override
            public Set<String> getFormNames() {
                return Set.of();
            }
        };
        walker = new ComponentTreeWalker(modelFactory, urlService, schemas,
                new StyleResolver(new String[0]), ComponentMappings.load());
    }

    @Test
    void mapAndWebViewArePageLevelEvenInsideNestedSections() {
        final Page page = context.create().page("/content/s/en/full-width");
        final String section = page.getPath() + "/jcr:content/root/section";
        context.create().resource(section,
                "sling:resourceType", SchemaConstants.RT_FRANKLIN_SECTION);
        context.create().resource(section + "/nested",
                "sling:resourceType", SchemaConstants.RT_FRANKLIN_SECTION);
        context.create().resource(section + "/nested/map",
                "sling:resourceType", SchemaConstants.RT_FRANKLIN_BLOCK,
                "model", "map", "mobileDisplayAs", "fullMap");
        context.create().resource(section + "/nested/web-view",
                "sling:resourceType", SchemaConstants.RT_FRANKLIN_BLOCK,
                "model", "web-view", "mobileUrl", "https://www.adobe.com");

        final List<Map<String, Object>> components = walker.componentsOf(page, null, true);
        assertEquals(2, components.size());
        assertEquals("map", meta(components.get(0)).get(SchemaConstants.KEY_COMPONENT));
        assertEquals("webView", meta(components.get(1)).get(SchemaConstants.KEY_COMPONENT));
        assertEquals(0, meta(components.get(0)).get(SchemaConstants.KEY_ORDER));
        assertEquals(1, meta(components.get(1)).get(SchemaConstants.KEY_ORDER));
        assertEquals(Map.of("url", "https://www.adobe.com"),
                components.get(1).get(SchemaConstants.KEY_CONTENT));
    }

    @Test
    void mixedSectionKeepsOrdinaryContentInPanelsAroundMap() {
        final Page page = context.create().page("/content/s/en/mixed");
        final String section = page.getPath() + "/jcr:content/root/section";
        context.create().resource(section,
                "sling:resourceType", SchemaConstants.RT_FRANKLIN_SECTION);
        context.create().resource(section + "/before",
                "sling:resourceType", SchemaConstants.RT_FRANKLIN_TEXT,
                "model", "text", "text", "<p>Before</p>");
        context.create().resource(section + "/map",
                "sling:resourceType", SchemaConstants.RT_FRANKLIN_BLOCK, "model", "map");
        context.create().resource(section + "/after",
                "sling:resourceType", SchemaConstants.RT_FRANKLIN_TEXT,
                "model", "text", "text", "<p>After</p>");

        final List<Map<String, Object>> components = walker.componentsOf(page, null, true);
        assertEquals(3, components.size());
        assertEquals("panel", meta(components.get(0)).get(SchemaConstants.KEY_COMPONENT));
        assertEquals("map", meta(components.get(1)).get(SchemaConstants.KEY_COMPONENT));
        assertEquals("panel", meta(components.get(2)).get(SchemaConstants.KEY_COMPONENT));
        assertEquals(1, ((List<?>) components.get(0).get(SchemaConstants.KEY_CONTENT)).size());
        assertEquals(1, ((List<?>) components.get(2).get(SchemaConstants.KEY_CONTENT)).size());
        assertEquals(3, components.stream().map(component ->
                meta(component).get(SchemaConstants.KEY_ID)).distinct().count());
        for (int index = 0; index < components.size(); index++) {
            assertEquals(index, meta(components.get(index)).get(SchemaConstants.KEY_ORDER));
        }
    }

    private Page twoColumnPage(final String firstAlignment) {
        final Page page = context.create().page("/content/s/en/p");
        context.create().resource(page.getPath() + "/jcr:content/root");
        final String columns = page.getPath() + "/jcr:content/root/columns";
        context.create().resource(columns,
                "sling:resourceType", SchemaConstants.RT_FRANKLIN_COLUMNS);

        final String colA = columns + "/col0";
        context.create().resource(colA,
                "sling:resourceType", SchemaConstants.RT_FRANKLIN_BLOCK_ITEM,
                "model", "column",
                "itemAlignment", firstAlignment);
        context.create().resource(colA + "/text",
                "sling:resourceType", SchemaConstants.RT_FRANKLIN_TEXT,
                "model", "text",
                "text", "<p>Left</p>");

        final String colB = columns + "/col1";
        context.create().resource(colB,
                "sling:resourceType", SchemaConstants.RT_FRANKLIN_BLOCK_ITEM,
                "model", "column",
                "itemAlignment", "vertical");
        context.create().resource(colB + "/image",
                "sling:resourceType", "core/franklin/components/image/v1/image",
                "model", "image",
                "image", "/content/dam/s/right.jpg");
        return page;
    }

    private Page sectionVisibilityPage(final Object... visibility) {
        final Page page = context.create().page("/content/s/en/p");
        final String root = page.getPath() + "/jcr:content/root";
        context.create().resource(root);
        context.create().resource(root + "/restricted", "sling:resourceType", SchemaConstants.RT_FRANKLIN_SECTION);
        context.resourceResolver().getResource(root + "/restricted")
                .adaptTo(ModifiableValueMap.class)
                .putAll(visibility.length == 0 ? Map.of() : Map.of((String) visibility[0], visibility[1]));
        context.create().resource(root + "/restricted/nested",
                "sling:resourceType", SchemaConstants.RT_FRANKLIN_SECTION);
        context.create().resource(root + "/restricted/nested/text",
                "sling:resourceType", SchemaConstants.RT_FRANKLIN_TEXT,
                "model", "text", "text", "<p>Restricted</p>");
        context.create().resource(root + "/visible", "sling:resourceType", SchemaConstants.RT_FRANKLIN_SECTION);
        context.create().resource(root + "/visible/text",
                "sling:resourceType", SchemaConstants.RT_FRANKLIN_TEXT,
                "model", "text", "text", "<p>Visible</p>");
        return page;
    }

    @Test
    void mobileHiddenSectionsAndAllDescendantsAreSkipped() {
        final Page page = sectionVisibilityPage("hide-in-mobile", true);
        for (final boolean hierarchical : List.of(false, true)) {
            final List<Map<String, Object>> components = walker.componentsOf(page, "", hierarchical, false);
            assertEquals(1, components.size());
            final Map<String, Object> text = hierarchical ? content(components.get(0)).get(0) : components.get(0);
            assertEquals(hierarchical ? "htmlText" : "text", meta(text).get(SchemaConstants.KEY_COMPONENT));
            assertEquals("<p>Visible</p>", ((Map<?, ?>) text.get(SchemaConstants.KEY_CONTENT)).get("text"));
        }
    }

    @Test
    void visibleAndDesktopOnlySectionsRemainInMobileOutput() {
        final Page page = sectionVisibilityPage("hide-in-mobile", false);
        assertEquals(2, walker.componentsOf(page, "", true, false).size());
        final ModifiableValueMap properties = context.resourceResolver()
                .getResource(page.getPath() + "/jcr:content/root/restricted")
                .adaptTo(ModifiableValueMap.class);
        properties.remove("hide-in-mobile");
        assertEquals(2, walker.componentsOf(page, "", true, false).size());
        properties.put("hide-in-desktop", true);
        assertEquals(2, walker.componentsOf(page, "", true, false).size());
        properties.put("hide-in-mobile", "true");
        assertEquals(1, walker.componentsOf(page, "", true, false).size());
    }

    @Test
    void mobileHiddenSectionsDoNotContributeFormSchemas() {
        final Page page = sectionVisibilityPage("hide-in-mobile", true);
        context.create().resource(page.getPath() + "/jcr:content/root/restricted/form",
                "sling:resourceType", SchemaConstants.RT_FRANKLIN_BLOCK,
                "model", "sign-in");
        assertEquals(Set.of(), walker.formSchemasOn(page));
        context.resourceResolver().getResource(page.getPath() + "/jcr:content/root/restricted")
                .adaptTo(ModifiableValueMap.class).put("hide-in-mobile", false);
        assertEquals(Set.of("sign-in"), walker.formSchemasOn(page));
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> content(final Map<String, Object> entry) {
        return (List<Map<String, Object>>) entry.get(SchemaConstants.KEY_CONTENT);
    }

    private static Map<String, Object> meta(final Map<String, Object> entry) {
        return (Map<String, Object>) entry.get(SchemaConstants.KEY_META);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> styles(final Map<String, Object> entry) {
        return (Map<String, Object>) entry.get(SchemaConstants.KEY_STYLES);
    }

    @Test
    void eachColumnBecomesItsOwnPanelHoldingItsMappedElements() {
        final List<Map<String, Object>> components =
                walker.componentsOf(twoColumnPage("vertical"), "https://assets.example.com", true, false);

        // columns -> one horizontal panel whose content is the two column panels.
        assertEquals(1, components.size(), "the columns block emits a single panel");
        final Map<String, Object> columnsPanel = components.get(0);
        assertEquals(SchemaConstants.COMPONENT_PANEL, meta(columnsPanel).get(SchemaConstants.KEY_COMPONENT));
        assertEquals("horizontal", meta(columnsPanel).get(SchemaConstants.KEY_LAYOUT));

        final List<Map<String, Object>> columns = content(columnsPanel);
        assertEquals(2, columns.size(), "each column is its own panel");
        assertEquals(SchemaConstants.COMPONENT_PANEL, meta(columns.get(0)).get(SchemaConstants.KEY_COMPONENT));

        // The basic elements inside keep their own mapped component names.
        assertEquals("htmlText", meta(content(columns.get(0)).get(0)).get(SchemaConstants.KEY_COMPONENT));
        assertEquals("image", meta(content(columns.get(1)).get(0)).get(SchemaConstants.KEY_COMPONENT));
    }

    @Test
    void theColumnsItemAlignmentDrivesItsPanelLayout() {
        final List<Map<String, Object>> columns =
                content(walker.componentsOf(twoColumnPage("horizontal"), "https://assets.example.com", true, false)
                        .get(0));

        assertEquals("horizontal", meta(columns.get(0)).get(SchemaConstants.KEY_LAYOUT),
                "itemAlignment=horizontal makes the column lay its contents out side by side");
        assertEquals("vertical", meta(columns.get(1)).get(SchemaConstants.KEY_LAYOUT),
                "itemAlignment=vertical stacks the column's contents");
    }

    @Test
    void imagesInAColumnCollapseIntoOneHorizontalRowAt120pxWhileTextDropsBelow() {
        final Page page = context.create().page("/content/s/en/p");
        context.create().resource(page.getPath() + "/jcr:content/root");
        final String columns = page.getPath() + "/jcr:content/root/columns";
        context.create().resource(columns, "sling:resourceType", SchemaConstants.RT_FRANKLIN_COLUMNS);
        final String col = columns + "/col0";
        context.create().resource(col,
                "sling:resourceType", SchemaConstants.RT_FRANKLIN_BLOCK_ITEM,
                "model", "column", "itemAlignment", "vertical");
        // Authored order is image, text, image - the two images must still end up side by side first.
        context.create().resource(col + "/a",
                "sling:resourceType", "core/franklin/components/image/v1/image",
                "model", "image", "image", "/content/dam/s/a.jpg");
        context.create().resource(col + "/b",
                "sling:resourceType", SchemaConstants.RT_FRANKLIN_TEXT,
                "model", "text", "text", "<p>Between</p>");
        context.create().resource(col + "/c",
                "sling:resourceType", "core/franklin/components/image/v1/image",
                "model", "image", "image", "/content/dam/s/c.jpg");

        final Map<String, Object> columnsPanel =
                walker.componentsOf(page, "https://assets.example.com", true, false).get(0);
        final List<Map<String, Object>> cell = content(content(columnsPanel).get(0));

        assertEquals(2, cell.size(), "the column holds the image row then the text");
        final Map<String, Object> imageRow = cell.get(0);
        assertEquals(SchemaConstants.COMPONENT_PANEL, meta(imageRow).get(SchemaConstants.KEY_COMPONENT));
        assertEquals("horizontal", meta(imageRow).get(SchemaConstants.KEY_LAYOUT));

        final List<Map<String, Object>> rowImages = content(imageRow);
        assertEquals(2, rowImages.size(), "both images sit in the row regardless of authored order");
        assertEquals("120", styles(rowImages.get(0)).get("width"));
        assertEquals("120", styles(rowImages.get(1)).get("width"));

        assertEquals("htmlText", meta(cell.get(1)).get(SchemaConstants.KEY_COMPONENT),
                "text drops to its own full-width row below the images");
    }

    private Map<String, Object> sectionPanel(final String name, final Object... sectionProps) {
        final Page page = context.create().page("/content/s/en/" + name);
        final String section = page.getPath() + "/jcr:content/root/section";
        final Object[] props = new Object[sectionProps.length + 2];
        props[0] = "sling:resourceType";
        props[1] = SchemaConstants.RT_FRANKLIN_SECTION;
        System.arraycopy(sectionProps, 0, props, 2, sectionProps.length);
        context.create().resource(section, props);
        context.create().resource(section + "/text",
                "sling:resourceType", SchemaConstants.RT_FRANKLIN_TEXT,
                "model", "text", "text", "<p>Body</p>");
        return walker.componentsOf(page, null, true).get(0);
    }

    @Test
    void sectionTakesItsAuthoredMobileLayout() {
        final Map<String, Object> panel = sectionPanel("authored", "mobilelayout", "horizontal");

        assertEquals(SchemaConstants.COMPONENT_PANEL, meta(panel).get(SchemaConstants.KEY_COMPONENT));
        assertEquals("horizontal", meta(panel).get(SchemaConstants.KEY_LAYOUT));
        assertEquals(0, styles(panel).get("gap"), "a horizontal panel takes the horizontal spacing defaults");
    }

    @Test
    void unauthoredSectionKeepsStackingAsItAlwaysHas() {
        final Map<String, Object> panel = sectionPanel("unauthored");

        assertEquals("vertical", meta(panel).get(SchemaConstants.KEY_LAYOUT));
        assertEquals(8, styles(panel).get("gap"));
    }

    @Test
    void mobileLayoutInAnotherVocabularyIsIgnored() {
        // A cards block's own mobilelayout says "vertical/grid"; that is not a panel layout.
        assertEquals("vertical",
                meta(sectionPanel("grid", "mobilelayout", "vertical/grid")).get(SchemaConstants.KEY_LAYOUT));
    }
}

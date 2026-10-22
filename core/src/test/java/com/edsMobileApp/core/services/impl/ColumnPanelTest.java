package com.edsMobileApp.core.services.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

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
    void unauthoredSectionKeepsStackingAsItAlwaysHas() {
        final Map<String, Object> panel = sectionPanel("unauthored");

        assertEquals("vertical", meta(panel).get(SchemaConstants.KEY_LAYOUT));
        assertEquals(8, styles(panel).get("gap"));
    }

    @Test
    void aSectionsCustomMobileStyleOverridesItsPanelStyles() {
        final Map<String, Object> panel = sectionPanel("custom-style",
                "custommobilestyle", new String[] {"gap:16", "padding:12"});

        assertEquals(16L, styles(panel).get("gap"));
        assertEquals(12L, styles(panel).get("padding"));
        assertEquals(8, styles(panel).get("margin"), "unauthored styles keep their default");
    }

    @Test
    void fullHeightAndItemsFillHeightAppearOnlyWhenAuthored() {
        final Map<String, Object> unauthored = meta(sectionPanel("flags-unset"));
        assertFalse(unauthored.containsKey("fullHeight"));
        assertFalse(unauthored.containsKey("itemsFillHeight"));
        assertEquals(false, unauthored.get("showBorder"), "showBorder keeps its default");

        final Map<String, Object> authored = meta(sectionPanel("flags-set",
                "fullHeight", true, "itemsFillHeight", "true", "showBorder", true));
        assertEquals(true, authored.get("fullHeight"));
        assertEquals(true, authored.get("itemsFillHeight"));
        assertEquals(true, authored.get("showBorder"));

        assertEquals(false, meta(sectionPanel("flags-off", "fullHeight", false)).get("fullHeight"));
    }

    @Test
    void aSectionsLayoutStylesAreAllAuthorableThroughCustomMobileStyle() {
        final Map<String, Object> styles = styles(sectionPanel("author-styles", "custommobilestyle",
                new String[] {"gap:8", "margin:8", "padding:8", "alignItems:center",
                    "justifyContent:space-between-author-value"}));

        assertEquals(8L, styles.get("gap"));
        assertEquals(8L, styles.get("margin"));
        assertEquals(8L, styles.get("padding"));
        assertEquals("center", styles.get("alignItems"));
        assertEquals("space-between-author-value", styles.get("justifyContent"));
    }

    @Test
    void mobileLayoutInAnotherVocabularyIsIgnored() {
        // A cards block's own mobilelayout says "vertical/grid"; that is not a panel layout.
        assertEquals("vertical",
                meta(sectionPanel("grid", "mobilelayout", "vertical/grid")).get(SchemaConstants.KEY_LAYOUT));
    }

    private Map<String, Object> columnsPanel(final String name, final Object... columnsProps) {
        final Page page = context.create().page("/content/s/en/" + name);
        final String columns = page.getPath() + "/jcr:content/root/columns";
        final Object[] props = new Object[columnsProps.length + 2];
        props[0] = "sling:resourceType";
        props[1] = SchemaConstants.RT_FRANKLIN_COLUMNS;
        System.arraycopy(columnsProps, 0, props, 2, columnsProps.length);
        context.create().resource(columns, props);
        context.create().resource(columns + "/col0",
                "sling:resourceType", SchemaConstants.RT_FRANKLIN_BLOCK_ITEM,
                "model", "column", "itemAlignment", "vertical");
        context.create().resource(columns + "/col0/text",
                "sling:resourceType", SchemaConstants.RT_FRANKLIN_TEXT,
                "model", "text", "text", "<p>Left</p>");
        return walker.componentsOf(page, null, true).get(0);
    }

    @Test
    void columnsBlockTakesItsAuthoredMobileLayout() {
        assertEquals("vertical",
                meta(columnsPanel("stacked", "mobilelayout", "vertical")).get(SchemaConstants.KEY_LAYOUT),
                "an authored layout overrides the horizontal a columns block gets from its type");
    }

    @Test
    void unauthoredColumnsBlockStaysHorizontal() {
        assertEquals("horizontal", meta(columnsPanel("plain")).get(SchemaConstants.KEY_LAYOUT));
    }

    @Test
    void aColumnsMobileLayoutBeatsItsItemAlignment() {
        final Page page = context.create().page("/content/s/en/cell");
        final String columns = page.getPath() + "/jcr:content/root/columns";
        context.create().resource(columns, "sling:resourceType", SchemaConstants.RT_FRANKLIN_COLUMNS);
        context.create().resource(columns + "/col0",
                "sling:resourceType", SchemaConstants.RT_FRANKLIN_BLOCK_ITEM,
                "model", "column", "itemAlignment", "vertical", "mobilelayout", "horizontal");
        context.create().resource(columns + "/col0/text",
                "sling:resourceType", SchemaConstants.RT_FRANKLIN_TEXT,
                "model", "text", "text", "<p>Left</p>");

        final Map<String, Object> cell = content(walker.componentsOf(page, null, true).get(0)).get(0);
        assertEquals("horizontal", meta(cell).get(SchemaConstants.KEY_LAYOUT));
    }

    /**
     * The shape AEM really produces: an untyped row wrapper sits between a columns block and its
     * cells. Every one of the 11 columns blocks on secur-financial looks like this.
     */
    private Page rowWrappedColumnsPage(final String name, final Object... columnsProps) {
        final Page page = context.create().page("/content/s/en/" + name);
        final String columns = page.getPath() + "/jcr:content/root/columns";
        final Object[] props = new Object[columnsProps.length + 2];
        props[0] = "sling:resourceType";
        props[1] = SchemaConstants.RT_FRANKLIN_COLUMNS;
        System.arraycopy(columnsProps, 0, props, 2, columnsProps.length);
        context.create().resource(columns, props);
        // The block/item cells UE creates up front stay empty once content goes into the row, and
        // an empty panel is dropped rather than emitted - so they contribute nothing.
        context.create().resource(columns + "/column",
                "sling:resourceType", SchemaConstants.RT_FRANKLIN_BLOCK_ITEM,
                "model", "column", "itemAlignment", "vertical");
        // row1 and its cols carry no sling:resourceType at all, so each is a panel by virtue of
        // being untyped with children rather than by being a recognised column item.
        final String row = columns + "/row1";
        context.create().resource(row);
        for (final String cell : new String[] {"col1", "col2"}) {
            context.create().resource(row + "/" + cell);
            context.create().resource(row + "/" + cell + "/text",
                    "sling:resourceType", SchemaConstants.RT_FRANKLIN_TEXT,
                    "model", "text", "text", "<p>" + cell + "</p>");
        }
        return page;
    }

    @Test
    void authoredColumnsFlattenIntoOnePanel() {
        final List<Map<String, Object>> components =
                walker.componentsOf(rowWrappedColumnsPage("flat", "mobilelayout", "horizontal"), null, true);

        assertEquals(1, components.size());
        final Map<String, Object> panel = components.get(0);
        assertEquals("horizontal", meta(panel).get(SchemaConstants.KEY_LAYOUT));

        final List<Map<String, Object>> kids = content(panel);
        assertEquals(2, kids.size(), "both cells' contents become the panel's own content");
        for (int index = 0; index < kids.size(); index++) {
            assertEquals("htmlText", meta(kids.get(index)).get(SchemaConstants.KEY_COMPONENT),
                    "mapped by their own rule, with the row wrapper and cell panels gone");
            assertEquals(index, meta(kids.get(index)).get(SchemaConstants.KEY_ORDER),
                    "each cell numbered its content from zero, so order is stamped again");
        }
    }

    @Test
    void unauthoredColumnsKeepTheirNesting() {
        final Map<String, Object> panel =
                walker.componentsOf(rowWrappedColumnsPage("nested"), null, true).get(0);

        assertEquals("horizontal", meta(panel).get(SchemaConstants.KEY_LAYOUT));
        final List<Map<String, Object>> kids = content(panel);
        assertEquals(1, kids.size(), "the untyped row wrapper still emits a panel of its own");
        assertEquals(SchemaConstants.COMPONENT_PANEL, meta(kids.get(0)).get(SchemaConstants.KEY_COMPONENT));
        assertEquals("vertical", meta(kids.get(0)).get(SchemaConstants.KEY_LAYOUT),
                "which is why an authored layout on the block alone was inert");
        assertEquals(2, content(kids.get(0)).size(), "and the cells are still panels below it");
    }

    /**
     * {@code itemsAlignment} is not a field of the target's panel - its "Items alignment" is the
     * {@code alignItems} exposed style, which this export already carries in {@code styles} and an
     * author can set through Custom Mobile Style. The key was emitted on every panel hardcoded to
     * "center", since no AEM property has ever backed it, so it told the client nothing it did not
     * already assume.
     */
    @Test
    void noPanelCarriesAnItemsAlignmentKey() {
        final Map<String, Object> panel =
                walker.componentsOf(rowWrappedColumnsPage("aligned"), null, true).get(0);

        assertFalse(meta(panel).containsKey("itemsAlignment"));
        assertFalse(meta(content(panel).get(0)).containsKey("itemsAlignment"),
                "the row wrapper's panel is emitted by the same code path");
    }

    // ---------------------------------------------------------------------------------------
    // The columns/row/column combination matrix. Each case fixes one dimension: what the block
    // authored, how deep the wrappers go, what the cells hold, and what else is in the tree.
    // ---------------------------------------------------------------------------------------

    /** A columns block under its own page, with whatever properties the case needs. */
    private String columnsNode(final String page, final Object... props) {
        final Page created = context.create().page("/content/s/en/" + page);
        final String columns = created.getPath() + "/jcr:content/root/columns";
        final Object[] all = new Object[props.length + 2];
        all[0] = "sling:resourceType";
        all[1] = SchemaConstants.RT_FRANKLIN_COLUMNS;
        System.arraycopy(props, 0, all, 2, props.length);
        context.create().resource(columns, all);
        return columns;
    }

    private void text(final String path, final String body) {
        context.create().resource(path,
                "sling:resourceType", SchemaConstants.RT_FRANKLIN_TEXT,
                "model", "text", "text", "<p>" + body + "</p>");
    }

    private List<Map<String, Object>> exportOf(final String columnsPath) {
        final String pagePath = columnsPath.substring(0, columnsPath.indexOf("/jcr:content"));
        return walker.componentsOf(context.pageManager().getPage(pagePath), null, true);
    }

    @Test
    void verticalAuthoredOnColumnsFlattensAndStacks() {
        final String columns = columnsNode("v-flat", "mobilelayout", "vertical");
        context.create().resource(columns + "/row1");
        text(columns + "/row1/col1/text", "a");
        text(columns + "/row1/col2/text", "b");

        final Map<String, Object> panel = exportOf(columns).get(0);
        assertEquals("vertical", meta(panel).get(SchemaConstants.KEY_LAYOUT));
        assertEquals(2, content(panel).size(),
                "vertical flattens too - the field is the gate, not the direction");
        assertEquals(8, styles(panel).get("gap"), "and it takes the vertical spacing defaults");
    }

    @Test
    void foreignLayoutVocabularyNeitherFlattensNorChangesLayout() {
        // "vertical/grid" belongs to the cards and lister blocks. It is not a panel layout, so it
        // must neither be honoured nor switch the flattening on.
        final String columns = columnsNode("foreign", "mobilelayout", "vertical/grid");
        context.create().resource(columns + "/row1");
        text(columns + "/row1/col1/text", "a");

        final Map<String, Object> panel = exportOf(columns).get(0);
        assertEquals("horizontal", meta(panel).get(SchemaConstants.KEY_LAYOUT));
        assertEquals(SchemaConstants.COMPONENT_PANEL,
                meta(content(panel).get(0)).get(SchemaConstants.KEY_COMPONENT), "nesting kept");
    }

    @Test
    void typedColumnCellsWithNoRowWrapperAlsoFlatten() {
        final String columns = columnsNode("typed", "mobilelayout", "horizontal");
        for (final String cell : new String[] {"col0", "col1"}) {
            context.create().resource(columns + "/" + cell,
                    "sling:resourceType", SchemaConstants.RT_FRANKLIN_BLOCK_ITEM,
                    "model", "column", "itemAlignment", "vertical");
            text(columns + "/" + cell + "/text", cell);
        }

        final List<Map<String, Object>> kids = content(exportOf(columns).get(0));
        assertEquals(2, kids.size(), "a recognised column cell is unwrapped like an untyped one");
        assertEquals("htmlText", meta(kids.get(0)).get(SchemaConstants.KEY_COMPONENT));
    }

    @Test
    void leavesDirectlyUnderColumnsAreLeftAlone() {
        final String columns = columnsNode("direct", "mobilelayout", "horizontal");
        text(columns + "/t1", "a");
        text(columns + "/t2", "b");

        final List<Map<String, Object>> kids = content(exportOf(columns).get(0));
        assertEquals(2, kids.size(), "nothing to unwrap - flattening is a no-op here");
        assertEquals(0, meta(kids.get(0)).get(SchemaConstants.KEY_ORDER));
        assertEquals(1, meta(kids.get(1)).get(SchemaConstants.KEY_ORDER));
    }

    @Test
    void aNestedColumnsBlockKeepsWhatItGrouped() {
        final String columns = columnsNode("deep", "mobilelayout", "horizontal");
        context.create().resource(columns + "/row1");
        text(columns + "/row1/col1/text", "outer");
        final String inner = columns + "/row1/col2/columns";
        context.create().resource(inner, "sling:resourceType", SchemaConstants.RT_FRANKLIN_COLUMNS);
        context.create().resource(inner + "/row1");
        text(inner + "/row1/col1/text", "inner-a");
        text(inner + "/row1/col2/text", "inner-b");

        final List<Map<String, Object>> kids = content(exportOf(columns).get(0));
        assertEquals(2, kids.size(), "the outer cell's single leaf, then the inner block's pair");
        assertEquals("htmlText", meta(kids.get(0)).get(SchemaConstants.KEY_COMPONENT));
        assertEquals(SchemaConstants.COMPONENT_PANEL, meta(kids.get(1)).get(SchemaConstants.KEY_COMPONENT),
                "the inner block held two components, so the panel grouping them survives");
        assertEquals(2, content(kids.get(1)).size());
        assertEquals(0, meta(kids.get(0)).get(SchemaConstants.KEY_ORDER));
        assertEquals(1, meta(kids.get(1)).get(SchemaConstants.KEY_ORDER));
    }

    @Test
    void aCellHoldingSeveralLeavesKeepsItsOwnPanel() {
        final String columns = columnsNode("multi", "mobilelayout", "horizontal");
        context.create().resource(columns + "/row1");
        text(columns + "/row1/col1/t1", "a");
        text(columns + "/row1/col1/t2", "b");
        text(columns + "/row1/col2/t1", "c");
        text(columns + "/row1/col2/t2", "d");

        final List<Map<String, Object>> kids = content(exportOf(columns).get(0));
        assertEquals(2, kids.size(), "two cells, each holding a pair, so both keep their panel");
        for (int index = 0; index < kids.size(); index++) {
            assertEquals(SchemaConstants.COMPONENT_PANEL,
                    meta(kids.get(index)).get(SchemaConstants.KEY_COMPONENT));
            assertEquals("vertical", meta(kids.get(index)).get(SchemaConstants.KEY_LAYOUT),
                    "each cell stacks its own contents, across the block's horizontal axis");
            assertEquals(2, content(kids.get(index)).size());
            assertEquals(index, meta(kids.get(index)).get(SchemaConstants.KEY_ORDER));
        }
    }

    @Test
    void emptyCellsLeaveNoGapInTheOrder() {
        final String columns = columnsNode("gappy", "mobilelayout", "horizontal");
        // the spare block/item cell UE creates up front, and an empty untyped col
        context.create().resource(columns + "/column",
                "sling:resourceType", SchemaConstants.RT_FRANKLIN_BLOCK_ITEM,
                "model", "column", "itemAlignment", "vertical");
        context.create().resource(columns + "/row1");
        context.create().resource(columns + "/row1/col1");
        text(columns + "/row1/col2/text", "only");

        final List<Map<String, Object>> kids = content(exportOf(columns).get(0));
        assertEquals(1, kids.size(), "an empty cell emits no panel, so there is nothing to unwrap");
        assertEquals(0, meta(kids.get(0)).get(SchemaConstants.KEY_ORDER));
    }

    @Test
    void anEmptyColumnsBlockEmitsNothingAtAll() {
        final String columns = columnsNode("void", "mobilelayout", "horizontal");
        context.create().resource(columns + "/row1");
        context.create().resource(columns + "/row1/col1");

        assertEquals(0, exportOf(columns).size(), "a panel with no content is dropped, authored or not");
    }

    @Test
    void aSectionIsNeverFlattened() {
        final Page page = context.create().page("/content/s/en/sec-flat");
        final String section = page.getPath() + "/jcr:content/root/section";
        context.create().resource(section,
                "sling:resourceType", SchemaConstants.RT_FRANKLIN_SECTION,
                "mobilelayout", "horizontal");
        final String inner = section + "/columns";
        context.create().resource(inner, "sling:resourceType", SchemaConstants.RT_FRANKLIN_COLUMNS);
        context.create().resource(inner + "/row1");
        text(inner + "/row1/col1/text", "a");

        final Map<String, Object> panel = walker.componentsOf(page, null, true).get(0);
        assertEquals("horizontal", meta(panel).get(SchemaConstants.KEY_LAYOUT),
                "a section honours the field for its own layout");
        assertEquals(SchemaConstants.COMPONENT_PANEL,
                meta(content(panel).get(0)).get(SchemaConstants.KEY_COMPONENT),
                "but does not flatten - the unauthored columns block inside keeps its panel");
    }

    @Test
    void aPageLevelComponentStillEscapesAFlattenedBlock() {
        final String columns = columnsNode("hoist", "mobilelayout", "horizontal");
        context.create().resource(columns + "/row1");
        text(columns + "/row1/col1/text", "before");
        context.create().resource(columns + "/row1/col2/map",
                "sling:resourceType", SchemaConstants.RT_FRANKLIN_BLOCK,
                "model", "map", "mobileDisplayAs", "fullMap");

        final List<Map<String, Object>> components = exportOf(columns);
        assertEquals(2, components.size(), "the map is hoisted out rather than left in the panel");
        assertEquals(SchemaConstants.COMPONENT_PANEL,
                meta(components.get(0)).get(SchemaConstants.KEY_COMPONENT));
        assertEquals("map", meta(components.get(1)).get(SchemaConstants.KEY_COMPONENT));
        assertEquals("htmlText",
                meta(content(components.get(0)).get(0)).get(SchemaConstants.KEY_COMPONENT));
    }

    @Test
    void theModelsEmptyDefaultIsNotAPanelLayout() {
        // UE materialises a select field's value onto the node when the component is saved, so the
        // Layout field defaults to "" rather than "horizontal". An empty value must read as unset:
        // neither a layout nor a reason to flatten.
        final String columns = columnsNode("empty-default", "mobilelayout", "");
        context.create().resource(columns + "/row1");
        text(columns + "/row1/col1/text", "a");
        text(columns + "/row1/col2/text", "b");

        final Map<String, Object> panel = exportOf(columns).get(0);
        assertEquals("horizontal", meta(panel).get(SchemaConstants.KEY_LAYOUT),
                "falls back to the resource type, as an untouched block always has");
        assertEquals(SchemaConstants.COMPONENT_PANEL,
                meta(content(panel).get(0)).get(SchemaConstants.KEY_COMPONENT),
                "and the nesting is kept - an empty default must not switch flattening on");
    }

    /** A cell with its own Layout field set: the dropdown added to the column model. */
    private Map<String, Object> cellPanel(final String name, final Object... cellProps) {
        final Page page = context.create().page("/content/s/en/" + name);
        final String columns = page.getPath() + "/jcr:content/root/columns";
        context.create().resource(columns, "sling:resourceType", SchemaConstants.RT_FRANKLIN_COLUMNS);
        final Object[] props = new Object[cellProps.length + 6];
        props[0] = "sling:resourceType";
        props[1] = SchemaConstants.RT_FRANKLIN_BLOCK_ITEM;
        props[2] = "model";
        props[3] = "column";
        props[4] = "itemAlignment";
        props[5] = "vertical";
        System.arraycopy(cellProps, 0, props, 6, cellProps.length);
        context.create().resource(columns + "/col0", props);
        context.create().resource(columns + "/col0/t1",
                "sling:resourceType", SchemaConstants.RT_FRANKLIN_TEXT, "model", "text", "text", "<p>a</p>");
        context.create().resource(columns + "/col0/t2",
                "sling:resourceType", SchemaConstants.RT_FRANKLIN_TEXT, "model", "text", "text", "<p>b</p>");
        return content(walker.componentsOf(page, null, true).get(0)).get(0);
    }

    @Test
    void aCellsOwnLayoutFieldOverridesItsItemAlignment() {
        assertEquals("horizontal",
                meta(cellPanel("cell-h", "mobilelayout", "horizontal")).get(SchemaConstants.KEY_LAYOUT),
                "the Layout field is more specific than the Basic tab's Item Alignment");
    }

    @Test
    void aCellCanBeForcedVerticalAgainstAHorizontalItemAlignment() {
        assertEquals("vertical",
                meta(cellPanel("cell-v", "itemAlignment", "horizontal", "mobilelayout", "vertical"))
                        .get(SchemaConstants.KEY_LAYOUT),
                "and it overrides in the other direction too, not just towards horizontal");
    }

    @Test
    void aCellsDefaultLayoutFallsBackToItemAlignment() {
        assertEquals("horizontal",
                meta(cellPanel("cell-default", "itemAlignment", "horizontal", "mobilelayout", ""))
                        .get(SchemaConstants.KEY_LAYOUT),
                "the Default option must leave the existing Item Alignment in charge");
        assertEquals("vertical",
                meta(cellPanel("cell-default-v", "mobilelayout", "")).get(SchemaConstants.KEY_LAYOUT));
    }

    @Test
    void aCellLayoutDoesNotFlattenTheBlockAroundIt() {
        final Map<String, Object> cell = cellPanel("cell-no-flatten", "mobilelayout", "horizontal");
        assertEquals(SchemaConstants.COMPONENT_PANEL, meta(cell).get(SchemaConstants.KEY_COMPONENT),
                "flattening is a columns-block decision; a cell setting its own layout keeps its panel");
        assertEquals(2, content(cell).size());
    }

    /**
     * The shape that exposed the flaw: an image in one cell, a title/subtitle/button stack in the
     * other. Flattening both cells laid all four along the block's horizontal axis, and the image
     * is fullWidth, so it took the row and pushed the rest off screen.
     */
    @Test
    void anImageCellBesideAStackedCellKeepsTheStackTogether() {
        final String columns = columnsNode("janet", "mobilelayout", "horizontal");
        context.create().resource(columns + "/row1");
        context.create().resource(columns + "/row1/col1/image",
                "sling:resourceType", "core/franklin/components/image/v1/image",
                "model", "image", "image", "/content/dam/s/janet.png");
        text(columns + "/row1/col2/title", "Janet Lee");
        text(columns + "/row1/col2/subtitle", "Loan Officer");
        text(columns + "/row1/col2/cta", "Call me now");

        final Map<String, Object> panel = exportOf(columns).get(0);
        assertEquals("horizontal", meta(panel).get(SchemaConstants.KEY_LAYOUT));

        final List<Map<String, Object>> kids = content(panel);
        assertEquals(2, kids.size(), "the image, then the stack - not four siblings in a row");
        assertEquals("image", meta(kids.get(0)).get(SchemaConstants.KEY_COMPONENT));
        assertEquals(SchemaConstants.COMPONENT_PANEL, meta(kids.get(1)).get(SchemaConstants.KEY_COMPONENT));
        assertEquals("vertical", meta(kids.get(1)).get(SchemaConstants.KEY_LAYOUT));
        assertEquals(3, content(kids.get(1)).size(), "title, subtitle and button stay grouped");
    }

    @Test
    void aLoneWrapperIsDissolvedHoweverMuchItHolds() {
        // row1 is the only child of the block. Keeping it would leave its vertical layout in charge
        // of the cells, which is the whole reason an authored horizontal was inert to begin with.
        final String columns = columnsNode("lone-wrapper", "mobilelayout", "horizontal");
        context.create().resource(columns + "/row1");
        text(columns + "/row1/col1/a", "a");
        text(columns + "/row1/col2/b", "b");
        text(columns + "/row1/col3/c", "c");

        final List<Map<String, Object>> kids = content(exportOf(columns).get(0));
        assertEquals(3, kids.size(), "three single-leaf cells, no row panel between them");
        for (final Map<String, Object> kid : kids) {
            assertEquals("htmlText", meta(kid).get(SchemaConstants.KEY_COMPONENT));
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> contentMap(final Map<String, Object> entry) {
        return (Map<String, Object>) entry.get(SchemaConstants.KEY_CONTENT);
    }

    @Test
    void anImageInARowDoesNotClaimTheWholeWidth() {
        final String columns = columnsNode("img-row", "mobilelayout", "horizontal");
        context.create().resource(columns + "/row1");
        context.create().resource(columns + "/row1/col1/image",
                "sling:resourceType", "core/franklin/components/image/v1/image",
                "model", "image", "image", "/content/dam/s/janet.png");
        text(columns + "/row1/col2/title", "Janet Lee");
        text(columns + "/row1/col2/subtitle", "Loan Officer");

        final List<Map<String, Object>> kids = content(exportOf(columns).get(0));
        assertEquals("image", meta(kids.get(0)).get(SchemaConstants.KEY_COMPONENT));
        assertEquals(Boolean.FALSE, contentMap(kids.get(0)).get("fullWidth"),
                "beside the stack it must leave room for it, or the siblings get no width at all");
    }

    @Test
    void anImageStackedAloneKeepsItsFullWidth() {
        final String columns = columnsNode("img-stack", "mobilelayout", "vertical");
        context.create().resource(columns + "/row1");
        context.create().resource(columns + "/row1/col1/image",
                "sling:resourceType", "core/franklin/components/image/v1/image",
                "model", "image", "image", "/content/dam/s/janet.png");
        text(columns + "/row1/col2/title", "Janet Lee");

        final List<Map<String, Object>> kids = content(exportOf(columns).get(0));
        assertEquals("image", meta(kids.get(0)).get(SchemaConstants.KEY_COMPONENT));
        assertEquals(Boolean.TRUE, contentMap(kids.get(0)).get("fullWidth"),
                "a vertical panel stacks, so the image across the screen is still right");
    }

    @Test
    void anImageInsideItsOwnCellKeepsItsFullWidth() {
        // The unauthored shape: cells survive, so the image is alone in a vertical cell even
        // though the block around it is horizontal.
        final String columns = columnsNode("img-celled");
        context.create().resource(columns + "/row1");
        context.create().resource(columns + "/row1/col1/image",
                "sling:resourceType", "core/franklin/components/image/v1/image",
                "model", "image", "image", "/content/dam/s/janet.png");
        text(columns + "/row1/col1/caption", "Janet Lee");
        text(columns + "/row1/col2/title", "Loan Officer");

        final Map<String, Object> row = content(exportOf(columns).get(0)).get(0);
        final Map<String, Object> cell = content(row).get(0);
        assertEquals(Boolean.TRUE, contentMap(content(cell).get(0)).get("fullWidth"));
    }
}

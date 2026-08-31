package com.edsMobileApp.core.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import com.edsMobileApp.core.ContentBridgeTestContext;
import com.day.cq.wcm.api.Page;

import io.wcm.testing.mock.aem.junit5.AemContext;
import io.wcm.testing.mock.aem.junit5.AemContextExtension;

@ExtendWith(AemContextExtension.class)
class PageJsonExporterImplTest {

    private final AemContext context = ContentBridgeTestContext.newContext();

    private PageJsonExporter exporter;

    @BeforeEach
    void setUp() {
        ContentBridgeTestContext.setUp(context);
        exporter = context.getService(PageJsonExporter.class);
        assertNotNull(exporter);
    }

    private Page page(final String path) {
        return context.pageManager().getPage(path);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> map(final Object value) {
        return (Map<String, Object>) value;
    }

    @Test
    @DisplayName("envelope carries meta, screens, branding, integrations, profileViewer")
    void envelopeShape() {
        final Map<String, Object> app = exporter.export(page(ContentBridgeTestContext.APP_ROOT));

        assertEquals(List.of("meta", "rules", "modules", "screens", "branding", "products", "integrations", "profileViewer"),
                List.copyOf(app.keySet()));
    }

    @Test
    @DisplayName("meta mirrors the app id and reads navigation from page properties")
    void meta() {
        final Map<String, Object> meta = map(exporter.export(page(ContentBridgeTestContext.APP_ROOT)).get("meta"));

        assertEquals("en", meta.get("id"));
        assertEquals("mobile-v2", meta.get("type"));
        assertEquals("Test App", meta.get("title"));
        assertEquals(Boolean.TRUE, meta.get("useCart"));

        final Map<String, Object> navigation = map(meta.get("navigation"));
        assertEquals("sign-in", navigation.get("loginScreen"));
        assertEquals("settings", navigation.get("profileScreen"));
        assertEquals("create-account", navigation.get("registrationScreen"));
        assertEquals(List.of("account"), navigation.get("authorizedMenu"));
    }

    @Test
    @DisplayName("menu and screens use the same keys, both from the node name")
    void menuMatchesScreens() {
        final Map<String, Object> app = exporter.export(page(ContentBridgeTestContext.APP_ROOT));
        final Map<String, Object> meta = map(app.get("meta"));
        final Map<String, Object> screens = map(app.get("screens"));

        @SuppressWarnings("unchecked")
        final List<String> menu = (List<String>) map(meta.get("navigation")).get("menu");

        assertEquals(menu, List.copyOf(screens.keySet()));
        assertTrue(menu.contains("en"));
        assertTrue(menu.contains("subpage-1"));
        assertTrue(menu.contains("renamed"),
                "the pageName property is no longer read - the key is the node name, kebab-cased");
    }

    @Test
    @DisplayName("screen key is the page's node name, not its title")
    void screenKeyIsNodeName() {
        final Map<String, Object> screens =
                map(exporter.export(page(ContentBridgeTestContext.APP_ROOT)).get("screens"));

        assertTrue(screens.containsKey("en"), "the app root's node name is 'en', its title is 'Home'");
        assertFalse(screens.containsKey("Home"), "title must not leak into the screen key: " + screens.keySet());
    }

    @Test
    @DisplayName("pages flagged excludeFromApp never appear")
    void excludedPagesAreSkipped() {
        final Map<String, Object> screens =
                map(exporter.export(page(ContentBridgeTestContext.APP_ROOT)).get("screens"));
        assertFalse(screens.containsKey("hidden-page"));
    }

    @Test
    @DisplayName("screen content is an ordered list of meta/content pairs")
    void screenContent() {
        final Map<String, Object> screens =
                map(exporter.export(page(ContentBridgeTestContext.APP_ROOT)).get("screens"));
        final Map<String, Object> home = map(screens.get("en"));

        assertTrue(map(home.get("meta")).keySet().containsAll(List.of("name", "title")),
                "screen meta carries at least name and title");

        @SuppressWarnings("unchecked")
        final List<Map<String, Object>> content = (List<Map<String, Object>>) home.get("content");

        assertEquals(7, content.size());
        assertEquals(List.of("banner", "title", "htmlText", "card", "divider", "image", "does-not-exist"),
                content.stream().map(c -> map(c.get("meta")).get("component")).collect(java.util.stream.Collectors.toList()));
    }

    @Test
    @DisplayName("screen thumbnail is blank when no card links to the screen")
    void screenThumbnailIsBlankWithoutAReferencingCard() {
        final Map<String, Object> screens =
                map(exporter.export(page(ContentBridgeTestContext.APP_ROOT)).get("screens"));

        assertEquals("", map(map(screens.get("en")).get("meta")).get("thumbnail"),
                "no card links to the home screen in this fixture");
        assertEquals("", map(map(screens.get("subpage-1")).get("meta")).get("thumbnail"));
    }

    @Test
    @DisplayName("a resource with no registered model is still exported, named from its resource type")
    void unmappedResourceTypesFallBackToGenericExport() {
        final Map<String, Object> screens =
                map(exporter.export(page(ContentBridgeTestContext.APP_ROOT)).get("screens"));

        @SuppressWarnings("unchecked")
        final List<Map<String, Object>> content =
                (List<Map<String, Object>>) map(screens.get("en")).get("content");

        final Map<String, Object> unmapped = content.get(content.size() - 1);
        assertEquals("does-not-exist", map(unmapped.get("meta")).get("component"),
                "the component name comes from the last segment of the resource type");
        assertNotNull(map(unmapped.get("meta")).get("id"));
    }

    @Test
    @DisplayName("a component type this project has never seen still exports its authored fields")
    void arbitraryProjectComponent() {
        context.build().resource(
                ContentBridgeTestContext.APP_ROOT + "/jcr:content/root/container/acmeTeaser",
                "sling:resourceType", "acme/components/content/teaser",
                "headline", "Quarterly results",
                "fileReference", "/content/dam/acme/chart.png",
                "cq:lastModifiedBy", "admin");

        final Map<String, Object> screens =
                map(exporter.export(page(ContentBridgeTestContext.APP_ROOT)).get("screens"));
        @SuppressWarnings("unchecked")
        final List<Map<String, Object>> content =
                (List<Map<String, Object>>) map(screens.get("en")).get("content");

        final Map<String, Object> teaser = content.stream()
                .filter(c -> "teaser".equals(map(c.get("meta")).get("component")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("acme teaser was not exported"));

        final Map<String, Object> payload = map(teaser.get("content"));
        assertEquals("Quarterly results", payload.get("headline"));
        assertEquals(ContentBridgeTestContext.PUBLISH_DOMAIN + "/content/dam/acme/chart.png",
                payload.get("fileReference"));
        assertFalse(payload.containsKey("cq:lastModifiedBy"), "bookkeeping must not leak");
    }

    @Test
    @DisplayName("requesting a sub page treats that page as the root of its own export")
    void singleScreenExport() {
        final Map<String, Object> app = exporter.export(page(ContentBridgeTestContext.APP_ROOT + "/subpage-1"));

        final Map<String, Object> screens = map(app.get("screens"));
        assertEquals(1, screens.size());
        assertTrue(screens.containsKey("subpage-1"));
    }

    @Test
    @DisplayName("branding keeps numeric types and leaves colour tokens alone")
    void branding() {
        final Map<String, Object> branding =
                map(exporter.export(page(ContentBridgeTestContext.APP_ROOT)).get("branding"));

        // No placeholders page in this fixture, so the host falls back to the configured publish
        // domain - the logo is as unreachable to the client as any other bare repository path.
        assertEquals(ContentBridgeTestContext.PUBLISH_DOMAIN + "/content/dam/contentbridge/brand/logo.png",
                branding.get("logo"));

        // A colour token is not a path and must survive the host pass untouched.
        assertEquals("#000000", branding.get("primaryColor"));
        assertEquals(4L, branding.get("roundness"));
        assertFalse(branding.containsKey("jcr:primaryType"));
    }

    @Test
    @DisplayName("extras JSON blobs are parsed, not echoed as strings")
    void extras() {
        final Map<String, Object> app = exporter.export(page(ContentBridgeTestContext.APP_ROOT));

        assertEquals("public-demo", map(map(app.get("integrations")).get("aep")).get("sandbox"));
    }

    @Test
    @DisplayName("malformed extras JSON degrades to an empty object instead of failing the request")
    void malformedExtras() {
        context.build().resource(
                ContentBridgeTestContext.APP_ROOT + "/jcr:content/contentbridge/extras",
                "integrationsJson", "{not json");

        final Map<String, Object> integrations =
                map(exporter.export(page(ContentBridgeTestContext.APP_ROOT)).get("integrations"));
        assertTrue(integrations.isEmpty());
    }

    /**
     * Authors an Edge Delivery {@code Cards} block - {@code filter="cards"}, three
     * {@code model="card"} items - under {@code subpage-1}'s container, each item's rich-text
     * {@code text} carrying a {@code <a href>} to one of the given targets, and all sharing the same
     * image. Mirrors the shape {@code luma-site}'s {@code language-masters/en} pages actually author.
     */
    private void buildCardsBlock(final String... linkTargets) {
        final String[] images = new String[linkTargets.length];
        Arrays.fill(images, "/content/dam/contentbridge/images/img2.png");
        buildCardsBlock(linkTargets, images);
    }

    /** Same shape as above, but with a distinct image per card. */
    private void buildCardsBlock(final String[] linkTargets, final String[] images) {
        final String base = ContentBridgeTestContext.APP_ROOT + "/subpage-1/jcr:content/root/container/cardsBlock";
        context.build().resource(base,
                "sling:resourceType", "core/franklin/components/block/v1/block",
                "filter", "cards",
                "name", "Cards");
        for (int i = 0; i < linkTargets.length; i++) {
            context.build().resource(base + "/item" + i,
                    "sling:resourceType", "core/franklin/components/block/v1/block/item",
                    "model", "card",
                    "image", images[i],
                    "text", "<p><a href=\"" + linkTargets[i] + "\">Card " + i + "</a></p>");
        }
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> subpage1Content() {
        final Map<String, Object> screens =
                map(exporter.export(page(ContentBridgeTestContext.APP_ROOT)).get("screens"));
        return (List<Map<String, Object>>) map(screens.get("subpage-1")).get("content");
    }

    @Test
    @DisplayName("a 3-card block whose links all resolve to real screens becomes one screenList")
    void cardsBlockWithAllLinksResolvedBecomesScreenList() {
        buildCardsBlock(
                ContentBridgeTestContext.APP_ROOT + "/renamed",
                ContentBridgeTestContext.APP_ROOT + "/subpage-1",
                ContentBridgeTestContext.APP_ROOT);

        final List<Map<String, Object>> content = subpage1Content();
        final Map<String, Object> list = content.stream()
                .filter(c -> "list".equals(map(c.get("meta")).get("component")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("expected a list component: " + content));

        final Map<String, Object> listContent = map(list.get("content"));
        assertEquals("screenList", listContent.get("listType"));
        assertEquals(List.of("renamed", "subpage-1", "en"), listContent.get("items"));

        // Sizing/layout fields a screenList overlay item needs to render three cards side by side
        // instead of stacked full-width - matches the LUMA MOBILE reference's own 3-card screenList.
        assertEquals("horizontal", listContent.get("layout"));
        assertEquals(130, listContent.get("imageWidth"));
        assertEquals(3, listContent.get("itemsInRow"));
        assertEquals("overlay", listContent.get("itemRenderer"));
        assertEquals("cover", listContent.get("imageResizeMode"));
        assertEquals("Category", listContent.get("filterProperty"));

        final Map<String, Object> styles = map(list.get("styles"));
        assertEquals(12, styles.get("gap"));
        assertEquals("16", styles.get("marginBottom"));
        assertEquals("8", styles.get("marginHorizontal"));
    }

    @Test
    @DisplayName("a screen linked from a card takes that card's own image as its thumbnail")
    void screenListItemThumbnailComesFromTheReferencingCard() {
        buildCardsBlock(
                new String[] {
                    ContentBridgeTestContext.APP_ROOT + "/renamed",
                    ContentBridgeTestContext.APP_ROOT + "/subpage-1",
                    ContentBridgeTestContext.APP_ROOT,
                },
                new String[] {
                    "/content/dam/contentbridge/images/renamed-card.png",
                    "/content/dam/contentbridge/images/subpage1-card.png",
                    "/content/dam/contentbridge/images/home-card.png",
                });

        final Map<String, Object> screens =
                map(exporter.export(page(ContentBridgeTestContext.APP_ROOT)).get("screens"));

        assertEquals(ContentBridgeTestContext.PUBLISH_DOMAIN
                        + "/content/dam/contentbridge/images/renamed-card.png",
                map(map(screens.get("renamed")).get("meta")).get("thumbnail"));
        assertEquals(ContentBridgeTestContext.PUBLISH_DOMAIN
                        + "/content/dam/contentbridge/images/home-card.png",
                map(map(screens.get("en")).get("meta")).get("thumbnail"),
                "the card's image wins even though en also has its own banner");
    }

    @Test
    @DisplayName("a 3-card block where one link does not resolve to a screen is dropped entirely")
    void cardsBlockWithADanglingLinkIsSkipped() {
        buildCardsBlock(
                ContentBridgeTestContext.APP_ROOT + "/renamed",
                ContentBridgeTestContext.APP_ROOT + "/no-such-page",
                ContentBridgeTestContext.APP_ROOT);

        final List<Map<String, Object>> content = subpage1Content();
        assertTrue(content.stream().noneMatch(c -> "list".equals(map(c.get("meta")).get("component"))),
                "no list component - one card's link does not resolve to a real screen");
        assertTrue(content.stream().noneMatch(c -> "card".equals(map(c.get("meta")).get("component"))),
                "the whole 3-card group is skipped, not exported as individual cards");
    }

    @Test
    @DisplayName("a 3-card block with no hyperlink at all on any card is dropped entirely, "
            + "even when the block itself carries an authored property")
    void cardsBlockWithNoLinksIsSkippedEvenWithBlockOwnProperty() {
        // Mirrors luma-site's "feature-support" Cards block: filter="cards", model="cards",
        // modelFields="[classes]" (so the block's own authored content is not empty), three plain
        // icon+text cards, none of them wrapping their text in an <a href>.
        final String base = ContentBridgeTestContext.APP_ROOT + "/subpage-1/jcr:content/root/container/cardsBlock";
        context.build().resource(base,
                "sling:resourceType", "core/franklin/components/block/v1/block",
                "filter", "cards",
                "model", "cards",
                "modelFields", new String[] {"classes"},
                "classes", "feature-support",
                "name", "Cards");
        final String[] texts = {
            "<h2>WORLDWIDE SHIPPING</h2><p>And free return</p>",
            "<h2>ORDER TRACKING</h2><p>Worldwide shipping</p>",
            "<h2>ONLINE SUPPORT</h2><p>With our outdoor specialist</p>",
        };
        for (int i = 0; i < texts.length; i++) {
            context.build().resource(base + "/item" + i,
                    "sling:resourceType", "core/franklin/components/block/v1/block/item",
                    "model", "card",
                    "image", "/content/dam/contentbridge/images/icon" + i + ".png",
                    "text", texts[i]);
        }

        final List<Map<String, Object>> content = subpage1Content();
        assertTrue(content.stream().noneMatch(c -> "list".equals(map(c.get("meta")).get("component"))),
                "no card carries a link, so there is no screen to resolve");
        assertTrue(content.stream().noneMatch(c -> "card".equals(map(c.get("meta")).get("component"))),
                "the whole block is dropped rather than falling through to individual cards");
        assertTrue(content.stream().noneMatch(c -> "cards".equals(map(c.get("meta")).get("component"))),
                "the block itself is not exported unmapped just because it carries 'classes'");
    }

    @Test
    @DisplayName("a coffee-quiz-frescopa block expands into its fixed step sequence from QuizSchemas")
    void quizBlockExpandsIntoFixedSteps() {
        context.build().resource(
                ContentBridgeTestContext.APP_ROOT + "/subpage-1/jcr:content/root/container/coffeeQuiz",
                "sling:resourceType", "core/franklin/components/block/v1/block",
                "model", "coffee-quiz-frescopa",
                "name", "Coffee Quiz Frescopa",
                "completion-url", "/content/frescopa/language-masters/en/quiz-results",
                "sign-in-url", "/content/frescopa/language-masters/en/sign-in",
                "step-event-type", "quizStep");

        final List<Map<String, Object>> content = subpage1Content();
        final List<String> components = content.stream()
                .map(c -> String.valueOf(map(c.get("meta")).get("component")))
                .collect(java.util.stream.Collectors.toList());

        assertTrue(components.contains("dropdown"), "the quiz's steps are individual top-level "
                + "components, not a wrapped 'form': " + components);
        assertTrue(components.contains("formSubmitButton"));
        assertFalse(components.contains("coffee-quiz-frescopa"),
                "the block itself is never exported unmapped - only its synthesised steps are");

        final List<Map<String, Object>> dropdowns = content.stream()
                .filter(c -> "dropdown".equals(map(c.get("meta")).get("component")))
                .collect(java.util.stream.Collectors.toList());
        assertEquals(4, dropdowns.size());
        assertEquals("quiz.favourieCoffeeLocation", map(dropdowns.get(0).get("content")).get("path"));
        assertEquals(java.util.List.of("sand", "sunrise", "villa", "cabbin"),
                ((List<Map<String, Object>>) map(dropdowns.get(0).get("content")).get("options")).stream()
                        .map(o -> o.get("value")).collect(java.util.stream.Collectors.toList()));

        final Map<String, Object> submit = content.stream()
                .filter(c -> "formSubmitButton".equals(map(c.get("meta")).get("component")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("expected a formSubmitButton step"));
        assertEquals("quiz.complete", map(submit.get("content")).get("customEvent"));

        // Every step shares the block's own resource path - ids still have to be distinct per step,
        // same invariant every other component on the page already has to satisfy.
        final long totalIds = content.size();
        final long distinctIds = content.stream().map(c -> map(c.get("meta")).get("id")).distinct().count();
        assertEquals(totalIds, distinctIds, "no two components on the page share an id: " + content);
    }

    @Test
    @DisplayName("a card authored with a Section Link property (plain text, no rich-text anchor) "
            + "resolves link from that property")
    void cardWithSectionLinkProperty() {
        context.build().resource(
                ContentBridgeTestContext.APP_ROOT + "/subpage-1/jcr:content/root/container/bundlesCard",
                "sling:resourceType", "core/franklin/components/block/v1/block/item",
                "model", "card",
                "image", "/content/dam/contentbridge/images/bundles-icon.png",
                "text", "Bundles",
                "link", ContentBridgeTestContext.APP_ROOT + "/coffee");

        final Map<String, Object> card = subpage1Content().stream()
                .filter(c -> "card".equals(map(c.get("meta")).get("component")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("expected a card component"));

        assertEquals("coffee", map(card.get("content")).get("link"),
                "the Section Link property's leaf wins over the (absent) rich-text anchor");
    }

    @Test
    @DisplayName("a 3-card block linking to product pages falls through to individual cards, "
            + "each keeping its own authored fields and a clean link with the sku restored")
    void cardsBlockWithProductLinksFallsThroughWithAuthoredFields() {
        buildCardsBlock(
                "/product.html?productId=_fIgob5sZR",
                "/product.html?productId=vNk3tQP1b",
                "");

        final List<Map<String, Object>> content = subpage1Content();
        assertTrue(content.stream().noneMatch(c -> "list".equals(map(c.get("meta")).get("component"))),
                "none of the three links resolve to a screen - no screenList conversion");

        final List<Map<String, Object>> cards = content.stream()
                .filter(c -> "card".equals(map(c.get("meta")).get("component")))
                .collect(java.util.stream.Collectors.toList());
        assertEquals(3, cards.size(), "all three cards fall through individually: " + content);

        final Map<String, Object> first = map(cards.get(0).get("content"));
        assertEquals("product?productId=_fIgob5sZR", first.get("link"),
                "the leaf's .html is dropped and the authored query string is kept as-is");

        final Map<String, Object> second = map(cards.get(1).get("content"));
        assertEquals("product?productId=vNk3tQP1b", second.get("link"));

        final Map<String, Object> noLink = map(cards.get(2).get("content"));
        assertEquals("#", noLink.get("link"), "no href authored - link stays the rule's own default");
    }
}

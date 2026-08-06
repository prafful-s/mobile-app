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
 * The v2 mapping rules, exercised through the exporter rather than against the rule classes.
 *
 * <p>Testing through the public entry point is deliberate: what matters is the JSON a client
 * receives, and the rules are data that should be free to change shape internally.</p>
 *
 * <p>The authored values here are copied from the we-healthcare package, including the Edge Delivery
 * habit of typing an eyebrow, a headline and a call to action into one rich-text field.</p>
 */
@ExtendWith(AemContextExtension.class)
class ComponentMappingTest {

    private static final String SITE = "/content/we-healthcare";
    private static final String HOME = SITE + "/language-masters/en";

    /** Verbatim from the authored hero on the we-healthcare home page. */
    private static final String HERO_TEXT =
            "<p><strong>Providing affordable</strong></p>\n"
            + "<h1>Medicare options for over 20 years</h1>\n"
            + "<p><a href=\"/content/we-healthcare/language-masters/en/medicare-plan-selection\" "
            + "title=\"Select your plan\">Select your plan</a></p>";

    private static final String CARD_TEXT =
            "<h2>Individual &amp; Family Plans</h2>\n"
            + "<p>You'll find flexible health, dental, and vision options for you and your family.</p>";

    private final AemContext context = new AemContext(ResourceResolverType.JCR_MOCK);

    private PageJsonExporter exporter;

    @BeforeEach
    void setUp() {
        context.create().page(HOME, "", Map.of("jcr:title", "Home"));
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

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> list(final Object value) {
        return (List<Map<String, Object>>) value;
    }

    /** Authors one block under the page root and returns the exported component. */
    private Map<String, Object> export(final boolean v2, final Object... properties) {
        final Object[] all = new Object[properties.length + 2];
        all[0] = "sling:resourceType";
        all[1] = "core/franklin/components/block/v1/block";
        System.arraycopy(properties, 0, all, 2, properties.length);
        context.build().resource(HOME + "/jcr:content/root/block", all);

        final Page home = context.pageManager().getPage(HOME);
        final Map<String, Object> app = v2
                ? exporter.export(home, ExportOptions.of(ExportOptions.SCHEMA_V2, null))
                : exporter.export(home);
        final Map<String, Object> screens = map(app.get("screens"));
        return list(map(screens.get("en")).get("content")).get(0);
    }

    private Map<String, Object> content(final Map<String, Object> component) {
        return map(component.get("content"));
    }

    private String component(final Map<String, Object> component) {
        return String.valueOf(map(component.get("meta")).get("component"));
    }

    @Test
    @DisplayName("a hero becomes a banner and its one rich-text field becomes four")
    void heroBecomesBanner() {
        final Map<String, Object> exported = export(true,
                "model", "hero",
                "image", "/content/dam/we-healthcare/en/images/banner-medicare-hero.jpg",
                "imageAlt", "banner",
                "text", HERO_TEXT,
                "ctastyle", "button",
                "herolayout", "overlay",
                "alignment", "center",
                "verticalalignment", "middle",
                "isfullwidth", "false");

        assertEquals("banner", component(exported));

        final Map<String, Object> content = content(exported);
        assertEquals("Medicare options for over 20 years", content.get("title"),
                "the headline is the h1, not the whole blob");
        assertEquals("Providing affordable", content.get("subtitle"),
                "the eyebrow paragraph, and not the one holding the link");
        assertEquals("Select your plan", content.get("buttonLabel"));
        assertEquals("/content/we-healthcare/language-masters/en/medicare-plan-selection",
                content.get("linkTo"), "the CTA target comes out of the anchor's href");

        assertEquals("/content/dam/we-healthcare/en/images/banner-medicare-hero.jpg",
                content.get("image"));
        assertEquals("center", content.get("horizontalAlignment"));
        assertEquals("middle", content.get("verticalAlignment"));
        assertEquals(Boolean.FALSE, content.get("fullWidth"),
                "Edge Delivery writes some booleans as strings; the rule coerces them");

        assertFalse(content.containsKey("text"), "the source blob must not survive alongside its parts");
        assertFalse(content.containsKey("herolayout"), "authored field names are not the app's");
    }

    @Test
    @DisplayName("a card takes its title from the heading and its subtitle from the paragraph")
    void cardMapping() {
        final Map<String, Object> exported = export(true,
                "model", "card",
                "image", "/content/dam/we-healthcare/en/images/family.jpeg",
                "text", CARD_TEXT,
                "style", "image-top",
                "link", "/content/we-healthcare/language-masters/en/insurance-plans",
                "selectable", "false",
                "overlaybackground", "true");

        assertEquals("card", component(exported));

        final Map<String, Object> content = content(exported);
        assertEquals("Individual & Family Plans", content.get("title"),
                "entities are decoded when text is read out, not left as &amp;");
        assertTrue(String.valueOf(content.get("subtitle")).startsWith("You'll find flexible"));
        assertEquals("image-top", content.get("cardType"));
        assertEquals("/content/we-healthcare/language-masters/en/insurance-plans", content.get("link"));
        assertEquals(Boolean.TRUE, content.get("overlayBackground"));
        assertEquals("titleMedium", content.get("titleVariant"), "supplied by the rule, not authored");
    }

    @Test
    @DisplayName("a title keeps its heading level and a text block keeps its markup")
    void titleAndText() {
        final Map<String, Object> title = export(true,
                "model", "title", "title", "Health Plans Just Right For Your Family",
                "titleType", "h1", "alignment", "center");

        assertEquals("title", component(title));
        assertEquals("Health Plans Just Right For Your Family", content(title).get("text"));
        assertEquals("h1", content(title).get("size"));
        assertEquals("center", content(title).get("textAlignment"));

        context.build().resource(HOME + "/jcr:content/root/block2",
                "sling:resourceType", "core/franklin/components/block/v1/block",
                "model", "text", "text", "<h1>Once you've picked a plan</h1>");

        final Page home = context.pageManager().getPage(HOME);
        final List<Map<String, Object>> all = list(map(map(exporter.export(home,
                ExportOptions.of(ExportOptions.SCHEMA_V2, null)).get("screens")).get("en"))
                .get("content"));
        final Map<String, Object> text = all.get(1);

        assertEquals("htmlText", component(text));
        assertEquals("<h1>Once you've picked a plan</h1>", content(text).get("text"),
                "an htmlText keeps its authored markup - it is the content, not a container for it");
    }

    @Test
    @DisplayName("a button's label and target come from whichever property name the site used")
    void buttonMapping() {
        final Map<String, Object> exported = export(true,
                "model", "button",
                "linkText", "Insurance Plans",
                "link", "/content/we-healthcare/language-masters/en/insurance-plans.html",
                "linkType", "cta-link",
                "textUppercase", "false");

        assertEquals("button", component(exported));
        assertEquals("Insurance Plans", content(exported).get("label"));
        assertEquals("/content/we-healthcare/language-masters/en/insurance-plans.html",
                content(exported).get("linkTo"));
        assertEquals("cta-link", content(exported).get("variant"));
        assertEquals(Boolean.FALSE, content(exported).get("uppercase"));
    }

    @Test
    @DisplayName("an image takes whichever of the four reference property names the site uses")
    void imageMapping() {
        final Map<String, Object> exported = export(true,
                "model", "image",
                "fileReference", "/content/dam/we-healthcare/en/images/doctor.jpg",
                "alt", "A doctor");

        assertEquals("image", component(exported));
        assertEquals("/content/dam/we-healthcare/en/images/doctor.jpg", content(exported).get("image"),
                "fileReference is the classic AEM name; the rule accepts it alongside image");
        assertEquals("A doctor", content(exported).get("alt"));
    }

    @Test
    @DisplayName("v1 is untouched by the mapping rules")
    void v1IsUnchanged() {
        final Map<String, Object> exported = export(false,
                "model", "hero", "text", HERO_TEXT, "herolayout", "overlay");

        assertEquals("hero", component(exported), "v1 still reports the authored component name");
        assertEquals(HERO_TEXT, content(exported).get("text"), "v1 still carries the whole blob");
        assertFalse(content(exported).containsKey("title"), "v1 gained no derived fields");
    }

    @Test
    @DisplayName("a curated model is never re-mapped, even when a rule claims its name")
    void curatedModelsAreNotRemapped() {
        // CardModel already emits the target vocabulary. The card rule matches the name 'card', so
        // without the guard this would be projected a second time - looking for a rich-text blob
        // that is not there and discarding the title the model had already resolved.
        context.build().resource(HOME + "/jcr:content/root/curated",
                "sling:resourceType", "contentbridge/components/card",
                "jcr:title", "Curated title",
                "subtitle", "Curated subtitle",
                "link", "/content/we-healthcare/language-masters/en/plans");

        final Page home = context.pageManager().getPage(HOME);
        final Map<String, Object> exported = list(map(map(exporter.export(home,
                ExportOptions.of(ExportOptions.SCHEMA_V2, null)).get("screens")).get("en"))
                .get("content")).get(0);

        assertEquals("card", component(exported));
        assertEquals("Curated title", content(exported).get("title"));
        assertEquals("Curated subtitle", content(exported).get("subtitle"));
    }

    @Test
    @DisplayName("a component with no rule keeps its authored shape in v2 too")
    void unmappedComponentFallsThrough() {
        // 'video' deliberately: it has neither a mapping rule nor a bundled form schema, so nothing
        // claims it. 'get-offer' would not do - it names a form, and forms are built rather than
        // projected. See getOfferIsAForm below.
        final Map<String, Object> exported = export(true,
                "model", "video",
                "videoUrl", "https://example.com/v.mp4",
                "autoplay", "false");

        assertEquals("video", component(exported));
        assertEquals("https://example.com/v.mp4", content(exported).get("videoUrl"),
                "an unmapped component is projected generically, not dropped");
    }

    @Test
    @DisplayName("a block naming a bundled form is built as a form, not projected")
    void getOfferIsAForm() {
        // The newsletter block on every we-healthcare page. It has a one-field schema, so under v2
        // it is a form component with that field as its content - not a generic block.
        final Map<String, Object> exported = export(true,
                "model", "get-offer",
                "placeholder", "Type your email address",
                "button-label", "Subscribe");

        assertEquals("form", component(exported));
        assertEquals("Subscribe", map(exported.get("meta")).get("submitButtonLabel"),
                "the authored button label becomes the form's submit label");

        @SuppressWarnings("unchecked")
        final List<Map<String, Object>> fields = (List<Map<String, Object>>) exported.get("content");
        assertEquals(1, fields.size());
        assertEquals("textField", map(fields.get(0).get("meta")).get("component"));

        // get-offer's one field carries no id in the configuration, so its name stands in - a field
        // the client cannot address is a field it cannot submit.
        assertEquals("personalEmail.address", map(fields.get(0).get("meta")).get("id"));
    }

    @Test
    @DisplayName("a rule that matches but finds nothing leaves the component alone")
    void emptyProjectionIsAbandoned() {
        // A hero authored with only a field no rule reads. Emitting an empty banner would tell the
        // client less than the authored shape does.
        final Map<String, Object> exported = export(true,
                "model", "hero", "someUnmappedField", "value");

        assertEquals("hero", component(exported));
        assertEquals("value", content(exported).get("someUnmappedField"));
    }

    @Test
    @DisplayName("extracted links are still given the site host")
    void extractedLinksAreExternalised() {
        context.create().page(SITE + "/placeholders", "",
                Map.of("hostname", "https://author-p1-e2.adobeaemcloud.com"));

        final Map<String, Object> content = content(export(true, "model", "hero", "text", HERO_TEXT));

        assertEquals("https://publish-p1-e2.adobeaemcloud.com"
                        + "/content/we-healthcare/language-masters/en/medicare-plan-selection",
                content.get("linkTo"),
                "the host pass runs after mapping, so derived fields are externalised too");
    }

    @Test
    @DisplayName("a mapping rule can also project a sibling 'styles' object")
    void mappingProjectsStyles() {
        final Map<String, Object> exported = export(true,
                "model", "contentCards",
                "surface", "sf_home_screen_cc",
                "color", "#363232",
                "borderRadius", "9");

        assertEquals("contentCards", component(exported));
        assertEquals("sf_home_screen_cc", content(exported).get("surface"));

        final Map<String, Object> styles = map(exported.get("styles"));
        assertEquals("#363232", styles.get("color"));
        assertEquals(9L, styles.get("borderRadius"), "declared as type: number");
        assertEquals("lightgrey", styles.get("borderColor"), "unauthored style keys still get their default");
        assertEquals("white", styles.get("backgroundColor"));
    }

    @Test
    @DisplayName("mappings/index.json and the mappings directory agree")
    void indexMatchesTheDirectory() throws Exception {
        final java.net.URL dir = getClass().getResource("/contentbridge/mappings");
        assertNotNull(dir, "mapping rule directory is missing from the classpath");

        final java.io.File[] files = new java.io.File(dir.toURI()).listFiles(
                (d, n) -> n.endsWith(".json") && !"index.json".equals(n));
        assertNotNull(files);

        final java.util.Set<String> onDisk = new java.util.TreeSet<>();
        for (final java.io.File file : files) {
            onDisk.add(file.getName().replace(".json", ""));
        }

        final com.fasterxml.jackson.databind.ObjectMapper mapper =
                new com.fasterxml.jackson.databind.ObjectMapper();
        final java.util.List<String> indexed = mapper.readValue(
                getClass().getResourceAsStream("/contentbridge/mappings/index.json"),
                new com.fasterxml.jackson.core.type.TypeReference<java.util.List<String>>() { });

        assertEquals(onDisk, new java.util.TreeSet<>(indexed),
                "index.json is out of step with the files - add or remove the name there too");
    }

    @Test
    @DisplayName("a list carries its authored items and its numeric/boolean defaults")
    void listMapping() {
        final Map<String, Object> exported = export(true,
                "model", "list",
                "items", new String[] {"money-transfer", "currency-rates", "atm"},
                "limit", "6",
                "hideSubtitle", "false");

        assertEquals("list", component(exported));

        final Map<String, Object> content = content(exported);
        assertEquals(List.of("money-transfer", "currency-rates", "atm"), content.get("items"));
        assertEquals(6L, content.get("limit"), "declared as type: number");
        assertEquals(Boolean.FALSE, content.get("hideSubtitle"));
        assertEquals("none", content.get("sort"), "unauthored, falls back to its default");
        assertEquals("horizontal", content.get("layout"));
        assertEquals("screenList", content.get("listType"));

        // An unauthored 'type: number' default comes straight off the parsed JSON rather than
        // through the same coercion an authored value gets - Jackson reads a bare integer literal
        // as Integer, not Long, so this compares via Number rather than pinning the boxed type.
        assertEquals(8, ((Number) map(exported.get("styles")).get("gap")).intValue());
    }

    @Test
    @DisplayName("a map carries its flat fields and styles - mapCoordinates is a known gap")
    void mapMapping() {
        final Map<String, Object> exported = export(true,
                "model", "map",
                "text", "ATM",
                "buttonLabel", "Find one");

        assertEquals("map", component(exported));

        final Map<String, Object> content = content(exported);
        assertEquals("ATM", content.get("text"));
        assertEquals("Find one", content.get("buttonLabel"));
        assertEquals(Boolean.FALSE, content.get("showInModal"));
        assertEquals("contained", content.get("buttonVariant"));
        assertFalse(content.containsKey("mapCoordinates"),
                "child-row aggregation is a known gap, not attempted here");

        final Map<String, Object> styles = map(exported.get("styles"));
        assertEquals("blue", styles.get("color"));
        assertEquals("green", styles.get("locationColor"));
    }

    @Test
    @DisplayName("an offer carries its authored link/surface and its defaults")
    void offerMapping() {
        final Map<String, Object> exported = export(true,
                "model", "offer",
                "linkTo", "insurance",
                "surface", "sf_home_offer");

        assertEquals("offer", component(exported));

        final Map<String, Object> content = content(exported);
        assertEquals("insurance", content.get("linkTo"));
        assertEquals("sf_home_offer", content.get("surface"));
        assertEquals(0.34, content.get("opacity"));
        assertEquals(Boolean.TRUE, content.get("showTitle"));
        assertEquals("cover", content.get("imageResize"));
        assertEquals("titleLarge", content.get("titleVariant"));
        assertEquals("center", content.get("horizontalAlignment"));

        final Map<String, Object> styles = map(exported.get("styles"));
        assertEquals("#FFF", styles.get("color"));
        assertEquals(200, ((Number) styles.get("height")).intValue());
    }

    @Test
    @DisplayName("a productImage takes its layout defaults and its authored height")
    void productImageMapping() {
        final Map<String, Object> exported = export(true,
                "model", "productImage",
                "fullWidth", "true");

        assertEquals("productImage", component(exported));
        final Map<String, Object> content = content(exported);
        assertEquals(Boolean.TRUE, content.get("fullWidth"));
        assertEquals("contain", content.get("imageResize"));
        assertEquals(250, ((Number) map(exported.get("styles")).get("height")).intValue());
    }

    @Test
    @DisplayName("a productTitle takes its size/weight/alignment defaults")
    void productTitleMapping() {
        final Map<String, Object> exported = export(true,
                "model", "productTitle",
                "textAlignment", "center");

        assertEquals("productTitle", component(exported));
        final Map<String, Object> content = content(exported);
        assertEquals("large", content.get("size"));
        assertEquals("bold", content.get("weight"));
        assertEquals("center", content.get("textAlignment"));
        assertEquals("#FFF", map(exported.get("styles")).get("color"));
    }

    @Test
    @DisplayName("a timeSlotPicker carries its authored path/options and its defaults")
    void timeSlotPickerMapping() {
        final Map<String, Object> exported = export(true,
                "model", "timeSlotPicker",
                "path", "appointment.dateTime",
                "timeOptions", new String[] {"09:00", "10:00", "11:00"});

        assertEquals("timeSlotPicker", component(exported));
        final Map<String, Object> content = content(exported);
        assertEquals("appointment.dateTime", content.get("path"));
        assertEquals(List.of("09:00", "10:00", "11:00"), content.get("timeOptions"));
        assertEquals(Boolean.TRUE, content.get("prefill"));
        assertEquals(3, ((Number) content.get("numberOfDays")).intValue());
        assertEquals(3, ((Number) content.get("numberOfTimeColumns")).intValue());

        final Map<String, Object> styles = map(exported.get("styles"));
        assertEquals(8, ((Number) styles.get("gap")).intValue());
        assertEquals(8, ((Number) styles.get("padding")).intValue());
    }

    @Test
    @DisplayName("userDetails coerces its authored flag to a real boolean")
    void userDetailsMapping() {
        final Map<String, Object> exported = export(true,
                "model", "userDetails",
                "showLogoutButton", "false");

        assertEquals("userDetails", component(exported));
        assertEquals(Boolean.FALSE, content(exported).get("showLogoutButton"),
                "Edge Delivery writes booleans as strings; the rule coerces them");
    }
}

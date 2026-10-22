package com.edsMobileApp.core.services.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.sling.models.factory.ModelFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import com.day.cq.wcm.api.Page;
import com.edsMobileApp.core.models.impl.GenericComponent;
import com.edsMobileApp.core.schema.SchemaConstants;
import com.edsMobileApp.core.services.FormSchemaService;

import io.wcm.testing.mock.aem.junit5.AemContext;
import io.wcm.testing.mock.aem.junit5.AemContextExtension;

/**
 * Public Bodea authors its service directory as rich text whose links are bare screen keys -
 * {@code <a href="acc-rep-advisory">}. An anchor left inside an htmlText block is not tappable
 * in the app; its renderer hands the href to the system browser, so tapping a service took the
 * user out of the app entirely.
 *
 * <p>{@link ComponentTreeWalker#extractInlineLinkButtons} already turns such an anchor into its own
 * {@code button} carrying {@code linkTo}, which is the shape the app routes through - but it is
 * gated on the site opting in via {@code extractInlineLinks} in its {@code defaults.json}, and no
 * {@code defaults.json} was shipped for {@code public-bodea} at all. These tests pin the flag's
 * effect on a Bodea page, and pin that a site without the flag is unaffected.</p>
 */
@ExtendWith(AemContextExtension.class)
class PublicBodeaInlineLinkTest {

    private static final String ASSET_HOST = "https://publish-p104656-e2246865.adobeaemcloud.com";

    private final AemContext context = new AemContext();

    private ComponentTreeWalker walker() {
        final UrlServiceImpl urlService = new UrlServiceImpl();
        final ModelFactory modelFactory = mock(ModelFactory.class);
        when(modelFactory.isModelAvailableForResource(any())).thenReturn(true);
        when(modelFactory.getModelFromResource(any()))
                .thenAnswer(invocation -> new GenericComponent(invocation.getArgument(0), urlService));

        final FormSchemaService noSchemas = new FormSchemaService() {
            @Override
            public List<Map<String, Object>> getSchema(final String formName) {
                return List.of();
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
        final ComponentTreeWalker walker = new ComponentTreeWalker(modelFactory, urlService, noSchemas,
                new StyleResolver(new String[0]), ComponentMappings.load());
        walker.setSiteDefaults(SiteDefaults.load());
        return walker;
    }

    /** @param site the content site the page belongs to - the only thing the flag keys off */
    private List<Map<String, Object>> export(final String site, final String name, final String text) {
        final Page page = context.create().page("/content/" + site + "/language-masters/en/" + name);
        context.create().resource(page.getPath() + "/jcr:content/root/text",
                "sling:resourceType", SchemaConstants.RT_FRANKLIN_TEXT,
                "text", text, "model", "text");
        return walker().componentsOf(page, ASSET_HOST, true, false);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(final Object value) {
        return (Map<String, Object>) value;
    }

    private static String componentOf(final Map<String, Object> entry) {
        return (String) asMap(entry.get(SchemaConstants.KEY_META)).get(SchemaConstants.KEY_COMPONENT);
    }

    private static List<String> componentsOf(final List<Map<String, Object>> entries) {
        return entries.stream().map(PublicBodeaInlineLinkTest::componentOf).collect(Collectors.toList());
    }


    @Test
    void aFullContentPathResolvesToItsLastSegment() {
        assertEquals("acc-rep-advisory", linkToFor("path",
                "/content/public-bodea/language-masters/en/acc-rep-advisory"),
                "the page picker writes a repository path; the app navigates by screen key");
    }

    @Test
    void aTrailingSlashAndAnHtmlExtensionAreBothStripped() {
        assertEquals("acc-rep-advisory", linkToFor("slash",
                "/content/public-bodea/language-masters/en/acc-rep-advisory/"));
        assertEquals("acc-rep-advisory", linkToFor("html",
                "/content/public-bodea/language-masters/en/acc-rep-advisory.html"));
    }

    @Test
    void aPathTypedAgainstAnAemHostResolvesTheSameWay() {
        assertEquals("acc-rep-advisory", linkToFor("absolute",
                "https://author-p104656-e2246865.adobeaemcloud.com"
                        + "/content/public-bodea/language-masters/en/acc-rep-advisory"),
                "a link authored against one environment's host still names a page of this site");
    }

    @Test
    void anExternalUrlIsLeftExactlyAsAuthored() {
        // screenKeyOf takes the last segment of whatever it is given, so without the external check
        // https://www.adobe.com/legal/terms.html became a button pointing at a "terms" screen.
        final List<Map<String, Object>> components = export("public-bodea", "external",
                "<p><a href=\"https://www.adobe.com/legal/terms.html\">Terms</a></p>");

        assertEquals(List.of("htmlText"), componentsOf(components),
                "somebody else's site is not a screen - no button stands in for it");
        assertEquals("<p><a href=\"https://www.adobe.com/legal/terms.html\">Terms</a></p>",
                asMap(components.get(0).get(SchemaConstants.KEY_CONTENT)).get("text"),
                "the anchor stays, so the renderer still hands it to the browser as before");
    }

    @Test
    void anExternalLinkBesideAnInternalOneDoesNotBlockIt() {
        final List<Map<String, Object>> components = export("public-bodea", "mixed",
                "<p><a href=\"https://www.adobe.com/\">Adobe</a> and "
                        + "<a href=\"/content/public-bodea/language-masters/en/tax-transformations\">Tax</a></p>");

        assertEquals(List.of("htmlText", "button"), componentsOf(components));
        assertEquals("tax-transformations",
                asMap(components.get(1).get(SchemaConstants.KEY_CONTENT)).get("linkTo"));
        final String text = (String) asMap(components.get(0).get(SchemaConstants.KEY_CONTENT)).get("text");
        assertTrue(text.contains("href=\"https://www.adobe.com/\""), "the external anchor survives");
        assertFalse(text.contains("tax-transformations"), "the internal one moved to its button");
    }


    /** @return the {@code linkTo} of the button the anchor was extracted into */
    private String linkToFor(final String name, final String href) {
        final List<Map<String, Object>> components =
                export("public-bodea", name, "<p><a href=\"" + href + "\">Label</a></p>");
        assertEquals(List.of("htmlText", "button"), componentsOf(components),
                "an href naming a page of this site is navigable");
        return (String) asMap(components.get(1).get(SchemaConstants.KEY_CONTENT)).get("linkTo");
    }

    @Test
    void aBareScreenKeyAnchorBecomesItsOwnButton() {
        final List<Map<String, Object>> components = export("public-bodea", "services",
                "<h3>Audit &amp; Assurance</h3>\n<ul>\n<li>"
                        + "<a href=\"acc-rep-advisory\" title=\"services\">Accounting &amp; Reporting</a>"
                        + "</li>\n</ul>");

        assertEquals(List.of("htmlText", "button"), componentsOf(components),
                "the button is inserted directly after the text it came out of");

        final Map<String, Object> content = asMap(components.get(1).get(SchemaConstants.KEY_CONTENT));
        assertEquals("acc-rep-advisory", content.get("linkTo"),
                "an href that is already a bare screen key is used as the screen key as-is");
        assertEquals("Accounting & Reporting", content.get("label"));
        assertEquals("left", content.get("alignment"),
                "an extracted link sits inline with a sentence rather than standing alone as a CTA");
    }

    @Test
    void aCharacterReferenceInTheLabelIsDecoded() {
        // Bodea's service names are full of "&amp;"; the label is plain text on the button, so an
        // entity left encoded here is rendered as the entity itself.
        final List<Map<String, Object>> components = export("public-bodea", "entities",
                "<p><a href=\"advisory-and-transactions\">Mergers &amp; Acquisitions &ndash; EMEA</a></p>");

        assertEquals("Mergers & Acquisitions – EMEA",
                asMap(components.get(1).get(SchemaConstants.KEY_CONTENT)).get("label"));
    }

    @Test
    void theAnchorIsRemovedFromTheTextLabelAndAll() {
        final List<Map<String, Object>> components = export("public-bodea", "removed",
                "<h3>Taxes</h3>\n<ul>\n<li>"
                        + "<a href=\"tax-transformations\">Tax Transformations</a>"
                        + "</li>\n</ul>");

        final String text = (String) asMap(components.get(0).get(SchemaConstants.KEY_CONTENT)).get("text");
        assertFalse(text.contains("href"), "the anchor is gone, not merely de-linked");
        assertFalse(text.contains("Tax Transformations"),
                "the label lives on the button now - leaving it behind would render it twice");
        assertTrue(text.contains("Taxes"), "the surrounding markup is untouched");
    }

    @Test
    void everyAnchorGetsAButtonAndTheOrderIsRenumbered() {
        final List<Map<String, Object>> components = export("public-bodea", "multiple",
                "<ul>\n<li><a href=\"advisory-and-transactions\">Advisory &amp; Transactions</a></li>\n"
                        + "<li><a href=\"tax-transformations\">Tax Transformations</a></li>\n</ul>");

        assertEquals(List.of("htmlText", "button", "button"), componentsOf(components));
        assertEquals(List.of("advisory-and-transactions", "tax-transformations"),
                List.of(asMap(components.get(1).get(SchemaConstants.KEY_CONTENT)).get("linkTo"),
                        asMap(components.get(2).get(SchemaConstants.KEY_CONTENT)).get("linkTo")),
                "buttons keep the order their anchors appeared in");

        for (int order = 0; order < components.size(); order++) {
            assertEquals(order,
                    asMap(components.get(order).get(SchemaConstants.KEY_META)).get(SchemaConstants.KEY_ORDER),
                    "meta.order matches the position the inserted buttons left each component in");
        }
    }

    @Test
    void aDeadAnchorIsDelinkedInPlaceRatherThanDropped() {
        // 15 of the 35 anchors on Bodea are href="#" - dead on the web too, but their label is copy.
        final List<Map<String, Object>> components = export("public-bodea", "dead",
                "<p><a href=\"#\">Read more</a></p>");

        assertEquals(List.of("htmlText"), componentsOf(components), "a dead href has no screen to point at");
        assertEquals("<p><a href=\"#\">Read more</a></p>",
                asMap(components.get(0).get(SchemaConstants.KEY_CONTENT)).get("text"),
                "with no navigable anchor anywhere in the block the text is left exactly as authored");
    }

    @Test
    void aSiteWithoutTheFlagKeepsItsAnchorsInline() {
        final List<Map<String, Object>> components = export("citi-signal", "support",
                "<p><a href=\"contact\">Contact us</a></p>");

        assertEquals(List.of("htmlText"), componentsOf(components),
                "extraction is opt-in per site - citi-signal ships no extractInlineLinks");
        assertTrue(((String) asMap(components.get(0).get(SchemaConstants.KEY_CONTENT)).get("text"))
                        .contains("href=\"contact\""),
                "the anchor is left exactly where it was authored");
    }
}

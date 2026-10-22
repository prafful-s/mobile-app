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
 * How one htmlText block's links render was a per-site choice spread over two independent flags:
 * {@code extractInlineLinks} puts every button after the whole text, {@code splitInlineLinks} puts
 * each button after the block that held its link, and a site setting neither leaves anchors inline
 * for the app to navigate itself. One site, one answer - but a services directory and a "Read More"
 * teaser want different answers on the same site, and often on the same page.
 *
 * <p>An authored {@code mobilelinkstyle} now overrides the site for the block that carries it.
 * These tests pin each value against a site whose own setting says something else, so a passing
 * test cannot be the site's doing.</p>
 */
@ExtendWith(AemContextExtension.class)
class InlineLinkRenderTest {

    private static final String ASSET_HOST = "https://publish-p104656-e2246865.adobeaemcloud.com";

    /** Ships {@code extractInlineLinks}. */
    private static final String EXTRACT_SITE = "secur-financial";
    /** Ships {@code splitInlineLinks}. */
    private static final String SPLIT_SITE = "public-bodea";
    /** Ships neither. */
    private static final String INLINE_SITE = "citi-signal";

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

    /** @param style the authored {@code mobilelinkstyle}, or null to leave the property off */
    private List<Map<String, Object>> export(final String site, final String name,
                                             final String style, final String text) {
        final Page page = context.create().page("/content/" + site + "/language-masters/en/" + name);
        final String resource = page.getPath() + "/jcr:content/root/text";
        if (style == null) {
            context.create().resource(resource,
                    "sling:resourceType", SchemaConstants.RT_FRANKLIN_TEXT, "model", "text", "text", text);
        } else {
            context.create().resource(resource,
                    "sling:resourceType", SchemaConstants.RT_FRANKLIN_TEXT, "model", "text", "text", text,
                    "mobilelinkstyle", style);
        }
        return walker().componentsOf(page, ASSET_HOST, true, false);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(final Object value) {
        return (Map<String, Object>) value;
    }

    private static List<String> componentsOf(final List<Map<String, Object>> entries) {
        return entries.stream()
                .map(e -> (String) asMap(e.get(SchemaConstants.KEY_META)).get(SchemaConstants.KEY_COMPONENT))
                .collect(Collectors.toList());
    }

    private static String textOf(final Map<String, Object> entry) {
        return (String) asMap(entry.get(SchemaConstants.KEY_CONTENT)).get("text");
    }

    private static final String TWO_PARAGRAPHS =
            "<p>Legal work. <a href=\"legal-advisory-services\">Read More</a></p>"
                    + "<p>Tax work. <a href=\"tax-transformations\">Read More</a></p>";

    /** Public Bodea's services directory, as authored: a heading per group, its links in a list. */
    private static final String SERVICES_DIRECTORY =
            "<h3>Audit &amp; Assurance</h3><ul>"
                    + "<li><a href=\"acc-rep-advisory\">Accounting &amp; Reporting</a></li>"
                    + "<li><a href=\"assurance-services\">Assurance Services</a></li></ul>"
                    + "<h3>Consulting</h3><ul>"
                    + "<li><a href=\"customer-and-marketing-services\">Customer &amp; Marketing</a></li>"
                    + "<li><a href=\"business-services\">Human Capital</a></li></ul>";

    @Test
    void eachHeadingKeepsItsOwnButtonsInsteadOfStackingThemAtTheEnd() {
        final List<Map<String, Object>> components =
                export(SPLIT_SITE, "services-grouped", null, SERVICES_DIRECTORY);

        assertEquals(List.of("htmlText", "button", "button", "htmlText", "button", "button"),
                componentsOf(components),
                "the author's grouping survives - heading, its links, next heading");
        assertEquals("<h3>Audit &amp; Assurance</h3>", textOf(components.get(0)));
        assertEquals("<h3>Consulting</h3>", textOf(components.get(3)));
        assertEquals(List.of("Accounting & Reporting", "Assurance Services",
                        "Customer & Marketing", "Human Capital"),
                components.stream()
                        .filter(c -> "button".equals(asMap(c.get(SchemaConstants.KEY_META))
                                .get(SchemaConstants.KEY_COMPONENT)))
                        .map(c -> asMap(c.get(SchemaConstants.KEY_CONTENT)).get("label"))
                        .collect(Collectors.toList()));
    }

    @Test
    void aListIsNotTornInHalfAroundTheLinkItHeld() {
        // Splitting at </li> left an unclosed <ul> on one part and an orphan </ul> opening the next.
        for (final Map<String, Object> component :
                export(SPLIT_SITE, "balanced", null, SERVICES_DIRECTORY)) {
            final Object text = asMap(component.get(SchemaConstants.KEY_CONTENT)).get("text");
            if (text instanceof String) {
                final String html = (String) text;
                assertFalse(html.contains("<ul>") || html.contains("</ul>"),
                        "a list whose every item was a link leaves nothing behind: " + html);
                assertFalse(html.contains("<li>"), "no stray list item: " + html);
            }
        }
    }

    @Test
    void aLinkInAParagraphStillSplitsAtThatParagraph() {
        // we-healthcare's own shape - unchanged by moving the list boundary from </li> to </ul>.
        final List<Map<String, Object>> components = export(SPLIT_SITE, "paragraphs", null,
                "<p>Legal work. <a href=\"legal-advisory-services\">Read More</a></p>"
                        + "<p>Tax work. <a href=\"tax-transformations\">Read More</a></p>");

        assertEquals(List.of("htmlText", "button", "htmlText", "button"), componentsOf(components));
        assertEquals("<p>Legal work. </p>", textOf(components.get(0)));
        assertEquals("<p>Tax work. </p>", textOf(components.get(2)));
    }

    @Test
    void linkKeepsTheAnchorsInlineOnASiteThatWouldExtractThem() {
        final List<Map<String, Object>> components =
                export(EXTRACT_SITE, "as-link", "link", TWO_PARAGRAPHS);

        assertEquals(List.of("htmlText"), componentsOf(components), "no button stands in for the links");
        assertEquals(TWO_PARAGRAPHS, textOf(components.get(0)),
                "the block is passed through exactly as authored, for the app to navigate itself");
    }

    @Test
    void buttonExtractsOnASiteThatWouldLeaveThemInline() {
        final List<Map<String, Object>> components =
                export(INLINE_SITE, "as-button", "button", TWO_PARAGRAPHS);

        assertEquals(List.of("htmlText", "button", "button"), componentsOf(components),
                "both buttons after the whole text - citi-signal itself sets neither flag");
        assertEquals("legal-advisory-services",
                asMap(components.get(1).get(SchemaConstants.KEY_CONTENT)).get("linkTo"));
    }

    @Test
    void splitPutsEachButtonAfterTheParagraphThatHeldItsLink() {
        final List<Map<String, Object>> components =
                export(INLINE_SITE, "as-split", "split", TWO_PARAGRAPHS);

        assertEquals(List.of("htmlText", "button", "htmlText", "button"), componentsOf(components),
                "the text is split so each button lands where its link was");
        assertEquals("<p>Legal work. </p>", textOf(components.get(0)));
        assertEquals("<p>Tax work. </p>", textOf(components.get(2)));
    }

    @Test
    void anUnsetStyleLeavesEverySiteOnItsOwnSetting() {
        assertEquals(List.of("htmlText", "button", "button"),
                componentsOf(export(EXTRACT_SITE, "default-extract", null, TWO_PARAGRAPHS)),
                "secur-financial extracts");
        assertEquals(List.of("htmlText", "button", "htmlText", "button"),
                componentsOf(export(SPLIT_SITE, "default-split", null, TWO_PARAGRAPHS)),
                "public-bodea splits");
        assertEquals(List.of("htmlText"),
                componentsOf(export(INLINE_SITE, "default-inline", null, TWO_PARAGRAPHS)),
                "citi-signal leaves them inline");
    }

    @Test
    void theStyleItselfNeverReachesTheClient() {
        // No mapping rule consumes it, so appendUnconsumedExtras would pass it straight through.
        for (final String style : new String[] {"link", "button", "split"}) {
            final List<Map<String, Object>> components =
                    export(EXTRACT_SITE, "consumed-" + style, style, TWO_PARAGRAPHS);
            for (final Map<String, Object> component : components) {
                assertFalse(asMap(component.get(SchemaConstants.KEY_CONTENT)).containsKey("mobilelinkstyle"),
                        "mobilelinkstyle is an instruction to the exporter, not content");
            }
        }
    }

    @Test
    void anUnrecognisedStyleFallsBackToTheSiteRatherThanGuessing() {
        assertEquals(List.of("htmlText", "button", "button"),
                componentsOf(export(EXTRACT_SITE, "typo", "Buton", TWO_PARAGRAPHS)),
                "a typo must not silently turn a site's own choice into something else");
    }

    @Test
    void splitDecodesACharacterReferenceInItsLabel() {
        final List<Map<String, Object>> components = export(SPLIT_SITE, "split-entity", null,
                "<p><a href=\"acc-rep-advisory\">Mergers &amp; Acquisitions</a></p>");

        assertEquals("Mergers & Acquisitions",
                asMap(components.get(0).get(SchemaConstants.KEY_CONTENT)).get("label"),
                "split built its label with a tag-stripping regex, which left the entity encoded");
    }

    @Test
    void splitLeavesAnExternalLinkAsAuthored() {
        final List<Map<String, Object>> components = export(SPLIT_SITE, "split-external", null,
                "<p>See <a href=\"https://www.adobe.com/legal/terms.html\">Terms</a>. "
                        + "<a href=\"acc-rep-advisory\">Advisory</a></p>");

        assertEquals(List.of("htmlText", "button"), componentsOf(components),
                "one button, for the internal link only");
        assertEquals("acc-rep-advisory",
                asMap(components.get(1).get(SchemaConstants.KEY_CONTENT)).get("linkTo"),
                "the external URL used to resolve to a \"terms\" screen that does not exist");
        assertTrue(textOf(components.get(0)).contains("href=\"https://www.adobe.com/legal/terms.html\""),
                "its anchor survives whole, so the browser still receives it");
    }
}

package com.edsMobileApp.core.services.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.apache.sling.models.factory.ModelFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import com.day.cq.wcm.api.Page;
import com.edsMobileApp.core.models.impl.GenericComponent;
import com.edsMobileApp.core.schema.SchemaConstants;

import io.wcm.testing.mock.aem.junit5.AemContext;
import io.wcm.testing.mock.aem.junit5.AemContextExtension;

/**
 * Every site gets each navigable link in a rich-text block as its own button, right after the
 * paragraph that held it; no per-site setting is involved.
 */
@ExtendWith(AemContextExtension.class)
class ExtractInlineLinksTest {

    private final AemContext context = new AemContext();

    @SuppressWarnings("unchecked")
    private Map<String, Object> exportForm(final String model, final Object... props) {
        final Page page = context.create().page("/content/s/en/p");
        context.create().resource(page.getPath() + "/jcr:content/root");
        final List<Object> all = new ArrayList<>(List.of("sling:resourceType",
                SchemaConstants.RT_FRANKLIN_BLOCK, "model", model));
        all.addAll(List.of(props));
        context.create().resource(page.getPath() + "/jcr:content/root/form", all.toArray());

        final UrlServiceImpl urlService = new UrlServiceImpl();
        final ModelFactory modelFactory = mock(ModelFactory.class);
        when(modelFactory.isModelAvailableForResource(any())).thenReturn(true);
        when(modelFactory.getModelFromResource(any()))
                .thenAnswer(invocation -> new GenericComponent(invocation.getArgument(0), urlService));
        final FormSchemaServiceImpl schemas = new FormSchemaServiceImpl();
        schemas.activate();
        final ComponentTreeWalker walker = new ComponentTreeWalker(modelFactory, urlService, schemas,
                new StyleResolver(new String[0]), ComponentMappings.load());
        final List<Map<String, Object>> tree =
                walker.componentsOf(page, "https://assets.example.com", true, false);
        return tree.stream()
                .filter(c -> "form".equals(((Map<String, Object>) c.get(SchemaConstants.KEY_META))
                        .get(SchemaConstants.KEY_COMPONENT)))
                .findFirst().orElseThrow(() -> new AssertionError("no form in " + tree));
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> fields(final Map<String, Object> form) {
        return (List<Map<String, Object>>) form.get(SchemaConstants.KEY_CONTENT);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> meta(final Map<String, Object> component) {
        return (Map<String, Object>) component.get(SchemaConstants.KEY_META);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> content(final Map<String, Object> component) {
        return (Map<String, Object>) component.get(SchemaConstants.KEY_CONTENT);
    }

    private List<Map<String, Object>> exportText(final String site) {
        return exportText(site,
                "<p>Intro</p><p><a href=\"medicare-insurance-basics\">Medicare Insurance Basics</a></p>");
    }

    private List<Map<String, Object>> exportText(final String site, final String html) {
        final Page page = context.create().page("/content/" + site + "/language-masters/en");
        context.create().resource(page.getPath() + "/jcr:content/root");
        context.create().resource(page.getPath() + "/jcr:content/root/text1",
                "sling:resourceType", SchemaConstants.RT_FRANKLIN_TEXT,
                "text", html);
        final UrlServiceImpl urlService = new UrlServiceImpl();
        final ModelFactory modelFactory = mock(ModelFactory.class);
        when(modelFactory.isModelAvailableForResource(any())).thenReturn(true);
        when(modelFactory.getModelFromResource(any()))
                .thenAnswer(invocation -> new GenericComponent(invocation.getArgument(0), urlService));
        final FormSchemaServiceImpl schemas = new FormSchemaServiceImpl();
        schemas.activate();
        final ComponentTreeWalker walker = new ComponentTreeWalker(modelFactory, urlService, schemas,
                new StyleResolver(new String[0]), ComponentMappings.load());
        walker.setSiteDefaults(SiteDefaults.load());
        return walker.componentsOf(page, "https://assets.example.com", true, false);
    }

    private static List<String> componentNames(final List<Map<String, Object>> tree) {
        return tree.stream().map(c -> String.valueOf(meta(c).get("component"))).collect(Collectors.toList());
    }

    @Test
    void weHealthcareAndCarveloExtractTheLinkIntoAButton() {
        for (final String site : new String[] {"we-healthcare", "carvelo"}) {
            assertEquals(List.of("htmlText", "button"), componentNames(exportText(site)), site);
        }
    }

    @Test
    void anExternalLinkStaysInlineWhateverWayItIsTyped() {
        for (final String href : new String[] {"https://www.adobe.com/legal/terms.html", "http://example.com",
            "//example.com/x", "www.example.com"}) {
            final List<Map<String, Object>> tree = exportText("frescopa",
                    "<p>Go <a href=\"" + href + "\">Link</a> now</p><p>End</p>");
            assertEquals(List.of("htmlText"), componentNames(tree), href);
            assertEquals("<p>Go <a href=\"" + href + "\">Link</a> now</p><p>End</p>", textOf(tree.get(0)), href);
        }
    }

    @Test
    void everySiteSplitsByDefault() {
        assertEquals(List.of("htmlText", "button"), componentNames(exportText("frescopa")));
    }

    private static String textOf(final Map<String, Object> component) {
        return String.valueOf(content(component).get("text"));
    }

    private static String labelOf(final Map<String, Object> component) {
        return String.valueOf(content(component).get("label"));
    }

    private static final String SEVERAL = "<p>Start</p><p><a href=\"first\">First</a></p><p>Middle</p>"
            + "<p><a href=\"second\">Second</a></p><p>End</p>";

    @Test
    void weHealthcareKeepsEachButtonAtItsOwnParagraph() {
        final List<Map<String, Object>> tree = exportText("we-healthcare", SEVERAL);
        assertEquals(List.of("htmlText", "button", "htmlText", "button", "htmlText"), componentNames(tree));
        assertEquals("<p>Start</p>", textOf(tree.get(0)));
        assertEquals("First", labelOf(tree.get(1)));
        assertEquals("<p>Middle</p>", textOf(tree.get(2)));
        assertEquals("Second", labelOf(tree.get(3)));
        assertEquals("<p>End</p>", textOf(tree.get(4)));
        assertEquals(List.of(0, 1, 2, 3, 4), tree.stream().map(c -> meta(c).get("order"))
                .collect(Collectors.toList()));
    }

    @Test
    void aBlockThatOpensWithALinkStartsWithItsButton() {
        final List<Map<String, Object>> tree = exportText("we-healthcare",
                "<p><a href=\"first\">First</a></p><p>Rest</p>");
        assertEquals(List.of("button", "htmlText"), componentNames(tree));
        assertEquals("<p>Rest</p>", textOf(tree.get(1)));
    }

    @Test
    void twoLinksInOneParagraphGiveTwoButtonsAfterIt() {
        final List<Map<String, Object>> tree = exportText("we-healthcare",
                "<p>See <a href=\"first\">First</a> and <a href=\"second\">Second</a>.</p><p>End</p>");
        assertEquals(List.of("htmlText", "button", "button", "htmlText"), componentNames(tree));
        assertEquals("<p>See  and .</p>", textOf(tree.get(0)));
        assertEquals("First", labelOf(tree.get(1)));
        assertEquals("Second", labelOf(tree.get(2)));
    }

    @Test
    void aLinkThatGoesNowhereStaysAsItsLabel() {
        final List<Map<String, Object>> tree = exportText("we-healthcare",
                "<p>Go <a href=\"#top\">up</a></p><p><a href=\"first\">First</a></p>");
        assertEquals(List.of("htmlText", "button"), componentNames(tree));
        assertEquals("<p>Go up</p>", textOf(tree.get(0)));
    }

    @Test
    void carveloKeepsEachButtonAtItsOwnParagraphToo() {
        final List<Map<String, Object>> tree = exportText("carvelo", SEVERAL);
        assertEquals(List.of("htmlText", "button", "htmlText", "button", "htmlText"), componentNames(tree));
    }
}

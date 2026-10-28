package com.edsMobileApp.core.services.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Set;

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
 * Unlike the {@code cards} block (see {@link ListStylingTest}, {@link CardsAsListTest}), an
 * {@code action-button} is matched by a real rule ({@code actionButton.json}), so it goes through
 * the same generic per-child path as {@code card} or {@code image} - the one that already calls
 * {@code mergeCustomStyles} for every matched component. This test drives that full path (not a
 * package-private method directly, since the merge itself is private) to confirm the block's own
 * {@code custommobilestyle} - the field added to its EDS model's Mobile Specific tab - actually
 * reaches the exported button's styles, the same way it already does for a card.
 */
@ExtendWith(AemContextExtension.class)
class ActionButtonCustomStyleTest {

    private final AemContext context = new AemContext();

    /**
     * A real {@link org.apache.sling.models.factory.ModelFactory} would resolve to
     * {@link com.edsMobileApp.core.models.impl.FranklinComponentModel} for a Franklin block
     * resource, which only delegates to {@link GenericComponent} (see its own Javadoc) - so
     * mocking that resolution directly, rather than registering the real Sling Model machinery,
     * exercises the identical {@code getContent()} the production path would.
     */
    private ComponentTreeWalker walkerWithMockedModelFactory() {
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

        return new ComponentTreeWalker(modelFactory, urlService, noSchemas,
                new StyleResolver(new String[0]), ComponentMappings.load());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> stylesOf(final List<Map<String, Object>> components, final String component) {
        return (Map<String, Object>) entryFor(components, component).getOrDefault(SchemaConstants.KEY_STYLES, Map.of());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> contentOf(final List<Map<String, Object>> components, final String component) {
        return (Map<String, Object>) entryFor(components, component).get(SchemaConstants.KEY_CONTENT);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> entryFor(final List<Map<String, Object>> components, final String component) {
        for (final Map<String, Object> entry : components) {
            final Map<String, Object> meta = (Map<String, Object>) entry.get(SchemaConstants.KEY_META);
            if (component.equals(meta.get(SchemaConstants.KEY_COMPONENT))) {
                return entry;
            }
        }
        throw new AssertionError("no '" + component + "' component in " + components);
    }

    @Test
    void htmlTextOmitsUnavailableStylesAndPreservesCustomOverrides() {
        final Page page = context.create().page("/content/s/en/text-styles");
        context.create().resource(page.getPath() + "/jcr:content/root");
        context.create().resource(page.getPath() + "/jcr:content/root/text",
                "sling:resourceType", SchemaConstants.RT_FRANKLIN_BLOCK,
                "model", "text",
                "text", "<p>Body</p>");
        context.create().resource(page.getPath() + "/jcr:content/root/styled-text",
                "sling:resourceType", SchemaConstants.RT_FRANKLIN_BLOCK,
                "model", "text",
                "text", "<p>Styled</p>",
                "padding", "16",
                "color", "#123456",
                "custommobilestyle", new String[] {"padding:0", "color:#abcdef"});

        final List<Map<String, Object>> components =
                walkerWithMockedModelFactory().componentsOf(page, "https://assets.example.com", true, false);

        assertEquals(2, components.size());
        assertEquals("htmlText", ((Map<?, ?>) components.get(0).get(SchemaConstants.KEY_META)).get("component"));
        assertFalse(components.get(0).containsKey(SchemaConstants.KEY_STYLES));
        assertEquals(Map.of("text", "<p>Body</p>"), components.get(0).get(SchemaConstants.KEY_CONTENT));
        assertEquals(Map.of("padding", 0L, "color", "#abcdef"), components.get(1).get(SchemaConstants.KEY_STYLES));
    }

    @Test
        @SuppressWarnings("unchecked")
        void aWebViewExportsUrlAndCustomMobileStyles() {
        final Page page = context.create().page("/content/s/en/web-view");
        context.create().resource(page.getPath() + "/jcr:content/root");
        context.create().resource(page.getPath() + "/jcr:content/root/web-view",
            "sling:resourceType", SchemaConstants.RT_FRANKLIN_BLOCK,
            "model", "web-view",
            "mobileNickname", "Adobe",
            "mobileUrl", "https://www.adobe.com");

        final ComponentTreeWalker walker = walkerWithMockedModelFactory();
        final Map<String, Object> entry = entryFor(
            walker.componentsOf(page, "https://assets.example.com", true, false), "webView");
        assertEquals(Map.of("url", "https://www.adobe.com"), entry.get(SchemaConstants.KEY_CONTENT));
        final Map<String, Object> meta = (Map<String, Object>) entry.get(SchemaConstants.KEY_META);
        assertEquals(0, meta.get("order"));
        assertFalse(meta.get("id").toString().isEmpty());
        assertFalse(entry.containsKey(SchemaConstants.KEY_STYLES));

        context.create().resource(page.getPath() + "/jcr:content/root/styled-web-view",
            "sling:resourceType", SchemaConstants.RT_FRANKLIN_BLOCK,
            "model", "web-view",
            "mobileUrl", "https://www.adobe.com",
            "custommobilestyle", new String[] {"height:500", "backgroundColor:#ffffff"});
        final List<Map<String, Object>> components =
            walker.componentsOf(page, "https://assets.example.com", true, false);
        assertEquals(Map.of("height", 500L, "backgroundColor", "#ffffff"),
            components.get(1).get(SchemaConstants.KEY_STYLES));
        assertEquals(Map.of("url", "https://www.adobe.com"),
            components.get(1).get(SchemaConstants.KEY_CONTENT));
        }

        @Test
    void anActionButtonsOwnCustomMobileStyleReachesItsExportedStyles() {
        final Page page = context.create().page("/content/s/en/p");
        context.create().resource(page.getPath() + "/jcr:content/root");
        context.create().resource(page.getPath() + "/jcr:content/root/cta",
                "sling:resourceType", SchemaConstants.RT_FRANKLIN_BLOCK,
                "model", "action-button",
                "link", "/content/s/en/somewhere",
                "label", "Book now",
                "style", "cta-button",
                "custommobilestyle", new String[] {"marginTop:20"});

        final List<Map<String, Object>> components =
                walkerWithMockedModelFactory().componentsOf(page, "https://assets.example.com", true, false);

        assertEquals(20L, stylesOf(components, "button").get("marginTop"),
                "the block's own custommobilestyle must merge onto the exported button's styles");
    }

    /**
     * actionButton.json is a {@code skeleton} rule, so anything left in the authored content that
     * no field targeted gets carried through as an "extra" (see appendUnconsumedExtras) -
     * custommobilestyle is consumed separately, into styles, not by any of the rule's own fields,
     * so without an explicit exclusion it looks unconsumed and leaks a raw duplicate into content
     * too.
     */
    @Test
    void customMobileStyleDoesNotLeakIntoASkeletonRulesContent() {
        final Page page = context.create().page("/content/s/en/p");
        context.create().resource(page.getPath() + "/jcr:content/root");
        context.create().resource(page.getPath() + "/jcr:content/root/cta",
                "sling:resourceType", SchemaConstants.RT_FRANKLIN_BLOCK,
                "model", "action-button",
                "link", "/content/s/en/somewhere",
                "label", "Book now",
                "style", "cta-button",
                "custommobilestyle", new String[] {"alignment:center"});

        final List<Map<String, Object>> components =
                walkerWithMockedModelFactory().componentsOf(page, "https://assets.example.com", true, false);

        assertFalse(contentOf(components, "button").containsKey("custommobilestyle"),
                "custommobilestyle already reached styles - content must not carry a raw duplicate");
    }

    /** The core {@code button} (button.json) takes the same {@code custommobilestyle} as an action-button. */
    @Test
    void aCoreButtonsCustomMobileStyleReachesItsStylesAndStaysOutOfContent() {
        final Page page = context.create().page("/content/s/en/p");
        context.create().resource(page.getPath() + "/jcr:content/root");
        context.create().resource(page.getPath() + "/jcr:content/root/cta",
                "sling:resourceType", SchemaConstants.RT_FRANKLIN_BLOCK,
                "model", "button",
                "linkText", "Apply for New Card",
                "mobilebuttonvariant", "elevated",
                "custommobilestyle", new String[] {"marginTop:20", "width:200"});

        final List<Map<String, Object>> components =
                walkerWithMockedModelFactory().componentsOf(page, "https://assets.example.com", true, false);

        assertEquals(20L, stylesOf(components, "button").get("marginTop"));
        assertEquals(200L, stylesOf(components, "button").get("width"));
        assertEquals("elevated", contentOf(components, "button").get("variant"));
        assertFalse(contentOf(components, "button").containsKey("custommobilestyle"),
                "custommobilestyle reached styles - content must not carry a raw duplicate");
    }
}

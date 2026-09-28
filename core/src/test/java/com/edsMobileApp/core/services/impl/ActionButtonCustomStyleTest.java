package com.edsMobileApp.core.services.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
        for (final Map<String, Object> entry : components) {
            final Map<String, Object> meta = (Map<String, Object>) entry.get(SchemaConstants.KEY_META);
            if (component.equals(meta.get(SchemaConstants.KEY_COMPONENT))) {
                final Object styles = entry.get(SchemaConstants.KEY_STYLES);
                return styles instanceof Map ? (Map<String, Object>) styles : Map.of();
            }
        }
        throw new AssertionError("no '" + component + "' component in " + components);
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
}

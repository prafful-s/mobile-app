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
 * title.json declares no styles of its own, so a title had nothing in its exported entry but
 * content - which made it easy to assume the Custom Mobile Style field added to the title model's
 * Mobile Specific tab did nothing for it. It does: {@code mergeCustomStyles} runs on the generic
 * per-child path for every matched component, whatever its rule declares, the same way it already
 * does for an action button ({@link ActionButtonCustomStyleTest}) or a card.
 *
 * <p>The companion case is the one that actually bit. The field is a multifield, and
 * {@link CustomMobileStyle#asMap} reads only a {@code List} or a {@code String[]}; a single-valued
 * String property is silently ignored. Authoring one row through the Sling POST servlet without a
 * {@code @TypeHint} produces exactly that, and the style vanishes with no error anywhere.</p>
 */
@ExtendWith(AemContextExtension.class)
class TitleCustomStyleTest {

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
        return new ComponentTreeWalker(modelFactory, urlService, noSchemas,
                new StyleResolver(new String[0]), ComponentMappings.load());
    }

    private Map<String, Object> titleEntry(final String name, final Object customMobileStyle) {
        final Page page = context.create().page("/content/s/en/" + name);
        final String title = page.getPath() + "/jcr:content/root/title";
        if (customMobileStyle == null) {
            context.create().resource(title,
                    "sling:resourceType", SchemaConstants.RT_FRANKLIN_TITLE,
                    "title", "Primary account", "mobilesize", "medium", "mobileweight", "bold");
        } else {
            context.create().resource(title,
                    "sling:resourceType", SchemaConstants.RT_FRANKLIN_TITLE,
                    "title", "Primary account", "mobilesize", "medium", "mobileweight", "bold",
                    "custommobilestyle", customMobileStyle);
        }
        final List<Map<String, Object>> components =
                walker().componentsOf(page, null, true, false);
        assertEquals("title", asMeta(components.get(0)).get(SchemaConstants.KEY_COMPONENT));
        return components.get(0);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMeta(final Map<String, Object> entry) {
        return (Map<String, Object>) entry.get(SchemaConstants.KEY_META);
    }

    @Test
    void aTitlesCustomMobileStyleReachesItsExportedStyles() {
        final Map<String, Object> entry =
                titleEntry("styled", new String[] {"color:#FFFFFF", "marginTop:8"});

        assertEquals(Map.of("color", "#FFFFFF", "marginTop", 8L),
                entry.get(SchemaConstants.KEY_STYLES),
                "title.json declares no styles, so these are the field's alone");
    }

    @Test
    void aTitleWithNoCustomStyleCarriesNoStylesAtAll() {
        assertFalse(titleEntry("plain", null).containsKey(SchemaConstants.KEY_STYLES),
                "an empty styles map is dropped rather than exported empty");
    }

    @Test
    void aSingleValuedStringIsIgnoredRatherThanRead() {
        // What the Sling POST servlet writes for a one-row multifield without @TypeHint=String[].
        assertFalse(titleEntry("single-valued", "color:#FFFFFF").containsKey(SchemaConstants.KEY_STYLES),
                "asMap reads a List or a String[] only - a String is not one row, it is ignored");
    }

    @Test
    void theContentIsUntouchedByTheStyleField() {
        final Map<String, Object> entry =
                titleEntry("content-intact", new String[] {"color:#FFFFFF"});

        assertEquals(Map.of("size", "medium", "text", "Primary account",
                        "weight", "bold", "textAlignment", "left"),
                entry.get(SchemaConstants.KEY_CONTENT),
                "custommobilestyle is consumed into styles, never leaked into content");
    }
}

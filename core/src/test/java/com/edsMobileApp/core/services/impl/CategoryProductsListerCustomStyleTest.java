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
 * A lister's {@code custommobilestyle} rows that name a list field ({@code imageWidth:120},
 * {@code itemHeight:130}) set that field in the exported content, replacing the rule's 150
 * default, rather than landing in {@code styles} where the client never reads them. Rows naming
 * nothing the rule reads ({@code gap:4}) still go to {@code styles}, as they always have.
 * Drives the full walker path, the same way {@link ActionButtonCustomStyleTest} does.
 */
@ExtendWith(AemContextExtension.class)
class CategoryProductsListerCustomStyleTest {

    private final AemContext context = new AemContext();

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
    private static Map<String, Object> entryOf(final List<Map<String, Object>> components, final String component) {
        for (final Map<String, Object> entry : components) {
            final Map<String, Object> meta = (Map<String, Object>) entry.get(SchemaConstants.KEY_META);
            if (component.equals(meta.get(SchemaConstants.KEY_COMPONENT))) {
                return entry;
            }
        }
        throw new AssertionError("no '" + component + "' component in " + components);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> part(final Map<String, Object> entry, final String key) {
        final Object value = entry.get(key);
        return value instanceof Map ? (Map<String, Object>) value : Map.of();
    }

    @Test
    void customMobileStyleRowsNamingListFieldsSetThoseFields() {
        final Page page = context.create().page("/content/s/en/p");
        context.create().resource(page.getPath() + "/jcr:content/root");
        context.create().resource(page.getPath() + "/jcr:content/root/lister",
                "sling:resourceType", SchemaConstants.RT_FRANKLIN_BLOCK,
                "model", "category-products-lister",
                "mobilelayout", "horizontal",
                "custommobilestyle", new String[] {"imageWidth:120", "itemHeight:130", "gap:4"});

        final Map<String, Object> list = entryOf(
                walkerWithMockedModelFactory().componentsOf(page, "https://assets.example.com", true, false), "list");
        final Map<String, Object> content = part(list, SchemaConstants.KEY_CONTENT);
        final Map<String, Object> styles = part(list, SchemaConstants.KEY_STYLES);

        assertEquals(120L, ((Number) content.get("imageWidth")).longValue());
        assertEquals(130L, ((Number) content.get("itemHeight")).longValue());
        assertFalse(styles.containsKey("imageWidth"), "a row spent on a field is not repeated as a style");
        assertFalse(styles.containsKey("itemHeight"), "a row spent on a field is not repeated as a style");
        assertEquals(4L, styles.get("gap"), "a row naming no field stays a style");
    }

    @Test
    void withoutCustomMobileStyleTheDefaultsStand() {
        final Page page = context.create().page("/content/s/en/p");
        context.create().resource(page.getPath() + "/jcr:content/root");
        context.create().resource(page.getPath() + "/jcr:content/root/lister",
                "sling:resourceType", SchemaConstants.RT_FRANKLIN_BLOCK,
                "model", "category-products-lister",
                "mobilelayout", "horizontal");

        final Map<String, Object> content = part(entryOf(
                walkerWithMockedModelFactory().componentsOf(page, "https://assets.example.com", true, false), "list"),
                SchemaConstants.KEY_CONTENT);

        assertEquals(150L, ((Number) content.get("imageWidth")).longValue());
        assertEquals(150L, ((Number) content.get("itemHeight")).longValue());
    }
}

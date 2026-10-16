package com.edsMobileApp.core.services.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.apache.sling.models.factory.ModelFactory;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.day.cq.wcm.api.Page;
import com.edsMobileApp.core.models.impl.GenericComponent;
import com.edsMobileApp.core.schema.SchemaConstants;

import io.wcm.testing.mock.aem.junit5.AemContext;
import io.wcm.testing.mock.aem.junit5.AemContextExtension;

/**
 * The form blocks whose bundled schemas carry a long consent label take a {@code custommobilestyle}
 * field in their EDS model. A {@code checkbox-width:<n>} row has to reach every {@code checkboxGroup} the
 * form emits, and a block without the row has to export exactly what it did before.
 */
@ExtendWith(AemContextExtension.class)
class CheckboxWidthTest {

    private final AemContext context = new AemContext();

    private ComponentTreeWalker walker() {
        final UrlServiceImpl urlService = new UrlServiceImpl();
        final ModelFactory modelFactory = mock(ModelFactory.class);
        when(modelFactory.isModelAvailableForResource(any())).thenReturn(true);
        when(modelFactory.getModelFromResource(any()))
                .thenAnswer(invocation -> new GenericComponent(invocation.getArgument(0), urlService));
        final FormSchemaServiceImpl schemas = new FormSchemaServiceImpl();
        schemas.activate();
        return new ComponentTreeWalker(modelFactory, urlService, schemas,
                new StyleResolver(new String[0]), ComponentMappings.load());
    }

    @SuppressWarnings("unchecked")
    private static void collectCheckboxGroups(final List<Map<String, Object>> tree,
                                              final List<Map<String, Object>> sink) {
        for (final Map<String, Object> component : tree) {
            final Map<String, Object> meta = (Map<String, Object>) component.get(SchemaConstants.KEY_META);
            if ("checkboxGroup".equals(meta.get(SchemaConstants.KEY_COMPONENT))) {
                sink.add(component);
            }
            final Object inner = component.get(SchemaConstants.KEY_CONTENT);
            if (inner instanceof List) {
                collectCheckboxGroups((List<Map<String, Object>>) inner, sink);
            }
        }
    }

    private List<Map<String, Object>> checkboxGroupsOf(final String model, final String[] rows) {
        final Page page = context.create().page("/content/s/en/" + model);
        context.create().resource(page.getPath() + "/jcr:content/root");
        final String block = page.getPath() + "/jcr:content/root/form";
        if (rows == null) {
            context.create().resource(block, "sling:resourceType", SchemaConstants.RT_FRANKLIN_BLOCK,
                    "model", model);
        } else {
            context.create().resource(block, "sling:resourceType", SchemaConstants.RT_FRANKLIN_BLOCK,
                    "model", model, "custommobilestyle", rows);
        }
        final List<Map<String, Object>> found = new ArrayList<>();
        collectCheckboxGroups(walker().componentsOf(page, "https://assets.example.com", true, false), found);
        return found;
    }

    @ParameterizedTest
    @ValueSource(strings = {"join-us", "checkout", "create-account", "child-account-application",
            "loan-application-form"})
    void aWidthRowReachesEveryCheckboxGroupOfTheForm(final String model) {
        final List<Map<String, Object>> groups = checkboxGroupsOf(model, new String[] {"checkbox-width:250"});
        assertFalse(groups.isEmpty(), model + " should emit at least one checkboxGroup");
        for (final Map<String, Object> group : groups) {
            @SuppressWarnings("unchecked")
            final Map<String, Object> styles = (Map<String, Object>) group.get(SchemaConstants.KEY_STYLES);
            assertEquals(250, styles.get("width"), model);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"join-us", "checkout", "create-account", "child-account-application",
            "loan-application-form"})
    void withoutARowTheCheckboxGroupsCarryNoStyles(final String model) {
        final List<Map<String, Object>> groups = checkboxGroupsOf(model, null);
        assertFalse(groups.isEmpty(), model + " should emit at least one checkboxGroup");
        for (final Map<String, Object> group : groups) {
            assertFalse(group.containsKey(SchemaConstants.KEY_STYLES), model);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"checkout", "child-account-application"})
    void rowsOtherThanWidthAreIgnored(final String model) {
        final List<Map<String, Object>> groups = checkboxGroupsOf(model, new String[] {"padding:0"});
        assertTrue(groups.stream().noneMatch(g -> g.containsKey(SchemaConstants.KEY_STYLES)), model);
    }
}

package com.edsMobileApp.core.services.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Map;

import org.apache.sling.api.resource.Resource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import com.edsMobileApp.core.schema.SchemaConstants;

import io.wcm.testing.mock.aem.junit5.AemContext;
import io.wcm.testing.mock.aem.junit5.AemContextExtension;

/**
 * The two lines these forms show above their fields on the web - the name change reason question and
 * the add-external "Authorization" section label - are in the form's schema, so the app shows them too.
 */
@ExtendWith(AemContextExtension.class)
class FormQuestionHeadingsTest {

    private final AemContext context = new AemContext();
    private int count;

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> fieldsOf(final String form, final Map<String, Object> authored) {
        final FormSchemaServiceImpl schemas = new FormSchemaServiceImpl();
        schemas.activate();
        final Resource resource = context.create().resource("/content/site/page/jcr:content/" + form + "-" + (++count));
        final Map<String, Object> built = new FormBlockBuilder(schemas).build(resource, form, "id1", 0, authored);
        return (List<Map<String, Object>>) built.get(SchemaConstants.KEY_CONTENT);
    }

    @SuppressWarnings("unchecked")
    private static String component(final Map<String, Object> field) {
        return (String) ((Map<String, Object>) field.get(SchemaConstants.KEY_META)).get(SchemaConstants.KEY_COMPONENT);
    }

    @SuppressWarnings("unchecked")
    private static String text(final Map<String, Object> field) {
        return (String) ((Map<String, Object>) field.get(SchemaConstants.KEY_CONTENT)).get("text");
    }

    @Test
    void nameChangeRequestAsksItsReasonQuestionAboveTheCheckboxes() {
        final List<Map<String, Object>> fields = fieldsOf("name-change-request", Map.of());
        assertEquals("title", component(fields.get(0)));
        assertEquals("What is the reason for your name change?", text(fields.get(0)));
        assertEquals("checkboxGroup", component(fields.get(1)));
    }

    @Test
    void addExternalLabelsItsAuthorizationSectionInBothVariants() {
        for (final String variant : new String[] {"account", "credit-card"}) {
            final List<Map<String, Object>> fields = fieldsOf("add-external", Map.of("variant", variant));
            int at = -1;
            for (int i = 0; i < fields.size(); i++) {
                if ("title".equals(component(fields.get(i)))) {
                    at = i;
                }
            }
            assertEquals("Authorization", text(fields.get(at)), variant);
            assertEquals(fields.size() - 2, at, variant);
        }
    }
}

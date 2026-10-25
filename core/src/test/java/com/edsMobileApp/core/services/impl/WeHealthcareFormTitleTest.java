package com.edsMobileApp.core.services.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.Map;

import org.junit.jupiter.api.Test;

import com.edsMobileApp.core.schema.SchemaConstants;

/**
 * The plan selections, the plan application and get offer show a heading above the form only when a
 * Form Title is authored - nothing compiled in, and the plan selections' Main Heading is not a source.
 */
class WeHealthcareFormTitleTest {

    private final FormBlockBuilder builder = new FormBlockBuilder(null);

    @SuppressWarnings("unchecked")
    private String titleOf(final String form, final Map<String, Object> authored) {
        final Map<String, Object> heading = builder.heading(form, authored, "id1", 0, null);
        return heading == null ? null
                : (String) ((Map<String, Object>) heading.get(SchemaConstants.KEY_CONTENT)).get("text");
    }

    @Test
    void anAuthoredFormTitleIsTheHeading() {
        for (final String form : new String[] {"plan-application", "medicare-plan-selection",
            "insurance-plan-selection", "get-offer"}) {
            assertEquals("My title", titleOf(form, Map.of("form-title", "My title")), form);
            assertEquals("My title", titleOf(form, Map.of("formtitle", "My title")), form);
        }
    }

    @Test
    void withoutAFormTitleThereIsNoHeading() {
        for (final String form : new String[] {"plan-application", "medicare-plan-selection",
            "insurance-plan-selection", "get-offer"}) {
            assertNull(titleOf(form, Map.of()), form);
            assertNull(titleOf(form, Map.of("form-title", "  ")), form);
        }
    }

    @Test
    void theMainHeadingIsNotASourceForThePlanSelections() {
        assertNull(titleOf("medicare-plan-selection", Map.of("formHeading", "Heading")));
        assertNull(titleOf("insurance-plan-selection", Map.of("formHeading", "Heading")));
    }
}

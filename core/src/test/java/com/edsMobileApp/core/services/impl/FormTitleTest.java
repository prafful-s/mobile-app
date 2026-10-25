package com.edsMobileApp.core.services.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.Map;

import org.junit.jupiter.api.Test;

import com.edsMobileApp.core.schema.SchemaConstants;

/**
 * These forms' pre-form heading comes only from the authored Form Title: with none authored (or only
 * blank text) the export has no heading for them, rather than a compiled-in default.
 */
class FormTitleTest {

    private final FormBlockBuilder builder = new FormBlockBuilder(null);

    @SuppressWarnings("unchecked")
    private String titleOf(final String form, final Map<String, Object> authored) {
        final Map<String, Object> heading = builder.heading(form, authored, "id1", 0, null);
        return heading == null ? null
                : (String) ((Map<String, Object>) heading.get(SchemaConstants.KEY_CONTENT)).get("text");
    }

    @Test
    void anAuthoredFormTitleIsTheHeading() {
        for (final String form : new String[] {"add-external", "child-account-application", "name-change-request",
            "set-up-auto-save", "transfer-funds", "automatic-payments", "mobile-additional-service",
            "tv-additional-service"}) {
            assertEquals("My title", titleOf(form, Map.of("form-title", "My title")), form);
            assertEquals("My title", titleOf(form, Map.of("formtitle", "My title")), form);
        }
    }

    @Test
    void thePlanFormsTakeTheirHeadingOnlyFromAuthoring() {
        assertEquals("Apply", titleOf("plan-application", Map.of("form-title", "Apply")));
        assertEquals("Offer", titleOf("get-offer", Map.of("form-title", "Offer")));
        assertNull(titleOf("get-offer", Map.of()));
        assertEquals("Pick", titleOf("medicare-plan-selection", Map.of("form-title", "Pick")));
        assertEquals("Pick", titleOf("insurance-plan-selection", Map.of("form-title", "Pick")));
        assertEquals("Pick", titleOf("medicare-plan-selection", Map.of("formtitle", "Pick")));
        // The block's Main Heading is not a source for the app heading.
        assertNull(titleOf("medicare-plan-selection", Map.of("formHeading", "Heading")));
        assertNull(titleOf("insurance-plan-selection", Map.of("formHeading", "Heading")));
        assertEquals("Title", titleOf("insurance-plan-selection",
                Map.of("form-title", "Title", "formHeading", "Heading")));
        for (final String form : new String[] {"plan-application", "medicare-plan-selection",
            "insurance-plan-selection"}) {
            assertNull(titleOf(form, Map.of()), form);
            assertNull(titleOf(form, Map.of("formHeading", " ", "form-title", " ")), form);
        }
    }

    @Test
    void withoutAFormTitleThereIsNoHeading() {
        for (final String form : new String[] {"add-external", "child-account-application", "name-change-request",
            "set-up-auto-save", "transfer-funds", "automatic-payments", "mobile-additional-service",
            "tv-additional-service"}) {
            assertNull(titleOf(form, Map.of()), form);
            assertNull(titleOf(form, Map.of("form-title", "  ")), form);
        }
    }
}

package com.edsMobileApp.core.services.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;

import org.junit.jupiter.api.Test;

import com.edsMobileApp.core.schema.SchemaConstants;

/**
 * A form's pre-form heading is its authored Form Title when there is one, and otherwise the text the
 * form has always shown - so a page authored before the field existed exports exactly what it did.
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
    void withoutAFormTitleTheHeadingIsTheDefaultItAlwaysHad() {
        assertEquals("Add External", titleOf("add-external", Map.of()));
        assertEquals("Child Account Application", titleOf("child-account-application", Map.of()));
        assertEquals("Name Change Request", titleOf("name-change-request", Map.of()));
        assertEquals("Set Up Auto Save", titleOf("set-up-auto-save", Map.of()));
        assertEquals("Transfer Funds", titleOf("transfer-funds", Map.of()));
        assertEquals("Transfer Funds", titleOf("transfer-funds", Map.of("form-title", "  ")));
    }

    @Test
    void theThreeFormsThatHadNoHeadingGetOneFromTheirName() {
        assertEquals("Automatic Payments", titleOf("automatic-payments", Map.of()));
        assertEquals("Mobile Additional Services", titleOf("mobile-additional-service", Map.of()));
        assertEquals("TV Additional Services", titleOf("tv-additional-service", Map.of()));
    }
}

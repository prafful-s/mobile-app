package com.edsMobileApp.core.services.impl;

import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.Map;

import org.junit.jupiter.api.Test;

import com.edsMobileApp.core.schema.SchemaConstants;

/**
 * The loan and credit card applications show no heading above the form: their Wizard Title only goes to
 * the data layer, so neither it nor the old Form Title is a source.
 */
class LoanCreditFormTitleTest {

    private final FormBlockBuilder builder = new FormBlockBuilder(null);

    @SuppressWarnings("unchecked")
    private String titleOf(final String form, final Map<String, Object> authored) {
        final Map<String, Object> heading = builder.heading(form, authored, "id1", 0, null);
        return heading == null ? null
                : (String) ((Map<String, Object>) heading.get(SchemaConstants.KEY_CONTENT)).get("text");
    }

    @Test
    void thereIsNoHeadingWhateverTitleIsAuthored() {
        for (final String form : new String[] {"loan-application-form", "credit-card-application-form"}) {
            assertNull(titleOf(form, Map.of()), form);
            assertNull(titleOf(form, Map.of("wizard-title", "My title")), form);
            assertNull(titleOf(form, Map.of("form-title", "My title")), form);
            assertNull(titleOf(form, Map.of("formtitle", "My title")), form);
        }
    }
}

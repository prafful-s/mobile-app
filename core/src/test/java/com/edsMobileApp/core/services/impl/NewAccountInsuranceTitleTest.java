package com.edsMobileApp.core.services.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.Map;

import org.junit.jupiter.api.Test;

import com.edsMobileApp.core.schema.SchemaConstants;

/**
 * Insurance shows a heading above the form only when one is authored - no "Life Insurance" is compiled
 * in. New account shows none: its Wizard Title only goes to the data layer.
 */
class NewAccountInsuranceTitleTest {

    private final FormBlockBuilder builder = new FormBlockBuilder(null);

    @SuppressWarnings("unchecked")
    private String titleOf(final String form, final Map<String, Object> authored) {
        final Map<String, Object> heading = builder.heading(form, authored, "id1", 0, null);
        return heading == null ? null
                : (String) ((Map<String, Object>) heading.get(SchemaConstants.KEY_CONTENT)).get("text");
    }

    @Test
    void anAuthoredTitleIsTheHeading() {
        assertEquals("Cover", titleOf("insurance", Map.of("formtitle", "Cover")));
    }

    @Test
    void newAccountHasNoHeadingWhateverTitleIsAuthored() {
        assertNull(titleOf("new-account", Map.of()));
        assertNull(titleOf("new-account", Map.of("wizard-title", "Account")));
        assertNull(titleOf("new-account", Map.of("form-title", "Account")));
        assertNull(titleOf("new-account", Map.of("formtitle", "Account")));
    }

    @Test
    void withoutATitleThereIsNoHeading() {
        assertNull(titleOf("insurance", Map.of()));
        assertNull(titleOf("insurance", Map.of("formtitle", " ")));
        assertNull(titleOf("insurance", Map.of("title", "Legacy")));
    }
}

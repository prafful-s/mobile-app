package com.edsMobileApp.core.services.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.Map;

import org.junit.jupiter.api.Test;

import com.edsMobileApp.core.schema.SchemaConstants;

/**
 * The new account and insurance forms show a heading above the form only when one is authored - no
 * "New Account Application" or "Life Insurance" is compiled in.
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
        assertEquals("Account", titleOf("new-account", Map.of("form-title", "Account")));
        assertEquals("Account", titleOf("new-account", Map.of("formtitle", "Account")));
        assertEquals("Cover", titleOf("insurance", Map.of("form-title", "Cover")));
        assertEquals("Cover", titleOf("insurance", Map.of("formtitle", "Cover")));
    }

    @Test
    void withoutATitleThereIsNoHeading() {
        assertNull(titleOf("new-account", Map.of()));
        assertNull(titleOf("new-account", Map.of("form-title", "  ")));
        assertNull(titleOf("insurance", Map.of()));
        assertNull(titleOf("insurance", Map.of("form-title", " ")));
        assertNull(titleOf("insurance", Map.of("title", "Legacy")));
    }
}

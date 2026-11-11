package com.edsMobileApp.core.services.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
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
    void getOfferJoinUsAndInsuranceTakeTheirHeadingOnlyFromAuthoring() {
        assertEquals("Offer", titleOf("get-offer", Map.of("form-title", "Offer")));
        assertEquals("Join", titleOf("join-us", Map.of("form-title", "Join")));
        assertEquals("Cover", titleOf("insurance", Map.of("title", "Cover")));
        assertNull(titleOf("insurance", Map.of()));
        assertNull(titleOf("insurance", Map.of("title", " ")));
        assertNull(titleOf("join-us", Map.of()));
        assertNull(titleOf("join-us", Map.of("form-title", " ")));
        assertNull(titleOf("get-offer", Map.of()));
    }

    /** Their Wizard Title only goes to the data layer and is not shown on the page, so it is no heading. */
    @Test
    void theWizardFormsHaveNoHeadingWhateverTitleIsAuthored() {
        for (final String form : new String[] {"plan-application", "medicare-plan-selection",
            "insurance-plan-selection", "new-account", "loan-application-form", "credit-card-application-form"}) {
            assertNull(titleOf(form, Map.of()), form);
            assertNull(titleOf(form, Map.of("wizard-title", "Title")), form);
            assertNull(titleOf(form, Map.of("form-title", "Title")), form);
            assertNull(titleOf(form, Map.of("formtitle", "Title")), form);
            assertNull(titleOf(form, Map.of("formHeading", "Heading")), form);
        }
    }

    /** schedule-consultation has no pre-form heading: its Form Title is not sent to the app. */
    @Test
    void scheduleConsultationHasNoFormHeading() {
        assertNull(titleOf("schedule-consultation", Map.of("form-title", "Appointment")));
        assertNull(titleOf("schedule-consultation", Map.of("formtitle", "Appointment")));
        assertNull(titleOf("schedule-consultation", Map.of()));
    }

    /** The heading above the slots takes the block's Slots Label, and keeps its configured text without one. */
    @Test
    void theSlotsHeadingTakesTheAuthoredSlotsLabel() throws Exception {
        final Map<String, Object> configured;
        try (InputStream schema = getClass().getResourceAsStream("/edsMobileApp/forms/schedule-consultation.json")) {
            final String fields = new String(schema.readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(fields.contains("\"labelFrom\": \"slots-label\""), "the heading names its block property");
            assertTrue(fields.contains("\"Next Available Slots\""), "and keeps a configured fallback");
            configured = Map.of("id", "consultation-slot-heading", "type", "heading",
                    "label", "Next Available Slots", "labelFrom", "slots-label");
        }

        assertEquals("Pick a time",
                FormFieldFactory.withAuthoredLabel(configured, Map.of("slots-label", "Pick a time")).get("label"));
        assertEquals("Pick a time",
                FormFieldFactory.withAuthoredLabel(configured, Map.of("slots-label", "  Pick a time  ")).get("label"));
        assertEquals("Next Available Slots",
                FormFieldFactory.withAuthoredLabel(configured, Map.of()).get("label"));
        assertEquals("Next Available Slots",
                FormFieldFactory.withAuthoredLabel(configured, Map.of("slots-label", " ")).get("label"));
        assertEquals("Next Available Slots",
                FormFieldFactory.withAuthoredLabel(configured, null).get("label"));
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

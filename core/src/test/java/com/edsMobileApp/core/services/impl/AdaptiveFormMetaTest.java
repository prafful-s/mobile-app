package com.edsMobileApp.core.services.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.apache.sling.models.factory.ModelFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import com.day.cq.wcm.api.Page;
import com.edsMobileApp.core.models.impl.GenericComponent;
import com.edsMobileApp.core.schema.SchemaConstants;

import io.wcm.testing.mock.aem.junit5.AemContext;
import io.wcm.testing.mock.aem.junit5.AemContextExtension;

/**
 * A form built in the AEM Forms editor has none of the mobile fields an EDS block carries, so the two
 * settings the app needs are read from what that editor does author: the event name from the submit
 * button's own click handlers, and the toast from the message the form shows on submit.
 */
@ExtendWith(AemContextExtension.class)
class AdaptiveFormMetaTest {

    private final AemContext context = new AemContext();

    /** Each case authors its own page, so one case's properties cannot leak into the next. */
    private int form;

    @SuppressWarnings("unchecked")
    private Map<String, Object> formMetaOf(final String[] click, final Object... formProps) {
        final Page page = context.create().page("/content/s/en/create-account-adaptive-" + (++form));
        context.create().resource(page.getPath() + "/jcr:content/root");
        final List<Object> props = new ArrayList<>(List.of(
                "sling:resourceType", "fd/franklin/components/form/v1/form", "fieldType", "form"));
        props.addAll(Arrays.asList(formProps));
        context.create().resource(page.getPath() + "/jcr:content/root/form", props.toArray());
        context.create().resource(page.getPath() + "/jcr:content/root/form/submitButton",
                "fieldType", "button", "jcr:title", "Create account");
        if (click != null) {
            context.create().resource(page.getPath() + "/jcr:content/root/form/submitButton/events",
                    "click", click);
        }

        final UrlServiceImpl urlService = new UrlServiceImpl();
        final ModelFactory modelFactory = mock(ModelFactory.class);
        when(modelFactory.isModelAvailableForResource(any())).thenReturn(true);
        when(modelFactory.getModelFromResource(any()))
                .thenAnswer(invocation -> new GenericComponent(invocation.getArgument(0), urlService));
        final FormSchemaServiceImpl schemas = new FormSchemaServiceImpl();
        schemas.activate();
        final ComponentTreeWalker walker = new ComponentTreeWalker(modelFactory, urlService, schemas,
                new StyleResolver(new String[0]), ComponentMappings.load());
        final Map<String, Object> component =
                walker.componentsOf(page, "https://assets.example.com", true, false)
                        .stream()
                        .filter(c -> "form".equals(((Map<String, Object>) c.get(SchemaConstants.KEY_META))
                                .get(SchemaConstants.KEY_COMPONENT)))
                        .findFirst().orElseThrow(() -> new AssertionError("no form exported"));
        return (Map<String, Object>) component.get(SchemaConstants.KEY_META);
    }

    private String eventTypeOf(final String[] click, final String blockProp) {
        final Object[] props = blockProp == null
                ? new Object[0]
                : new Object[] {"mobilebuttoneventtype", blockProp};
        return (String) formMetaOf(click, props).get("eventType");
    }

    private String toastOf(final String option, final String message, final Object... extra) {
        final List<Object> props = new ArrayList<>();
        if (option != null) {
            props.addAll(List.of("thankYouOption", option));
        }
        if (message != null) {
            props.addAll(List.of("thankYouMessage", message));
        }
        props.addAll(Arrays.asList(extra));
        return (String) formMetaOf(null, props.toArray()).get("toastMessage");
    }

    @Test
    void theMobileEventNameWins() {
        assertEquals("mobile.registration", eventTypeOf(new String[] {
            "pushFormToDataLayer()",
            "fireLaunchEvent('create-account-submit')",
            "setMobileEventType('mobile.registration')"}, null));
    }

    @Test
    void theLaunchEventIsTheFallback() {
        assertEquals("create-account-submit", eventTypeOf(new String[] {
            "pushFormToDataLayer()",
            "setLaunchIdentity($form.\"personalEmail.address\".$value)",
            "fireLaunchEvent('create-account-submit')"}, null));
    }

    @Test
    void neitherCallMeansNoEventType() {
        assertEquals("", eventTypeOf(new String[] {"pushFormToDataLayer()"}, null));
        assertEquals("", eventTypeOf(null, null));
    }

    @Test
    void theBlockEventFieldStillWins() {
        assertEquals("mobile.signup",
                eventTypeOf(new String[] {"setMobileEventType('mobile.registration')"}, "mobile.signup"));
    }

    @Test
    void theOnSubmitMessageBecomesTheToast() {
        assertEquals("Thanks, your account is on its way.",
                toastOf("message", "<p>Thanks, your account is on its way.</p>"));
    }

    @Test
    void aRedirectOnSubmitLeavesNoToast() {
        assertEquals("", toastOf("page", "<p>Stale text from an earlier choice</p>"));
    }

    @Test
    void noMessageContentMeansNoToast() {
        assertEquals("", toastOf("message", null));
        assertEquals("", toastOf(null, null));
    }

    @Test
    void theBlockToastFieldStillWins() {
        assertEquals("Booked", toastOf("message", "<p>Ignored</p>", "toastMessage", "Booked"));
    }
}

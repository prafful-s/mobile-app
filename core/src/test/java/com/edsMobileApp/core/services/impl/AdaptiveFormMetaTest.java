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
 * A form built in the AEM Forms editor carries none of the mobile fields an EDS block does, so what
 * the app needs is read from what that editor does author: the event name from the submit button's
 * own click handlers, the toast from the message shown on submit, and the form's own headings from
 * the static text components sitting among its fields.
 */
@ExtendWith(AemContextExtension.class)
class AdaptiveFormMetaTest {

    /** The submit button every case authors, since the form's label is read off it. */
    private static final Object[] SUBMIT_BUTTON =
        {"submitButton", "fieldType", "button", "jcr:title", "Create account"};

    private final AemContext context = new AemContext();

    /** Each case authors its own page, so one case's properties cannot leak into the next. */
    private int form;

    /**
     * @param click     the submit button's authored click handlers, or null for none
     * @param formProps properties on the form node itself
     * @param children  one {@code {nodeName, key, value, ...}} array per authored child, in order
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> formComponentOf(final String[] click, final Object[] formProps,
                                                final Object[]... children) {
        final Page page = context.create().page("/content/s/en/create-account-adaptive-" + (++form));
        context.create().resource(page.getPath() + "/jcr:content/root");
        final List<Object> props = new ArrayList<>(List.of(
                "sling:resourceType", "fd/franklin/components/form/v1/form", "fieldType", "form"));
        props.addAll(Arrays.asList(formProps));
        final String formPath = page.getPath() + "/jcr:content/root/form";
        context.create().resource(formPath, props.toArray());
        for (final Object[] child : children) {
            context.create().resource(formPath + "/" + child[0],
                    Arrays.copyOfRange(child, 1, child.length));
        }
        if (click != null) {
            context.create().resource(formPath + "/submitButton/events", "click", click);
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
        return walker.componentsOf(page, "https://assets.example.com", true, false)
                .stream()
                .filter(c -> "form".equals(((Map<String, Object>) c.get(SchemaConstants.KEY_META))
                        .get(SchemaConstants.KEY_COMPONENT)))
                .findFirst().orElseThrow(() -> new AssertionError("no form exported"));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> metaOf(final String[] click, final Object... formProps) {
        return (Map<String, Object>) formComponentOf(click, formProps, SUBMIT_BUTTON)
                .get(SchemaConstants.KEY_META);
    }

    private String eventTypeOf(final String[] click, final Object... formProps) {
        return (String) metaOf(click, formProps).get("eventType");
    }

    private String toastOf(final Object... formProps) {
        return (String) metaOf(null, formProps).get("toastMessage");
    }

    /** The form's field list, one {@code "component :: label-or-text"} per entry, in order. */
    @SuppressWarnings("unchecked")
    private List<String> fieldsOf(final Object[]... children) {
        final List<Map<String, Object>> fields = (List<Map<String, Object>>)
                formComponentOf(null, new Object[0], children).get(SchemaConstants.KEY_CONTENT);
        final List<String> described = new ArrayList<>();
        for (final Map<String, Object> field : fields) {
            final Map<String, Object> meta = (Map<String, Object>) field.get(SchemaConstants.KEY_META);
            final Map<String, Object> content = (Map<String, Object>) field.get(SchemaConstants.KEY_CONTENT);
            described.add(meta.get(SchemaConstants.KEY_COMPONENT) + " :: "
                    + content.getOrDefault("label", content.getOrDefault("text", "")));
        }
        return described;
    }

    @Test
    void theMobileEventNameWins() {
        assertEquals("mobile.registration", eventTypeOf(new String[] {
            "pushFormToDataLayer()",
            "fireLaunchEvent('create-account-submit')",
            "setMobileEventType('mobile.registration')"}));
    }

    @Test
    void theLaunchEventIsTheLastResort() {
        assertEquals("create-account-submit", eventTypeOf(new String[] {
            "pushFormToDataLayer()",
            "setLaunchIdentity($form.\"personalEmail.address\".$value)",
            "fireLaunchEvent('create-account-submit')"}));
    }

    @Test
    void neitherCallNorFieldMeansNoEventType() {
        assertEquals("", eventTypeOf(new String[] {"pushFormToDataLayer()"}));
        assertEquals("", eventTypeOf(null));
    }

    @Test
    void theMobileFieldWinsOverEverything() {
        assertEquals("mobile.signup", eventTypeOf(
                new String[] {"setMobileEventType('mobile.registration')"},
                "mobilebuttoneventtype", "mobile.signup"));
    }

    @Test
    void theBlocksWebEventFieldIsNotRead() {
        // buttoneventtype is the name the block JS fires on the web; whether app analytics should
        // take it is still open, so a form that sets only that field still exports no event.
        assertEquals("", eventTypeOf(null, "buttoneventtype", "create-account-submit"));
    }

    @Test
    void theOnSubmitMessageBecomesTheToast() {
        assertEquals("Thanks, your account is on its way.",
                toastOf("thankYouOption", "message",
                        "thankYouMessage", "<p>Thanks, your account is on its way.</p>"));
    }

    @Test
    void aRedirectOnSubmitLeavesNoToast() {
        assertEquals("", toastOf("thankYouOption", "page",
                "thankYouMessage", "<p>Stale text from an earlier choice</p>"));
    }

    @Test
    void noMessageContentMeansNoToast() {
        assertEquals("", toastOf("thankYouOption", "message"));
        assertEquals("", toastOf());
    }

    @Test
    void theBlockToastFieldStillWins() {
        assertEquals("Booked", toastOf("thankYouOption", "message",
                "thankYouMessage", "<p>Ignored</p>", "toastMessage", "Booked"));
    }

    @Test
    void staticTextBecomesATitleWhereTheAuthorPutIt() {
        assertEquals(List.of(
                "title :: Create an account",
                "textField :: Email address",
                "title :: Communication preferences",
                // A checkboxGroup carries its text in its options, not in a label of its own.
                "checkboxGroup :: "),
                fieldsOf(
                    new Object[] {"heading", "fieldType", "plain-text", "name", "heading-create-account",
                        "value", "<h1>Create an account</h1><p></p>", "textIsRich", Boolean.TRUE},
                    new Object[] {"email", "fieldType", "email", "name", "personalEmail.address",
                        "jcr:title", "Email address"},
                    new Object[] {"commHeading", "fieldType", "plain-text", "name", "heading-comm",
                        "value", "<h2>Communication preferences</h2>"},
                    new Object[] {"prefEmail", "fieldType", "checkbox", "name", "consents.marketing.email.val",
                        "jcr:title", "Email me"},
                    SUBMIT_BUTTON));
    }

    @Test
    void hiddenOrEmptyStaticTextMapsToNothing() {
        assertEquals(List.of("textField :: Email address"),
                fieldsOf(
                    new Object[] {"hidden", "fieldType", "plain-text", "name", "heading-hidden",
                        "value", "<h1>Not shown</h1>", "visible", Boolean.FALSE},
                    new Object[] {"empty", "fieldType", "plain-text", "name", "heading-empty",
                        "value", "<p></p>"},
                    new Object[] {"email", "fieldType", "email", "name", "personalEmail.address",
                        "jcr:title", "Email address"},
                    SUBMIT_BUTTON));
    }
}

package com.edsMobileApp.core.services.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.apache.sling.models.factory.ModelFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import com.day.cq.wcm.api.Page;
import com.edsMobileApp.core.models.impl.GenericComponent;
import com.edsMobileApp.core.schema.SchemaConstants;

import io.wcm.testing.mock.aem.junit5.AemContext;
import io.wcm.testing.mock.aem.junit5.AemContextExtension;

/**
 * Citi Signal's forms: {@code automatic-payments}, and the two independent additional service forms,
 * {@code mobile-additional-service} and {@code tv-additional-service}. All are fixed schemas, so what
 * is tested is the shape the old app JSON
 * carried - paths, option values, the vertical width-capped checkbox groups and
 * each form's own button text.
 */
@ExtendWith(AemContextExtension.class)
class CitiSignalFormsTest {

    private final AemContext context = new AemContext();

    @SuppressWarnings("unchecked")
    private Map<String, Object> exportForm(final String model, final Object... props) {
        final Page page = context.create().page("/content/s/en/p");
        context.create().resource(page.getPath() + "/jcr:content/root");
        final List<Object> all = new ArrayList<>(List.of("sling:resourceType",
                SchemaConstants.RT_FRANKLIN_BLOCK, "model", model));
        all.addAll(List.of(props));
        context.create().resource(page.getPath() + "/jcr:content/root/form", all.toArray());

        final UrlServiceImpl urlService = new UrlServiceImpl();
        final ModelFactory modelFactory = mock(ModelFactory.class);
        when(modelFactory.isModelAvailableForResource(any())).thenReturn(true);
        when(modelFactory.getModelFromResource(any()))
                .thenAnswer(invocation -> new GenericComponent(invocation.getArgument(0), urlService));
        final FormSchemaServiceImpl schemas = new FormSchemaServiceImpl();
        schemas.activate();
        final ComponentTreeWalker walker = new ComponentTreeWalker(modelFactory, urlService, schemas,
                new StyleResolver(new String[0]), ComponentMappings.load());
        final List<Map<String, Object>> tree =
                walker.componentsOf(page, "https://assets.example.com", true, false);
        return tree.stream()
                .filter(c -> "form".equals(((Map<String, Object>) c.get(SchemaConstants.KEY_META))
                        .get(SchemaConstants.KEY_COMPONENT)))
                .findFirst().orElseThrow(() -> new AssertionError("no form in " + tree));
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> fields(final Map<String, Object> form) {
        return (List<Map<String, Object>>) form.get(SchemaConstants.KEY_CONTENT);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> meta(final Map<String, Object> component) {
        return (Map<String, Object>) component.get(SchemaConstants.KEY_META);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> content(final Map<String, Object> component) {
        return (Map<String, Object>) component.get(SchemaConstants.KEY_CONTENT);
    }

    private static List<String> ids(final Map<String, Object> form) {
        return fields(form).stream().map(f -> String.valueOf(meta(f).get("id"))).collect(Collectors.toList());
    }

    private static List<Map<String, Object>> byComponent(final Map<String, Object> form, final String name) {
        return fields(form).stream().filter(f -> name.equals(meta(f).get("component")))
                .collect(Collectors.toList());
    }

    @SuppressWarnings("unchecked")
    private static int optionCount(final Map<String, Object> form) {
        return byComponent(form, "checkboxGroup").stream()
                .mapToInt(g -> ((List<Object>) content(g).get("options")).size()).sum();
    }

    @Test
    void automaticPaymentsHasTheThreeInputsAndTwoRadioGroups() {
        final Map<String, Object> form = exportForm("automatic-payments");
        assertEquals(List.of("accountNumber", "fullName", "email", "payment-method-heading",
                "paymentMethod", "payment-cycle-heading", "paymentCycle"), ids(form));

        final List<Map<String, Object>> inputs = byComponent(form, "textField");
        assertEquals(List.of("accountNumber", "person.name.fullName", "personalEmail.address"),
                inputs.stream().map(f -> content(f).get("path")).collect(Collectors.toList()));
        assertEquals("text", content(inputs.get(0)).get("inputMode"));
        assertEquals("Email Address", content(inputs.get(2)).get("label"));

        final List<Map<String, Object>> radios = byComponent(form, "radioGroup");
        assertEquals("Payment Method", content(radios.get(0)).get("path"));
        assertEquals("Payment Cycle", content(radios.get(1)).get("path"));
        assertFalse((Boolean) content(radios.get(0)).get("prefill"));
        assertEquals(List.of("card", "bank", "paypall", "other"), optionValues(radios.get(0)));
        assertEquals(List.of("monthly", "quaterly", "annualy"), optionValues(radios.get(1)));

    }

    @SuppressWarnings("unchecked")
    private static List<Object> optionValues(final Map<String, Object> radio) {
        return ((List<Map<String, Object>>) content(radio).get("options")).stream()
                .map(o -> o.get("value")).collect(Collectors.toList());
    }

    @Test
    void theButtonTextIsAuthoredAndDefaultsToSubmit() {
        for (final String form : new String[] {"automatic-payments", "mobile-additional-service", "tv-additional-service"}) {
            assertEquals("Submit", meta(exportForm(form)).get("submitButtonLabel"), form + " with no text authored");
            assertEquals("Pay now", meta(exportForm(form, "button-label", "Pay now")).get("submitButtonLabel"),
                    form + " with an authored text");
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void mobileAdditionalServiceHasItsThreeSectionsAndBuyButton() {
        final Map<String, Object> form = exportForm("mobile-additional-service");
        assertEquals(3, byComponent(form, "checkboxGroup").size());
        assertEquals(11, optionCount(form));
        assertEquals(List.of("Data & Internet Add-Ons", "Entertainment & Streaming",
                "Device Protection & Security"),
                byComponent(form, "title").stream().map(t -> content(t).get("text")).collect(Collectors.toList()));

        final Map<String, Object> first = byComponent(form, "checkboxGroup").get(0);
        final Map<String, Object> firstOption = ((List<Map<String, Object>>) content(first).get("options")).get(0);
        assertEquals("extraData", firstOption.get("path"));
        assertEquals("Extra Data - 5GB - $40", firstOption.get("label"));
        assertEquals("y", firstOption.get("checkedValue"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void tvAdditionalServiceHasItsOwnSectionsEmojisAndButton() {
        final Map<String, Object> form = exportForm("tv-additional-service");
        assertEquals(3, byComponent(form, "checkboxGroup").size());
        assertEquals(8, optionCount(form));
        assertEquals(List.of("Upgrade Your Plan", "Premium Channels", "Sports Add-ons"),
                byComponent(form, "title").stream().map(t -> content(t).get("text")).collect(Collectors.toList()));

        final Map<String, Object> premium = byComponent(form, "checkboxGroup").get(1);
        final List<Map<String, Object>> options = (List<Map<String, Object>>) content(premium).get("options");
        assertEquals(List.of("hboMax", "showtime", "starz"),
                options.stream().map(o -> o.get("path")).collect(Collectors.toList()));
        assertEquals("🍿 HBO Max – $15.99/month", options.get(0).get("label"));
    }

    @Test
    void checkboxGroupsAreVerticalAndWidthCappedSoLongLabelsWrap() {
        final Map<String, Object> form = exportForm("mobile-additional-service");
        for (final Map<String, Object> group : byComponent(form, "checkboxGroup")) {
            @SuppressWarnings("unchecked")
            final Map<String, Object> styles = (Map<String, Object>) group.get(SchemaConstants.KEY_STYLES);
            assertEquals("vertical", styles.get("flexDirection"));
            assertEquals(300, styles.get("width"));
        }
    }

    @Test
    void anAuthoredWidthReplacesTheDefaultButKeepsTheVerticalLayout() {
        final Map<String, Object> form = exportForm("mobile-additional-service",
                "custommobilestyle", new String[] {"checkbox-width:260"});
        for (final Map<String, Object> group : byComponent(form, "checkboxGroup")) {
            @SuppressWarnings("unchecked")
            final Map<String, Object> styles = (Map<String, Object>) group.get(SchemaConstants.KEY_STYLES);
            assertEquals(260, styles.get("width"));
            assertEquals("vertical", styles.get("flexDirection"));
        }
    }

    @Test
    void otherFormsAreUntouchedByTheSchemaStyleSupport() {
        final Map<String, Object> form = exportForm("set-up-auto-save");
        assertEquals("Submit", meta(form).get("submitButtonLabel"));
        for (final Map<String, Object> group : byComponent(form, "checkboxGroup")) {
            assertNull(group.get(SchemaConstants.KEY_STYLES));
        }
        assertTrue(ids(form).contains("objective"));
    }
}

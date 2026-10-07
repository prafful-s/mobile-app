package com.edsMobileApp.core.services.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
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
 * The {@code test-drive} block's {@code variant} picks which of its schema's fields the form
 * carries: the two existing variants keep the personal-info section, and the {@code service} variant
 * swaps it for a machine-type field, a service address and its own time heading.
 */
@ExtendWith(AemContextExtension.class)
class ServiceVariantFormTest {

    private final AemContext context = new AemContext();

    /** The tree the last {@link #fieldIds} call exported. */
    private List<Map<String, Object>> lastTree;

    private List<String> fieldIds(final String variant) {
        return fieldIds(variant, new Object[0], null);
    }

    @SuppressWarnings("unchecked")
    private List<String> fieldIds(final String variant, final Object[] extraProps,
                                  final Map<String, Object> formMetaSink) {
        final Page page = context.create().page("/content/s/en/p");
        context.create().resource(page.getPath() + "/jcr:content/root");
        final String block = page.getPath() + "/jcr:content/root/form";
        final List<Object> props = new ArrayList<>(List.of("sling:resourceType",
                SchemaConstants.RT_FRANKLIN_BLOCK, "model", "test-drive"));
        if (variant != null) {
            props.addAll(List.of("variant", variant));
        }
        props.addAll(List.of(extraProps));
        context.create().resource(block, props.toArray());
        final UrlServiceImpl urlService = new UrlServiceImpl();
        final ModelFactory modelFactory = mock(ModelFactory.class);
        when(modelFactory.isModelAvailableForResource(any())).thenReturn(true);
        when(modelFactory.getModelFromResource(any()))
                .thenAnswer(invocation -> new GenericComponent(invocation.getArgument(0), urlService));
        final FormSchemaServiceImpl schemas = new FormSchemaServiceImpl();
        schemas.activate();
        final ComponentTreeWalker walker = new ComponentTreeWalker(modelFactory, urlService, schemas,
                new StyleResolver(new String[0]), ComponentMappings.load());
        final List<String> ids = new ArrayList<>();
        final List<Map<String, Object>> tree =
                walker.componentsOf(page, "https://assets.example.com", true, false);
        lastTree = tree;
        collect(tree, ids);
        if (formMetaSink != null) {
            final List<Map<String, Object>> forms = new ArrayList<>();
            collectForms(tree, forms);
            formMetaSink.putAll((Map<String, Object>) forms.get(0).get(SchemaConstants.KEY_META));
        }
        return ids;
    }

    @SuppressWarnings("unchecked")
    private static void collect(final List<Map<String, Object>> tree, final List<String> sink) {
        for (final Map<String, Object> component : tree) {
            final Map<String, Object> meta = (Map<String, Object>) component.get(SchemaConstants.KEY_META);
            if ("form".equals(meta.get(SchemaConstants.KEY_COMPONENT))) {
                for (final Map<String, Object> field : (List<Map<String, Object>>) component.get(SchemaConstants.KEY_CONTENT)) {
                    sink.add(String.valueOf(((Map<String, Object>) field.get(SchemaConstants.KEY_META)).get(SchemaConstants.KEY_ID)));
                }
            } else if (component.get(SchemaConstants.KEY_CONTENT) instanceof List) {
                collect((List<Map<String, Object>>) component.get(SchemaConstants.KEY_CONTENT), sink);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static void collectForms(final List<Map<String, Object>> tree, final List<Map<String, Object>> sink) {
        for (final Map<String, Object> component : tree) {
            final Map<String, Object> meta = (Map<String, Object>) component.get(SchemaConstants.KEY_META);
            if ("form".equals(meta.get(SchemaConstants.KEY_COMPONENT))) {
                sink.add(component);
            } else if (component.get(SchemaConstants.KEY_CONTENT) instanceof List) {
                collectForms((List<Map<String, Object>>) component.get(SchemaConstants.KEY_CONTENT), sink);
            }
        }
    }

    /** The web block's success message is the app form's toast: the authored text, read as-is. */
    @SuppressWarnings("unchecked")
    private static void collectTitles(final List<Map<String, Object>> tree, final List<String> sink) {
        for (final Map<String, Object> component : tree) {
            final Map<String, Object> meta = (Map<String, Object>) component.get(SchemaConstants.KEY_META);
            final Object name = meta.get(SchemaConstants.KEY_COMPONENT);
            if ("title".equals(name)) {
                sink.add(String.valueOf(((Map<String, Object>) component.get(SchemaConstants.KEY_CONTENT)).get("text")));
            } else if (!"form".equals(name) && component.get(SchemaConstants.KEY_CONTENT) instanceof List) {
                collectTitles((List<Map<String, Object>>) component.get(SchemaConstants.KEY_CONTENT), sink);
            }
        }
    }

    private List<String> pageTitles(final String variant, final Object... props) {
        fieldIds(variant, props, null);
        final List<String> titles = new ArrayList<>();
        collectTitles(lastTree, titles);
        return titles;
    }

    @Test
    void theServiceVariantIsTitledCustomerServiceWhenNoTitleIsAuthored() {
        assertEquals(List.of("Customer service"), pageTitles("service"));
    }

    @Test
    void anAuthoredTitleWinsOverTheServiceDefault() {
        assertEquals(List.of("Order a clean"), pageTitles("service", "form-title", "Order a clean"));
    }

    @Test
    void theOtherVariantsStillShowNoTitleUnlessAuthored() {
        assertEquals(List.of(), pageTitles("test-drive"));
        assertEquals(List.of(), pageTitles("schedule-appointment"));
        assertEquals(List.of("Book your drive"), pageTitles("test-drive", "form-title", "Book your drive"));
    }

    @Test
    void onlyTheServiceVariantShowsTheResetButton() {
        final Map<String, Object> service = new java.util.LinkedHashMap<>();
        fieldIds("service", new Object[0], service);
        assertEquals(false, service.get("hideResetButton"));
        for (final String variant : new String[] {"test-drive", "schedule-appointment", null}) {
            final Map<String, Object> other = new java.util.LinkedHashMap<>();
            fieldIds(variant, new Object[0], other);
            assertEquals(true, other.get("hideResetButton"), String.valueOf(variant));
        }
    }

    @Test
    void theAuthoredSuccessMessageIsTheFormsToast() {
        final Map<String, Object> meta = new java.util.LinkedHashMap<>();
        fieldIds("service", new Object[] {"success-message", "Thanks, we will confirm your service shortly."}, meta);
        assertEquals("Thanks, we will confirm your service shortly.", meta.get("toastMessage"));
    }

    @Test
    void theServiceVariantSwapsPersonalInfoForMachineTypeAddressAndTime() {
        assertEquals(List.of("machine-heading", "machineType", "service-address-heading", "serviceStreet",
                "serviceCity", "service-slot-heading", "appointment-slot"), fieldIds("service"));
    }

    @Test
    void theTestDriveVariantIsUnchanged() {
        assertEquals(List.of("personal-info-heading", "firstName", "lastName", "email", "phone",
                "appointment-heading", "appointment-slot", "address-heading", "address", "city", "zipCode"),
                fieldIds("test-drive"));
    }

    @Test
    void aBlockWithNoVariantStillDefaultsToTestDrive() {
        assertEquals(fieldIds("test-drive"), fieldIds(null));
    }

    @Test
    void theScheduleAppointmentVariantKeepsPersonalInfoWithoutAnAddress() {
        assertEquals(List.of("personal-info-heading", "firstName", "lastName", "email", "phone",
                "appointment-heading", "appointment-slot"), fieldIds("schedule-appointment"));
    }
}

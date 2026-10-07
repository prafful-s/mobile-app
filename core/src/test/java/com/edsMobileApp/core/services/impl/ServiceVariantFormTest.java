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

    @SuppressWarnings("unchecked")
    private static void collectByComponent(final List<Map<String, Object>> tree, final String name,
                                           final List<Map<String, Object>> sink) {
        for (final Map<String, Object> component : tree) {
            final Map<String, Object> meta = (Map<String, Object>) component.get(SchemaConstants.KEY_META);
            if (name.equals(meta.get(SchemaConstants.KEY_COMPONENT))) {
                sink.add(component);
            }
            if (component.get(SchemaConstants.KEY_CONTENT) instanceof List) {
                collectByComponent((List<Map<String, Object>>) component.get(SchemaConstants.KEY_CONTENT), name, sink);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private List<String> descriptions(final String variant, final Object... props) {
        fieldIds(variant, props, null);
        final List<Map<String, Object>> found = new ArrayList<>();
        collectByComponent(lastTree, "htmlText", found);
        final List<String> texts = new ArrayList<>();
        for (final Map<String, Object> c : found) {
            texts.add(String.valueOf(((Map<String, Object>) c.get(SchemaConstants.KEY_CONTENT)).get("text")));
        }
        return texts;
    }

    /** The web block's Redirect URL sends its form home after submit; the app form takes the same page. */
    @Test
    void theAuthoredRedirectUrlIsTheFormsLink() {
        final Map<String, Object> meta = new java.util.LinkedHashMap<>();
        fieldIds("service", new Object[] {"redirect-url", "/content/frescopa/language-masters/en"}, meta);
        assertEquals("en", meta.get("link"));
    }

    @Test
    void withNoRedirectAuthoredTheLinkStaysBlank() {
        final Map<String, Object> meta = new java.util.LinkedHashMap<>();
        fieldIds("service", new Object[0], meta);
        assertEquals("", meta.get("link"));
    }

    @Test
    void aMobileRedirectStillWinsOverTheWebOne() {
        final Map<String, Object> meta = new java.util.LinkedHashMap<>();
        fieldIds("service", new Object[] {"redirect-url", "/content/frescopa/language-masters/en",
                "mobileRedirectUrl", "/content/frescopa/language-masters/en/products"}, meta);
        assertEquals("products", meta.get("link"));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> contentOfField(final String variant, final String fieldId) {
        fieldIds(variant, new Object[0], null);
        final List<Map<String, Object>> found = new ArrayList<>();
        for (final String component : new String[] {"title", "textField"}) {
            collectByComponent(lastTree, component, found);
        }
        return (Map<String, Object>) found.stream()
                .filter(f -> fieldId.equals(((Map<String, Object>) f.get(SchemaConstants.KEY_META)).get("id")))
                .findFirst().orElseThrow().get(SchemaConstants.KEY_CONTENT);
    }

    /** The description is a schema entry gated to the service variant: a small, regular, centred line. */
    @Test
    void theServiceDescriptionIsASmallCentredLineFromTheSchema() {
        final Map<String, Object> description = contentOfField("service", "service-description");
        assertEquals("Your coffee machine needs cleaning? Order a service with our partners.", description.get("text"));
        assertEquals("small", description.get("size"));
        assertEquals("regular", description.get("weight"));
        assertEquals("center", description.get("textAlignment"));
    }

    @Test
    void theMachineTypeFieldHasNoLabelSoItsPlaceholderShows() {
        final Map<String, Object> machine = contentOfField("service", "machineType");
        assertEquals("", machine.get("label"));
        assertEquals("Frescopa Smart Machine", machine.get("placeholder"));
    }

    @Test
    void ordinaryHeadingsKeepTheirMediumBoldLeftStyle() {
        final Map<String, Object> heading = contentOfField("service", "machine-heading");
        assertEquals("medium", heading.get("size"));
        assertEquals("bold", heading.get("weight"));
        assertEquals("left", heading.get("textAlignment"));
    }

    /** With no Form Title authored the schema supplies "Customer service": large, bold and centred. */
    @Test
    void theSchemaSuppliesTheServiceTitleWhenNoneIsAuthored() {
        assertEquals(List.of(), pageTitles("service"));
        final Map<String, Object> title = contentOfField("service", "service-title");
        assertEquals("Customer service", title.get("text"));
        assertEquals("large", title.get("size"));
        assertEquals("bold", title.get("weight"));
        assertEquals("center", title.get("textAlignment"));
    }

    /** An authored Form Title replaces the default - one title, not two. */
    @SuppressWarnings("unchecked")
    @Test
    void anAuthoredFormTitleReplacesTheSchemaDefault() {
        final List<String> ids = fieldIds("service", new Object[] {"form-title", "Order a clean"}, null);
        assertEquals(false, ids.contains("service-title"));
        fieldIds("service", new Object[] {"form-title", "Order a clean"}, null);
        final List<Map<String, Object>> titles = new ArrayList<>();
        collectByComponent(lastTree, "title", titles);
        final Map<String, Object> title = (Map<String, Object>) titles.get(0).get(SchemaConstants.KEY_CONTENT);
        assertEquals("Order a clean", title.get("text"));
        assertEquals("large", title.get("size"));
        assertEquals("center", title.get("textAlignment"));
    }

    @Test
    void everyFormHidesResetUnlessTheSiteSaysOtherwise() {
        for (final String variant : new String[] {"service", "test-drive", "schedule-appointment"}) {
            final Map<String, Object> meta = new java.util.LinkedHashMap<>();
            fieldIds(variant, new Object[0], meta);
            assertEquals(true, meta.get("hideResetButton"), variant);
        }
    }

    /** Frescopa's defaults.json shows Reset on its service screen - read through the exporter's form settings. */
    @SuppressWarnings("unchecked")
    @Test
    void frescopasDefaultsShowResetOnTheServiceScreenOnly() throws Exception {
        final PageJsonExporterImpl exporter = new PageJsonExporterImpl();
        final java.lang.reflect.Field defaults = PageJsonExporterImpl.class.getDeclaredField("siteDefaults");
        defaults.setAccessible(true);
        defaults.set(exporter, SiteDefaults.load());
        final java.lang.reflect.Method apply = PageJsonExporterImpl.class.getDeclaredMethod(
                "applyFormSettings", List.class, Page.class, String.class);
        apply.setAccessible(true);
        final Page page = context.create().page("/content/frescopa/language-masters/en/service");
        for (final String screen : new String[] {"service", "login"}) {
            final Map<String, Object> meta = new java.util.LinkedHashMap<>();
            meta.put(SchemaConstants.KEY_COMPONENT, "form");
            meta.put("hideResetButton", true);
            final Map<String, Object> form = new java.util.LinkedHashMap<>();
            form.put(SchemaConstants.KEY_META, meta);
            form.put(SchemaConstants.KEY_CONTENT, new ArrayList<>());
            apply.invoke(exporter, new ArrayList<>(List.of(form)), page, screen);
            assertEquals("service".equals(screen) ? false : true, meta.get("hideResetButton"), screen);
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
        assertEquals(List.of("service-title", "service-description", "machine-heading", "machineType", "service-address-heading", "serviceStreet",
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

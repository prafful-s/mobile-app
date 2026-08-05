package com.dept.contentbridge.core.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.apache.sling.testing.mock.sling.ResourceResolverType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import com.dept.contentbridge.core.services.impl.FormSchemaServiceImpl;

import io.wcm.testing.mock.aem.junit5.AemContext;
import io.wcm.testing.mock.aem.junit5.AemContextExtension;

/**
 * Form schemas ship inside the bundle, so they are verified against the real files rather than a
 * fixture - a typo in one of them should fail the build, not surface in production.
 */
@ExtendWith(AemContextExtension.class)
class FormSchemaServiceTest {

    private final AemContext context = new AemContext(ResourceResolverType.JCR_MOCK);

    private FormSchemaService service;

    @BeforeEach
    void setUp() {
        service = context.registerInjectActivateService(new FormSchemaServiceImpl());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> field(final String form, final String name) {
        return (Map<String, Object>) service.getSchema(form).get(name);
    }

    @Test
    @DisplayName("every shipped schema is discovered")
    void allSchemasLoad() {
        assertEquals(18, service.getFormNames().size(), service.getFormNames().toString());
    }

    @Test
    @DisplayName("index.json and the schema directory agree")
    void indexMatchesTheDirectory() throws Exception {
        // The service loads by index because a bundle cannot list its own contents. Nothing stops the
        // two drifting apart, so compare them here - at test time the resources are a real directory.
        final java.net.URL dir = getClass().getResource("/contentbridge/forms");
        assertNotNull(dir, "form schema directory is missing from the classpath");

        final java.io.File[] files = new java.io.File(dir.toURI()).listFiles(
                (d, n) -> n.endsWith(".json") && !"index.json".equals(n));
        assertNotNull(files);

        final java.util.Set<String> onDisk = new java.util.TreeSet<>();
        for (final java.io.File file : files) {
            onDisk.add(file.getName().replace(".json", ""));
        }

        assertEquals(onDisk, new java.util.TreeSet<>(service.getFormNames()),
                "index.json is out of step with the files - add or remove the name there too");
    }

    @Test
    @DisplayName("form names come from the file names")
    void formNamesMatchFiles() {
        assertTrue(service.getFormNames().containsAll(java.util.List.of(
                "sign-in", "checkout", "checkout-shipping", "create-account", "flight-search",
                "loan-application-form", "credit-card-application-form", "transfer-funds")));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> part(final Map<String, Object> field, final String key) {
        return (Map<String, Object>) field.get(key);
    }

    @Test
    @DisplayName("a field is a meta/content pair, the same envelope a component uses")
    void fieldShape() {
        final Map<String, Object> email = field("sign-in", "email");
        assertNotNull(email);

        assertEquals(java.util.List.of("meta", "content"), java.util.List.copyOf(email.keySet()));
        assertEquals("email", part(email, "meta").get("id"));
        assertEquals("textField", part(email, "meta").get("component"),
                "the widget the client renders, distinct from the value type");

        final Map<String, Object> content = part(email, "content");
        assertEquals("Email address", content.get("label"));
        assertEquals("email", content.get("name"));
        assertEquals("", content.get("value"));
        assertEquals("text", content.get("type"));
        assertEquals("email", content.get("inputMode"), "drives the on-screen keyboard");
        assertEquals(Boolean.TRUE, content.get("prefill"));
        assertEquals("personalEmail.address", content.get("path"),
                "where the client reads the prefill value from in its profile");
    }

    @Test
    @DisplayName("a password field is marked as one rather than only typed as one")
    void passwordField() {
        final Map<String, Object> password = field("sign-in", "password");

        assertEquals("textField", part(password, "meta").get("component"));
        assertEquals("password", part(password, "content").get("type"));
        assertEquals(Boolean.TRUE, part(password, "content").get("isPassword"));
    }

    @Test
    @DisplayName("a choice field carries the options the client renders")
    void choiceFieldsCarryOptions() {
        final Map<String, Object> country = field("checkout-shipping", "country");
        assertEquals("dropdown", part(country, "meta").get("component"));

        @SuppressWarnings("unchecked")
        final java.util.List<Map<String, Object>> options =
                (java.util.List<Map<String, Object>>) part(country, "content").get("options");
        assertTrue(options.size() > 1, "a dropdown with no options cannot be rendered");
        assertTrue(options.stream().allMatch(o -> o.containsKey("label") && o.containsKey("value")),
                "every option needs both a label and a value: " + options);
    }

    @Test
    @DisplayName("field order follows the file, not alphabetical order")
    void fieldOrderIsPreserved() {
        assertEquals(java.util.List.of("email", "password"),
                java.util.List.copyOf(service.getSchema("sign-in").keySet()));
    }

    @Test
    @DisplayName("ids that differ from the field key are preserved")
    void idsThatDifferFromTheKey() {
        // checkout-shipping deliberately keys 'zipCode' with an id of 'postalCode'
        assertEquals("postalCode", part(field("checkout-shipping", "zipCode"), "meta").get("id"));
        assertEquals("street", part(field("checkout-shipping", "streetAddress"), "meta").get("id"));
    }

    @Test
    @DisplayName("non-empty default values survive")
    void defaultValues() {
        assertEquals("$100.00", part(field("transfer-funds", "amount"), "content").get("value"));
        assertEquals("card", part(field("checkout-shipping", "paymentMethod"), "content").get("value"));
        assertEquals("checked", part(field("checkout", "wknd-club"), "content").get("value"));
    }

    @Test
    @DisplayName("every field names a widget the client can render and a type it can hold")
    void knownFieldTypes() {
        // Two separate vocabularies on purpose: 'component' is the widget, 'type' is the value it
        // produces. A checkbox group and a switch are the same type and different components.
        final java.util.Set<String> components = java.util.Set.of(
                "textField", "dropdown", "checkboxGroup", "radioGroup", "datePicker", "fileUpload");
        final java.util.Set<String> types =
                java.util.Set.of("text", "select", "checkbox", "radio", "file", "password", "date");

        service.getFormNames().forEach(form -> service.getSchema(form).forEach((name, definition) -> {
            @SuppressWarnings("unchecked")
            final Map<String, Object> field = (Map<String, Object>) definition;
            final Object component = part(field, "meta").get("component");
            final Object type = part(field, "content").get("type");

            assertTrue(components.contains(component),
                    form + "." + name + " has unexpected component " + component);
            assertTrue(types.contains(type), form + "." + name + " has unexpected type " + type);
        }));
    }

    @Test
    @DisplayName("every choice field actually has choices")
    void choiceFieldsAreRenderable() {
        final java.util.Set<String> needsOptions =
                java.util.Set.of("dropdown", "checkboxGroup", "radioGroup");

        service.getFormNames().forEach(form -> service.getSchema(form).forEach((name, definition) -> {
            @SuppressWarnings("unchecked")
            final Map<String, Object> field = (Map<String, Object>) definition;
            if (needsOptions.contains(part(field, "meta").get("component"))) {
                final Object options = part(field, "content").get("options");
                assertTrue(options instanceof java.util.List && !((java.util.List<?>) options).isEmpty(),
                        form + "." + name + " is a choice field with no options");
            }
        }));
    }

    @Test
    @DisplayName("an unknown form yields an empty map rather than null")
    void unknownForm() {
        assertTrue(service.getSchema("does-not-exist").isEmpty());
        assertTrue(service.getSchema(null).isEmpty());
        assertTrue(service.getSchema("  ").isEmpty());
    }

    @Test
    @DisplayName("schemas are immutable - a caller cannot corrupt them for everyone else")
    void schemasAreImmutable() {
        assertThrows(UnsupportedOperationException.class,
                () -> service.getSchema("sign-in").put("injected", "value"));
        assertThrows(UnsupportedOperationException.class,
                () -> service.getAllSchemas().remove("sign-in"));
    }
}

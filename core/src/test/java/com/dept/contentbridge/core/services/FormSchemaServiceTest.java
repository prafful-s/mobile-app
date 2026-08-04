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

    @Test
    @DisplayName("a field carries id, label, name, value and type")
    void fieldShape() {
        final Map<String, Object> email = field("sign-in", "email");
        assertNotNull(email);
        assertEquals("email", email.get("id"));
        assertEquals("Email address", email.get("label"));
        assertEquals("email", email.get("name"));
        assertEquals("", email.get("value"));
        assertEquals("text", email.get("type"));
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
        assertEquals("postalCode", field("checkout-shipping", "zipCode").get("id"));
        assertEquals("street", field("checkout-shipping", "streetAddress").get("id"));
    }

    @Test
    @DisplayName("non-empty default values survive")
    void defaultValues() {
        assertEquals("$100.00", field("transfer-funds", "amount").get("value"));
        assertEquals("card", field("checkout-shipping", "paymentMethod").get("value"));
        assertEquals("checked", field("checkout", "wknd-club").get("value"));
    }

    @Test
    @DisplayName("every field type in use is one the client can render")
    void knownFieldTypes() {
        final java.util.Set<String> allowed =
                java.util.Set.of("text", "select", "checkbox", "radio", "file", "password", "date");

        service.getFormNames().forEach(form -> service.getSchema(form).forEach((name, definition) -> {
            @SuppressWarnings("unchecked")
            final Map<String, Object> typed = (Map<String, Object>) definition;
            assertTrue(allowed.contains(typed.get("type")),
                    form + "." + name + " has unexpected type " + typed.get("type"));
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

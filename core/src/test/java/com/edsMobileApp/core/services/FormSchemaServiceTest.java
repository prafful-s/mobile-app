package com.edsMobileApp.core.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.apache.sling.testing.mock.sling.ResourceResolverType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import com.edsMobileApp.core.services.impl.FormSchemaServiceImpl;

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

    /** Fields are a list now, so a test that wants one by name has to look it up by id. */
    private Map<String, Object> field(final String form, final String id) {
        for (final Map<String, Object> candidate : service.getSchema(form)) {
            if (id.equals(candidate.get("id"))) {
                return candidate;
            }
        }
        return null;
    }

    private List<String> fieldIds(final String form) {
        final List<String> ids = new java.util.ArrayList<>();
        service.getSchema(form).forEach(f -> ids.add(String.valueOf(f.get("id"))));
        return ids;
    }

    @Test
    @DisplayName("every shipped schema is discovered")
    void allSchemasLoad() {
        assertEquals(20, service.getFormNames().size(), service.getFormNames().toString());
    }

    @Test
    @DisplayName("index.json and the schema directory agree")
    void indexMatchesTheDirectory() throws Exception {
        // The service loads by index because a bundle cannot list its own contents. Nothing stops the
        // two drifting apart, so compare them here - at test time the resources are a real directory.
        //
        // A literal, hardcoded relative path rather than a classloader lookup - no URL/URI in the
        // chain at all, so there is nothing for findsecbugs's PATH_TRAVERSAL_IN check to flag: that
        // rule targets the File(URI)/Paths.get(URI) constructors specifically, whatever feeds them.
        // Surefire's working directory is the module base (core/) by default, so this is stable.
        final java.nio.file.Path dir =
                java.nio.file.Paths.get("src/main/resources/contentbridge/forms");
        assertTrue(java.nio.file.Files.isDirectory(dir), "form schema directory is missing: " + dir);

        final java.util.Set<String> onDisk = new java.util.TreeSet<>();
        try (java.util.stream.Stream<java.nio.file.Path> paths = java.nio.file.Files.list(dir)) {
            paths.map(p -> p.getFileName().toString())
                    .filter(n -> n.endsWith(".json") && !"index.json".equals(n))
                    .forEach(n -> onDisk.add(n.replace(".json", "")));
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
    @DisplayName("a field is a flat definition the client can render directly")
    void fieldShape() {
        final Map<String, Object> email = field("sign-in", "email");
        assertNotNull(email);

        assertEquals("email", email.get("id"), "the identifier within the form");
        assertEquals("personalEmail.address", email.get("name"),
                "the path the client reads and writes in its own profile model");
        assertEquals("Email address", email.get("label"));
        assertEquals("", email.get("value"));
        assertEquals("text", email.get("type"));
    }

    @Test
    @DisplayName("id and name are different things and both survive")
    void idAndNameAreDistinct() {
        // 'id' is unique within the form; 'name' points into the profile model, so two forms asking
        // for the same detail use the same name and different ids.
        final Map<String, Object> first = field("loan-application-form", "firstName");
        assertEquals("firstName", first.get("id"));
        assertEquals("person.name.firstName", first.get("name"));
    }

    @Test
    @DisplayName("field order follows the file - a form is rendered top to bottom")
    void fieldOrderIsPreserved() {
        assertEquals(List.of("email", "password", "createAccountLink"), fieldIds("sign-in"));
    }

    @Test
    @DisplayName("non-empty default values survive")
    void defaultValues() {
        assertEquals("$100.00", field("transfer-funds", "amount").get("value"));
    }

    @Test
    @DisplayName("every field names a type the client can render")
    void knownFieldTypes() {
        final java.util.Set<String> types = java.util.Set.of(
                "text", "select", "checkbox", "radio", "file", "password", "date", "heading", "link");

        service.getFormNames().forEach(form -> service.getSchema(form).forEach(field ->
                assertTrue(types.contains(field.get("type")),
                        form + "." + field.get("id") + " has unexpected type " + field.get("type"))));
    }

    @Test
    @DisplayName("every field has an id and a name - both are load-bearing")
    void everyFieldIsAddressable() {
        // A heading is a static section divider and a link is secondary navigation - neither is a
        // value the user supplies, so neither has a path in the profile model to name.
        final java.util.Set<String> namelessTypes = java.util.Set.of("heading", "link");
        service.getFormNames().forEach(form -> service.getSchema(form).forEach(field -> {
            assertNotNull(field.get("id"), form + " has a field with no id");
            if (!namelessTypes.contains(field.get("type"))) {
                assertNotNull(field.get("name"), form + "." + field.get("id") + " has no name");
            }
        }));
    }

    @Test
    @DisplayName("every choice field actually has choices")
    void choiceFieldsAreRenderable() {
        // Invisible in the JSON, fatal in the UI: a select with no options renders as an empty box.
        final java.util.Set<String> needsOptions = java.util.Set.of("select", "radio", "checkbox");

        service.getFormNames().forEach(form -> service.getSchema(form).forEach(field -> {
            if (needsOptions.contains(field.get("type"))) {
                final Object options = field.get("options");
                assertTrue(options instanceof List && !((List<?>) options).isEmpty(),
                        form + "." + field.get("id") + " is a choice field with no options");
            }
        }));
    }

    @Test
    @DisplayName("options carry both a label and a value")
    void optionsAreWellFormed() {
        @SuppressWarnings("unchecked")
        final List<Map<String, Object>> options =
                (List<Map<String, Object>>) field("loan-application-form", "state").get("options");

        assertTrue(options.size() > 1);
        assertTrue(options.stream().allMatch(o -> o.containsKey("label") && o.containsKey("value")),
                "an option missing either is unrenderable: " + options);
    }

    @Test
    @DisplayName("an unknown form yields an empty list rather than null")
    void unknownForm() {
        assertTrue(service.getSchema("does-not-exist").isEmpty());
        assertTrue(service.getSchema(null).isEmpty());
        assertTrue(service.getSchema("  ").isEmpty());
    }

    @Test
    @DisplayName("schemas are immutable - a caller cannot corrupt them for everyone else")
    void schemasAreImmutable() {
        assertThrows(UnsupportedOperationException.class,
                () -> service.getSchema("sign-in").add(new java.util.HashMap<>()));
        assertThrows(UnsupportedOperationException.class,
                () -> service.getAllSchemas().remove("sign-in"));
    }
}

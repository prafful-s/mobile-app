package com.dept.contentbridge.core.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.apache.sling.testing.mock.sling.ResourceResolverType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import com.dept.contentbridge.core.services.impl.FormSchemaServiceImpl;
import com.dept.contentbridge.core.services.impl.PageJsonExporterImpl;
import com.dept.contentbridge.core.services.impl.UrlServiceImpl;
import com.day.cq.wcm.api.Page;

import io.wcm.testing.mock.aem.junit5.AemContext;
import io.wcm.testing.mock.aem.junit5.AemContextExtension;

/**
 * A v2 form is one component: settings in {@code meta}, fields as an ordered {@code content} array.
 *
 * <p>Fields come from a bundled schema where one exists and from the block's own authored children
 * where it does not, and both routes have to produce the same shape - otherwise a client would need
 * to know which kind of form it was looking at.</p>
 */
@ExtendWith(AemContextExtension.class)
class FormBlockV2Test {

    private static final String SITE = "/content/binji-site/en";
    private static final String ROOT = SITE + "/jcr:content/root";

    private final AemContext context = new AemContext(ResourceResolverType.JCR_MOCK);

    private PageJsonExporter exporter;

    @BeforeEach
    void setUp() {
        context.create().page(SITE, "", Map.of("jcr:title", "Home"));
        context.addModelsForPackage("com.dept.contentbridge.core.models.impl");
        context.registerInjectActivateService(new UrlServiceImpl(), "publishDomain", "");
        context.registerInjectActivateService(new FormSchemaServiceImpl());
        context.registerInjectActivateService(new PageJsonExporterImpl(),
                "allowedRoots", new String[] {"/content"}, "cacheMaxAge", 0);

        exporter = context.getService(PageJsonExporter.class);
        assertNotNull(exporter);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> map(final Object value) {
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> list(final Object value) {
        return (List<Map<String, Object>>) value;
    }

    private List<Map<String, Object>> content(final boolean v2) {
        final Page home = context.pageManager().getPage(SITE);
        final Map<String, Object> app = v2
                ? exporter.export(home, ExportOptions.of(ExportOptions.SCHEMA_V2, null))
                : exporter.export(home);
        return list(map(map(app.get("screens")).get("Home")).get("content"));
    }

    private Map<String, Object> form() {
        return content(true).stream()
                .filter(c -> "form".equals(map(c.get("meta")).get("component")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no form component was exported: "
                        + content(true)));
    }

    private void signInBlock() {
        context.build().resource(ROOT + "/signin",
                "sling:resourceType", "core/franklin/components/block/v1/block",
                "model", "sign-in",
                "buttoneventtype", "login",
                "sign-in-btn-text", "Get Started");
    }

    @Test
    @DisplayName("a form is a single component with its settings in meta")
    void formSettingsAreInMeta() {
        signInBlock();

        final Map<String, Object> meta = map(form().get("meta"));
        assertEquals("form", meta.get("component"));
        assertEquals("login", meta.get("eventType"), "authored buttoneventtype becomes eventType");
        assertEquals("Get Started", meta.get("submitButtonLabel"));
        assertEquals("Reset", meta.get("resetButtonLabel"));
        assertEquals(Boolean.TRUE, meta.get("hideResetButton"));
        assertEquals("", meta.get("webhookUrl"), "always present so the client need not test for it");
        assertEquals("", meta.get("toastMessage"));
    }

    @Test
    @DisplayName("content is an ordered array of fields, and there is no schema key")
    void contentIsTheFieldList() {
        signInBlock();

        final Map<String, Object> form = form();
        assertFalse(form.containsKey("schema"), "the fields are the content now, not a sidecar");

        final List<Map<String, Object>> fields = list(form.get("content"));
        assertEquals(2, fields.size());
        assertEquals("email", map(fields.get(0).get("meta")).get("id"));
        assertEquals("password", map(fields.get(1).get("meta")).get("id"));
        assertEquals(1, map(fields.get(0).get("meta")).get("order"));
        assertEquals(2, map(fields.get(1).get("meta")).get("order"));
    }

    @Test
    @DisplayName("a field's widget and its rendering keys are generated from its type")
    void fieldContentIsGenerated() {
        signInBlock();
        final List<Map<String, Object>> fields = list(form().get("content"));

        // The configuration says 'text'; everything else here is derived from that.
        final Map<String, Object> email = fields.get(0);
        assertEquals("textField", map(email.get("meta")).get("component"));

        final Map<String, Object> content = map(email.get("content"));
        assertEquals("outlined", content.get("mode"));
        assertEquals("Email address", content.get("label"));

        // sign-in.json sets no 'path', and 'name' is not one - so there is nowhere to pre-fill from
        // and the payload says so rather than inventing a profile location.
        assertEquals("", content.get("path"));
        assertEquals(Boolean.FALSE, content.get("prefill"));
        assertEquals("email", content.get("inputMode"), "an email keyboard, not a plain one");
        assertEquals(Boolean.FALSE, content.get("multiline"));
        assertEquals(Boolean.FALSE, content.get("isPassword"));
        assertEquals("off", content.get("leftElement"));
        assertEquals("enter", content.get("enterKeyHint"));
    }

    @Test
    @DisplayName("a password is never pre-filled, whatever the configuration says")
    void passwordIsNeverPrefilled() {
        signInBlock();
        final Map<String, Object> content = map(list(form().get("content")).get(1).get("content"));

        assertEquals(Boolean.TRUE, content.get("isPassword"));
        // sign-in.json gives the password field a name, which for any other type would be read as
        // somewhere to read a stored value from.
        assertEquals(Boolean.FALSE, content.get("prefill"));
    }

    @Test
    @DisplayName("a choice field becomes a dropdown carrying its options")
    void choiceField() {
        context.build().resource(ROOT + "/form",
                "sling:resourceType", "core/franklin/components/block/v1/block",
                "model", "form", "formName", "loan-application-form");

        final Map<String, Object> state = list(form().get("content")).stream()
                .filter(f -> "state".equals(map(f.get("meta")).get("id")))
                .findFirst().orElseThrow(() -> new AssertionError("state field missing"));

        assertEquals("dropdown", map(state.get("meta")).get("component"));

        final Map<String, Object> content = map(state.get("content"));
        final List<?> options = (List<?>) content.get("options");
        assertTrue(options.size() > 1, "a dropdown with no options cannot be rendered");

        // Every field carries the same input keys, whatever widget renders it - a client parsing a
        // fixed shape should not have to branch on the component to know which keys exist.
        assertEquals("none", content.get("inputMode"), "nothing is typed into a dropdown");
        assertEquals(Boolean.FALSE, content.get("multiline"));
        assertEquals(Boolean.FALSE, content.get("isPassword"));
        assertEquals(Boolean.FALSE, content.get("autoCorrect"));
        assertEquals("off", content.get("leftElement"));
        assertEquals("off", content.get("rightElement"));
        assertEquals("enter", content.get("enterKeyHint"));
    }

    @Test
    @DisplayName("every field type carries the same input keys")
    void everyWidgetHasTheSameKeys() {
        context.build().resource(ROOT + "/form",
                "sling:resourceType", "core/franklin/components/block/v1/block",
                "model", "form", "formName", "name-change-request");

        final List<String> required = List.of("mode", "path", "label", "prefill", "inputMode",
                "multiline", "isPassword", "autoCorrect", "leftElement", "rightElement",
                "enterKeyHint", "value", "placeholder", "defaultValue");

        for (final Map<String, Object> field : list(form().get("content"))) {
            final Map<String, Object> content = map(field.get("content"));
            for (final String key : required) {
                assertTrue(content.containsKey(key),
                        map(field.get("meta")).get("component") + " is missing " + key
                                + ": " + content.keySet());
            }
        }
    }

    @Test
    @DisplayName("a form with no bundled schema takes its fields from what was authored")
    void authoredFieldsAreDerived() {
        // An AEM Forms component names no schema - it declares itself a form and carries its fields.
        context.build().resource(ROOT + "/customform",
                "sling:resourceType", "fd/franklin/components/form/v1/form", "fieldType", "form");
        context.build().resource(ROOT + "/customform/panel",
                "sling:resourceType", "core/fd/components/form/panelcontainer/v1/panelcontainer",
                "fieldType", "panel", "name", "details");
        context.build().resource(ROOT + "/customform/panel/patient",
                "sling:resourceType", "core/fd/components/form/textinput/v1/textinput",
                "fieldType", "text-input", "name", "patient_name", "title", "Patient Name",
                "required", true);
        context.build().resource(ROOT + "/customform/panel/doctor",
                "sling:resourceType", "core/fd/components/form/dropdown/v1/dropdown",
                "fieldType", "drop-down", "name", "doctor_selection", "title", "Select Doctor",
                "enum", new String[] {"dr_smith", "dr_jones"},
                "enumNames", new String[] {"Dr. Smith", "Dr. Jones"});
        context.build().resource(ROOT + "/customform/submit",
                "sling:resourceType", "core/fd/components/form/button/v1/button",
                "fieldType", "button", "name", "submit", "title", "Book Appointment");

        final Map<String, Object> form = form();
        assertEquals("Book Appointment", map(form.get("meta")).get("submitButtonLabel"),
                "the label comes off the authored button");

        final List<Map<String, Object>> fields = list(form.get("content"));
        assertEquals(2, fields.size(), "the panel is flattened and the button is not a field");

        assertEquals("textField", map(fields.get(0).get("meta")).get("component"));
        assertEquals("Patient Name", map(fields.get(0).get("content")).get("label"));

        assertEquals("dropdown", map(fields.get(1).get("meta")).get("component"));
        @SuppressWarnings("unchecked")
        final List<Map<String, Object>> options =
                (List<Map<String, Object>>) map(fields.get(1).get("content")).get("options");
        assertEquals("Dr. Smith", options.get(0).get("label"),
                "enum and enumNames are zipped back together");
        assertEquals("dr_smith", options.get(0).get("value"));
    }

    @Test
    @DisplayName("an authored field's label, path and placeholder come off the node")
    void authoredFieldValuesAreRead() {
        // Copied from /content/binji-site/language-masters/en/form-test - the label is jcr:title and
        // the profile path is 'name', which is the same dotted form the bundled schemas use.
        context.build().resource(ROOT + "/form",
                "sling:resourceType", "fd/franklin/components/form/v1/form", "fieldType", "form");
        context.build().resource(ROOT + "/form/text_input",
                "sling:resourceType", "core/fd/components/form/textinput/v1/textinput",
                "fieldType", "text-input",
                "jcr:title", "First Name",
                "name", "person.name.firstName",
                "placeholder", "Enter your first name",
                "default", "John");

        final Map<String, Object> content = map(list(form().get("content")).get(0).get("content"));

        assertEquals("First Name", content.get("label"), "the label is jcr:title, not title");
        assertEquals("Enter your first name", content.get("placeholder"));

        // The node carries 'name', not 'path'. They are different things, so path stays empty.
        assertEquals("", content.get("path"));
        assertEquals(Boolean.FALSE, content.get("prefill"));
        assertEquals("John", content.get("defaultValue"), "the editor calls it 'default'");
    }

    @Test
    @DisplayName("an authored password field is recognised and never pre-filled")
    void authoredPasswordField() {
        context.build().resource(ROOT + "/form",
                "sling:resourceType", "fd/franklin/components/form/v1/form", "fieldType", "form");
        context.build().resource(ROOT + "/form/password",
                "sling:resourceType", "core/fd/components/form/password/v1/password",
                "fieldType", "password", "jcr:title", "Password", "name", "password");

        final Map<String, Object> field = list(form().get("content")).get(0);
        assertEquals("textField", map(field.get("meta")).get("component"));
        assertEquals(Boolean.TRUE, map(field.get("content")).get("isPassword"));
        assertEquals(Boolean.FALSE, map(field.get("content")).get("prefill"));
    }

    @Test
    @DisplayName("a field with no configured id is still addressable")
    void blankIdsFallBackToThePath() {
        // Twenty of the checkout fields ship with an empty id. Emitting that verbatim would give the
        // client a field it cannot name, so the profile path stands in.
        context.build().resource(ROOT + "/checkout",
                "sling:resourceType", "core/franklin/components/block/v1/block",
                "model", "form", "formName", "checkout");

        final List<Map<String, Object>> fields = list(form().get("content"));
        assertTrue(fields.stream().noneMatch(f ->
                        String.valueOf(map(f.get("meta")).get("id")).isEmpty()),
                "no field may reach the client without an id");
        assertEquals("person.name.firstName", map(fields.get(0).get("meta")).get("id"));
    }

    @Test
    @DisplayName("a block that is not a form is left alone")
    void nonFormBlocksAreUntouched() {
        context.build().resource(ROOT + "/hero",
                "sling:resourceType", "core/franklin/components/block/v1/block",
                "model", "hero", "text", "<h1>Welcome</h1>");

        assertTrue(content(true).stream()
                .noneMatch(c -> "form".equals(map(c.get("meta")).get("component"))),
                "guessing a block is a form would turn unrelated content into an empty form");
    }

    @Test
    @DisplayName("v1 keeps the flat block with a schema beside it")
    void v1IsUnchanged() {
        signInBlock();

        final Map<String, Object> block = content(false).get(0);
        assertEquals("sign-in", map(block.get("meta")).get("component"));
        assertEquals("login", map(block.get("content")).get("buttoneventtype"),
                "v1 content is still the authored properties");
        assertTrue(block.containsKey("schema"), "v1 still carries the sidecar schema");
    }
}

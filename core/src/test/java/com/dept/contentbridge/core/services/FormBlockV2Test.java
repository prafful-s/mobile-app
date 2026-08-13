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
        return list(map(map(app.get("screens")).get("en")).get("content"));
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

        assertEquals("personalEmail.address", content.get("path"), "config 'name' is the target 'path'");
        assertEquals(Boolean.TRUE, content.get("prefill"), "it knows where its value lives");
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
        assertEquals(List.of("mode", "path", "label", "options", "prefill"),
                List.copyOf(content.keySet()), "a dropdown carries exactly these five keys");

        final List<?> options = (List<?>) content.get("options");
        assertTrue(options.size() > 1, "a dropdown with no options cannot be rendered");
    }

    @Test
    @DisplayName("each widget family has its own content shape")
    void contentShapeFollowsTheWidget() {
        context.build().resource(ROOT + "/form",
                "sling:resourceType", "core/franklin/components/block/v1/block",
                "model", "form", "formName", "name-change-request");

        final List<String> typed = List.of("mode", "path", "label", "prefill", "inputMode",
                "multiline", "isPassword", "autoCorrect", "leftElement", "rightElement",
                "enterKeyHint", "value", "placeholder", "defaultValue");
        final List<String> toggle = List.of("options", "prefill", "variant");

        for (final Map<String, Object> field : list(form().get("content"))) {
            final String component = String.valueOf(map(field.get("meta")).get("component"));
            final Map<String, Object> content = map(field.get("content"));

            if (List.of("checkboxGroup", "radioGroup").contains(component)) {
                // A group says nothing about itself beyond an optional question label; each option
                // carries its own path and label.
                assertTrue(content.keySet().containsAll(toggle), component + ": " + content);
                assertFalse(content.containsKey("mode"), component + " is not typed into");
            } else if ("dropdown".equals(component)) {
                assertEquals(List.of("mode", "path", "label", "options", "prefill"),
                        List.copyOf(content.keySet()), component + ": " + content);
            } else {
                for (final String key : typed) {
                    assertTrue(content.containsKey(key),
                            component + " is missing " + key + ": " + content.keySet());
                }
            }
        }
    }

    @Test
    @DisplayName("a checkbox group puts the path and label on each option, not on the field")
    void checkboxGroupShape() {
        context.build().resource(ROOT + "/form",
                "sling:resourceType", "core/franklin/components/block/v1/block",
                "model", "form", "formName", "loan-application-form");

        final Map<String, Object> authorize = list(form().get("content")).stream()
                .filter(f -> "authorize".equals(map(f.get("meta")).get("id")))
                .findFirst().orElseThrow(() -> new AssertionError("authorize field missing"));

        assertEquals("checkboxGroup", map(authorize.get("meta")).get("component"));

        final Map<String, Object> content = map(authorize.get("content"));
        // loan-application-form declares variant 'switch'; the configuration decides where it speaks.
        assertEquals("switch", content.get("variant"));
        assertFalse(content.containsKey("label"), "this field has none - the option carries it");
        assertFalse(content.containsKey("mode"));

        final Map<String, Object> option = list(content.get("options")).get(0);
        assertEquals(List.of("path", "label", "checkedValue", "uncheckedValue"),
                List.copyOf(option.keySet()));
        assertEquals("authorize", option.get("path"), "each option says where its own value goes");
        assertTrue(String.valueOf(option.get("label")).startsWith("I authorize"));
        assertEquals("true", option.get("checkedValue"), "the configured option value");
    }

    @Test
    @DisplayName("an authored checkbox is one node standing for one option")
    void authoredCheckboxShape() {
        // Copied from form-test: a checkbox has no enum/enumNames - it is a single node carrying its
        // own name, title and checkedValue. Reading only the arrays left it with no options at all.
        context.build().resource(ROOT + "/form",
                "sling:resourceType", "fd/franklin/components/form/v1/form", "fieldType", "form");
        context.build().resource(ROOT + "/form/checkbox",
                "sling:resourceType", "core/fd/components/form/checkbox/v1/checkbox",
                "fieldType", "checkbox",
                "jcr:title", "SMS",
                "name", "consents.marketing.sms.val",
                "checkedValue", "true");

        final Map<String, Object> field = list(form().get("content")).get(0);
        assertEquals("checkboxGroup", map(field.get("meta")).get("component"));

        final Map<String, Object> content = map(field.get("content"));
        assertEquals(List.of("options", "prefill", "variant"), List.copyOf(content.keySet()),
                "the node's title is the option's label, so it is not repeated on the group");

        final Map<String, Object> option = list(content.get("options")).get(0);
        assertEquals("consents.marketing.sms.val", option.get("path"));
        assertEquals("SMS", option.get("label"));
        assertEquals("true", option.get("checkedValue"), "read off the node, not invented");
        assertEquals("", option.get("uncheckedValue"), "the node sets none");
    }

    @Test
    @DisplayName("a radio group has the same shape as a checkbox group")
    void radioGroupMatchesCheckbox() {
        context.build().resource(ROOT + "/form",
                "sling:resourceType", "core/franklin/components/block/v1/block",
                "model", "form", "formName", "insurance-plan-selection");

        final Map<String, Object> radio = list(form().get("content")).get(0);
        assertEquals("radioGroup", map(radio.get("meta")).get("component"));

        final Map<String, Object> content = map(radio.get("content"));
        assertEquals(List.of("label", "options", "prefill", "variant"),
                List.copyOf(content.keySet()));
        assertEquals("radio", content.get("variant"), "no variant configured, so the control names it");

        // A radio group asks a question the options only answer, so its label is kept rather than
        // dropped for the sake of matching the checkbox shape exactly.
        assertEquals("Are you looking for coverage for yourself or your family?", content.get("label"));

        final Map<String, Object> option = list(content.get("options")).get(0);
        assertEquals(List.of("path", "label", "checkedValue", "uncheckedValue"),
                List.copyOf(option.keySet()), "same option shape as a checkbox group");
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

        assertEquals("person.name.firstName", content.get("path"), "the node's 'name' is the path");
        assertEquals(Boolean.TRUE, content.get("prefill"), "it knows where its value lives");
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
    @DisplayName("a date-typed field becomes a datePicker - and only a date-typed one")
    void dateTypeBecomesDatePicker() {
        context.build().resource(ROOT + "/form",
                "sling:resourceType", "core/franklin/components/block/v1/block",
                "model", "form", "formName", "flight-search");

        final Map<String, Object> date = list(form().get("content")).stream()
                .filter(f -> "flight-date".equals(map(f.get("meta")).get("id")))
                .findFirst().orElseThrow(() -> new AssertionError("flight-date field missing"));

        assertEquals("datePicker", map(date.get("meta")).get("component"));

        final Map<String, Object> content = map(date.get("content"));
        assertEquals(List.of("mode", "path", "type", "label", "prefill", "withDateFormatInLabel"),
                List.copyOf(content.keySet()), "a datePicker carries exactly these six keys");
        assertEquals("single", content.get("type"));
        assertEquals(Boolean.FALSE, content.get("withDateFormatInLabel"));
        assertFalse(content.containsKey("inputMode"), "nothing is typed into a datePicker");
        assertFalse(content.containsKey("value"), "a datePicker has no free-text value");
    }

    @Test
    @DisplayName("a date-looking field typed as text stays a text field")
    void dateLookingTextStaysText() {
        // create-account has both: dateOfBirth and cardExpiry are typed 'text'. The type decides the
        // widget - guessing from a label would turn 'EXPIRATION DATE' into a date picker, which is a
        // poor way to enter MM/YY.
        // showcreditcard=true so cardExpiry is emitted (defaults to hidden otherwise).
        context.build().resource(ROOT + "/form",
                "sling:resourceType", "core/franklin/components/block/v1/block",
                "model", "form", "formName", "create-account",
                "showcreditcard", true);

        for (final String id : List.of("dateOfBirth", "cardExpiry")) {
            final Map<String, Object> field = list(form().get("content")).stream()
                    .filter(f -> id.equals(map(f.get("meta")).get("id")))
                    .findFirst().orElseThrow(() -> new AssertionError(id + " missing"));

            assertEquals("textField", map(field.get("meta")).get("component"),
                    id + " is typed 'text', so it renders as one");
        }
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

    private List<String> ids(final Map<String, Object> form) {
        return list(form.get("content")).stream()
                .map(f -> String.valueOf(map(f.get("meta")).get("id")))
                .collect(java.util.stream.Collectors.toList());
    }

    @Test
    @DisplayName("create-account emits every field when the authored block sets no show flags")
    void createAccountFullSchemaWithoutFlags() {
        context.build().resource(ROOT + "/create",
                "sling:resourceType", "core/franklin/components/block/v1/block",
                "model", "create-account");
        // showaddress/showdateofbirth/showcommunicationpreferences default true, so their fields
        // stay in. showcreditcard/showloyaltyprogram/showprivacypolicy default false, so those
        // fields drop out. Net expected: 33 - 8 (creditcard block) - 1 (loyalty) - 1 (privacy) = 23.
        final List<String> ids = ids(form());

        assertTrue(ids.contains("address"), "showaddress defaults true");
        assertTrue(ids.contains("dateOfBirth"), "showdateofbirth defaults true");
        assertTrue(ids.contains("marketingConsent"), "showcommunicationpreferences defaults true");
        assertFalse(ids.contains("cardName"), "showcreditcard defaults false");
        assertFalse(ids.contains("joinLoyaltyProgram"), "showloyaltyprogram defaults false");
        assertFalse(ids.contains("privacyPolicy"), "showprivacypolicy defaults false");
    }

    @Test
    @DisplayName("create-account drops address/date-of-birth fields when the author turns them off")
    void createAccountAddressAndDobHidden() {
        context.build().resource(ROOT + "/create",
                "sling:resourceType", "core/franklin/components/block/v1/block",
                "model", "create-account",
                "showaddress", false, "showdateofbirth", false);

        final List<String> ids = ids(form());
        assertFalse(ids.contains("address"));
        assertFalse(ids.contains("zipCode"));
        assertFalse(ids.contains("city"));
        assertFalse(ids.contains("dateOfBirth"));
        assertTrue(ids.contains("firstName"), "ungated fields stay");
    }

    @Test
    @DisplayName("create-account shows credit-card fields when the author turns showcreditcard on")
    void createAccountCreditCardShown() {
        context.build().resource(ROOT + "/create",
                "sling:resourceType", "core/franklin/components/block/v1/block",
                "model", "create-account",
                "showcreditcard", true);

        final List<String> ids = ids(form());
        assertTrue(ids.contains("cardName"));
        assertTrue(ids.contains("cardNumber"));
        assertTrue(ids.contains("cardExpiry"));
        assertTrue(ids.contains("cardCvv"));
        assertTrue(ids.contains("trialConsent"));
    }

    @Test
    @DisplayName("visibleIf treats the string 'true' the same as boolean true (authored props "
            + "arrive as strings from CRXDE)")
    void visibleIfAcceptsStringBooleans() {
        context.build().resource(ROOT + "/create",
                "sling:resourceType", "core/franklin/components/block/v1/block",
                "model", "create-account",
                "showcreditcard", "true", "showloyaltyprogram", "false");

        final List<String> ids = ids(form());
        assertTrue(ids.contains("cardName"), "string 'true' is truthy");
        assertFalse(ids.contains("joinLoyaltyProgram"), "string 'false' is falsy");
    }

    @Test
    @DisplayName("create-account emits variant-specific fields only when variant matches")
    void createAccountVariantGating() {
        context.build().resource(ROOT + "/create",
                "sling:resourceType", "core/franklin/components/block/v1/block",
                "model", "create-account", "variant", "luma");

        final List<String> ids = ids(form());
        assertTrue(ids.contains("shoeSize"), "variant=luma reveals luma-preference fields");
        assertTrue(ids.contains("shirtSize"));
        assertTrue(ids.contains("favoriteColor"));
        assertFalse(ids.contains("companyName"), "companyName is bodea-only");
        assertFalse(ids.contains("companySize"), "companySize is bodea-only");
        assertFalse(ids.contains("wkndFlyMember"), "wkndFlyMember is wknd-fly-only");
        assertFalse(ids.contains("frescopaOwner"), "frescopaOwner is frescopa-only");
        assertFalse(ids.contains("favouriteNewsCategory"), "favouriteNewsCategory is expnews-only");
    }

    @Test
    @DisplayName("create-account bodea variant reveals company/industry/topic fields together")
    void createAccountBodeaVariant() {
        context.build().resource(ROOT + "/create",
                "sling:resourceType", "core/franklin/components/block/v1/block",
                "model", "create-account", "variant", "bodea");

        final List<String> ids = ids(form());
        assertTrue(ids.contains("companyName"));
        assertTrue(ids.contains("companySize"));
        assertTrue(ids.contains("industry"));
        assertTrue(ids.contains("topicOfInterest"));
        assertFalse(ids.contains("shoeSize"), "not luma");
    }

    @Test
    @DisplayName("create-account without a variant hides every variant-only field")
    void createAccountNoVariantHidesVariantFields() {
        context.build().resource(ROOT + "/create",
                "sling:resourceType", "core/franklin/components/block/v1/block",
                "model", "create-account");

        final List<String> ids = ids(form());
        for (final String v : List.of("companyName", "companySize", "industry", "topicOfInterest",
                "wkndFlyMember", "shoeSize", "shirtSize", "favoriteColor", "frescopaOwner",
                "favouriteNewsCategory", "accountType")) {
            assertFalse(ids.contains(v), v + " should be hidden with no variant set");
        }
    }

    @Test
    @DisplayName("test-drive schedule-appointment variant hides the address section (notEquals)")
    void testDriveScheduleAppointmentHidesAddress() {
        context.build().resource(ROOT + "/td",
                "sling:resourceType", "core/franklin/components/block/v1/block",
                "model", "test-drive", "variant", "schedule-appointment");

        final List<String> ids = ids(form());
        assertFalse(ids.contains("address"));
        assertFalse(ids.contains("city"));
        assertFalse(ids.contains("zipCode"));
        assertTrue(ids.contains("firstName"), "personal fields stay");
    }

    @Test
    @DisplayName("test-drive default variant keeps the address section (notEquals defaults visible)")
    void testDriveDefaultVariantKeepsAddress() {
        context.build().resource(ROOT + "/td",
                "sling:resourceType", "core/franklin/components/block/v1/block",
                "model", "test-drive");

        final List<String> ids = ids(form());
        assertTrue(ids.contains("address"), "no variant set - notEquals defaults visible");
        assertTrue(ids.contains("city"));
        assertTrue(ids.contains("zipCode"));
    }

    @Test
    @DisplayName("insurance block becomes a form with 7 fields (no gates)")
    void insuranceBecomesForm() {
        context.build().resource(ROOT + "/ins",
                "sling:resourceType", "core/franklin/components/block/v1/block",
                "model", "insurance");

        final Map<String, Object> form = form();
        assertEquals("form", map(form.get("meta")).get("component"),
                "an insurance block is recognised as a form via its component name");

        final List<String> ids = ids(form);
        assertEquals(List.of("firstName", "lastName", "birthDate", "permanentAddress",
                "city", "zipCode", "country"), ids);
    }

    @Test
    @DisplayName("add-external default (account variant) shows accountNumber, hides cardNumber")
    void addExternalAccountVariant() {
        context.build().resource(ROOT + "/ext",
                "sling:resourceType", "core/franklin/components/block/v1/block",
                "model", "add-external");

        final List<String> ids = ids(form());
        assertTrue(ids.contains("accountNumber"), "default variant is account");
        assertFalse(ids.contains("cardNumber"), "cardNumber is credit-card-only");
        assertTrue(ids.contains("type"), "type dropdown always present");
        assertTrue(ids.contains("origin"));
        assertTrue(ids.contains("authorizedUserName"));
    }

    @Test
    @DisplayName("add-external credit-card variant swaps accountNumber for cardNumber")
    void addExternalCreditCardVariant() {
        context.build().resource(ROOT + "/ext",
                "sling:resourceType", "core/franklin/components/block/v1/block",
                "model", "add-external", "variant", "credit-card");

        final List<String> ids = ids(form());
        assertFalse(ids.contains("accountNumber"), "hidden on credit-card variant");
        assertTrue(ids.contains("cardNumber"));
    }

    @Test
    @DisplayName("checkout-shipping gates cardNumber + lumaLoyalty on show flags")
    void checkoutShippingBooleanGates() {
        context.build().resource(ROOT + "/cs",
                "sling:resourceType", "core/franklin/components/block/v1/block",
                "model", "checkout-shipping");

        final List<String> defaultIds = ids(form());
        assertFalse(defaultIds.contains("cardNumber"), "showcardnumberfield defaults false");
        assertFalse(defaultIds.contains("lumaLoyalty"), "showloyalty defaults false");
    }

    @Test
    @DisplayName("after a visibility filter, remaining fields get a contiguous 1..N order")
    void orderIsCompactedAfterFiltering() {
        context.build().resource(ROOT + "/create",
                "sling:resourceType", "core/franklin/components/block/v1/block",
                "model", "create-account",
                "showaddress", false, "showdateofbirth", false);

        final List<Map<String, Object>> fields = list(form().get("content"));
        for (int i = 0; i < fields.size(); i++) {
            assertEquals(i + 1, map(fields.get(i).get("meta")).get("order"),
                    "hidden fields must not leave gaps in the ordering");
        }
    }
}

package com.edsMobileApp.core.services.impl;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ValueMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.edsMobileApp.core.schema.SchemaConstants;
import com.edsMobileApp.core.services.FormSchemaService;

/**
 * Assembles an authored form into the single {@code form} component the client renders.
 *
 * <pre>
 * { "meta":    { "id", "link", "order", "component": "form", "eventType", "webhookUrl",
 *                "toastMessage", "hideResetButton", "resetButtonLabel", "submitButtonLabel" },
 *   "content": [ field, field, … ] }
 * </pre>
 *
 * <p>Two things differ from every other component. The form's own settings sit in {@code meta}
 * rather than {@code content}, because {@code content} is the field list - a form is a container of
 * fields the way a panel is a container of components. And the fields are an ordered array, because
 * a form is rendered top to bottom and a keyed object only happens to preserve that.</p>
 *
 * <p>Fields come from a bundled schema where one exists, and from the block's own authored children
 * where it does not. A form built in the AEM Forms editor therefore needs no configuration written
 * for it, and both routes produce the same shape.</p>
 */
final class FormBlockBuilder {

    private static final Logger LOG = LoggerFactory.getLogger(FormBlockBuilder.class);

    /** The one component name a client has to know to render a form. */
    static final String COMPONENT_FORM = "form";

    /** A schema field of this type names a secondary navigation link - see {@link #secondaryLink}. */
    private static final String TYPE_LINK = "link";

    /** Guard against a pathological or cyclic authored tree while collecting fields. */
    private static final int MAX_FIELD_DEPTH = 6;

    private static final String DEFAULT_RESET_LABEL = "Reset";
    private static final String DEFAULT_SUBMIT_LABEL = "Submit";

    /** Authored property names per emitted setting, most specific first. */
    private static final String[] EVENT_TYPE_KEYS = {"mobilebuttoneventtype"};
    private static final String[] MOBILE_REDIRECT_KEYS = {"mobileRedirectUrl"};
    /** Authored per-block custom style rows the block emits - see {@link #applyCheckboxWidth}. */
    private static final String CUSTOM_MOBILE_STYLE_KEY = "custommobilestyle";
    /** The one {@code custommobilestyle} row key this export currently understands - see
     *  {@link #applyCheckboxWidth}. */
    private static final String STYLE_KEY_WIDTH = "checkbox-width";
    /** The radio counterpart of {@link #STYLE_KEY_WIDTH} - see {@link #applyCheckboxWidth}. */
    private static final String STYLE_KEY_RADIO_WIDTH = "radio-width";
    private static final String[] WEBHOOK_KEYS = {"webhookUrl", "webhook", "action", "endpoint"};
    private static final String[] TOAST_KEYS = {"toastMessage", "toast-message", "success-message"};
    private static final String[] SUBMIT_LABEL_KEYS = {
        "submitButtonLabel", "buttontext", "button-label", "sign-in-btn-text", "submitText",
    };
    private static final String[] RESET_LABEL_KEYS = {"resetButtonLabel", "resetText"};

    /** No authored property backs this form's heading - see {@link #FORM_HEADINGS}. */
    private static final String[] NO_KEYS = new String[0];

    /**
     * Per-form heading configuration: the block's own property name(s) for its on-page title, and
     * the text the block itself falls back to when none is authored. Every form gets an entry, so
     * every form gets a heading component - for the eight forms whose block actually has a heading
     * field (create-account, credit-card-application-form, insurance, insurance-plan-selection,
     * join-us, loan-application-form, medicare-plan-selection, new-account, test-drive) the property
     * key(s) and default text are read straight off that block's source (the EDS block {@code .js},
     * not this project) so the default matches what actually renders when a site author leaves the
     * field blank. The remaining forms have no such property in their block at all, so their entry
     * carries no keys - the heading is always the form's own name, title-cased.
     *
     * <p>{@code test-drive}'s default varies per authored {@code variant} in the block itself
     * ({@code VARIANTS[variant].formTitle}); the text here is the block's own fallback default
     * ({@code VARIANTS['test-drive'].formTitle}), used when the authored variant has no match of its
     * own - an approximation of the per-variant logic, not a full port of it.</p>
     */
    private static final Map<String, Heading> FORM_HEADINGS = buildFormHeadings();

    private static Map<String, Heading> buildFormHeadings() {
        final Map<String, Heading> headings = new LinkedHashMap<>();
        // Forms whose block authors a heading property - key(s) and default read from the block .js.
        headings.put("create-account", new Heading(new String[]{"createaccounttitle"}, "Create an account"));
        headings.put("credit-card-application-form",
                new Heading(new String[]{"form-title", "formtitle"}, "Credit Card Application"));
        headings.put("insurance", new Heading(new String[]{"title"}, "Life Insurance"));
        // The plan selections, plan application and get offer take their heading only from the
        // authored Form Title: no compiled default and no fallback to the block's Main Heading.
        headings.put("insurance-plan-selection", new Heading(new String[]{"form-title", "formtitle"}, ""));
        headings.put("join-us", new Heading(new String[]{"form-title", "formtitle"}, "JOIN WKND FLY CLUB"));
        headings.put("loan-application-form",
                new Heading(new String[]{"form-title", "formtitle"}, "Home Loan Application Form"));
        headings.put("medicare-plan-selection", new Heading(new String[]{"form-title", "formtitle"}, ""));
        headings.put("new-account",
                new Heading(new String[]{"form-title", "formtitle"}, "New Account Application"));
        // No compiled default: test-drive (and its schedule-appointment variant) show a pre-form
        // title only when the block authors form-title, or a site overrides it (e.g. luma's
        // defaults.json). With neither, heading() emits nothing - the form's own field-list heading
        // ("Your personal informations") is the first title, so the outer one is not duplicated.
        headings.put("test-drive", new Heading(new String[]{"form-title", "formtitle"}, ""));

        // Forms with no heading property in their block - default is the form's own name.
        headings.put("add-external", new Heading(NO_KEYS, "Add External"));
        headings.put("checkout", new Heading(NO_KEYS, "Checkout"));
        headings.put("checkout-shipping", new Heading(NO_KEYS, "Checkout Shipping"));
        headings.put("child-account-application", new Heading(NO_KEYS, "Child Account Application"));
        headings.put("flight-search", new Heading(NO_KEYS, "Flight Search"));
        headings.put("get-offer", new Heading(new String[]{"form-title", "formtitle"}, ""));
        headings.put("name-change-request", new Heading(NO_KEYS, "Name Change Request"));
        headings.put("plan-application", new Heading(new String[]{"form-title", "formtitle"}, ""));
        headings.put("set-up-auto-save", new Heading(NO_KEYS, "Set Up Auto Save"));
        headings.put("sign-in", new Heading(NO_KEYS, "Sign In"));
        headings.put("transfer-funds", new Heading(NO_KEYS, "Transfer Funds"));
        return Collections.unmodifiableMap(headings);
    }

    /**
     * The {@code variant} value a form's own block dialog defaults to when an author sets none - read
     * from each block's own field definition (its {@code variant} select's own {@code value}), not
     * guessed. Only the forms whose fields actually gate on {@code variant} via {@code variants}/
     * {@code equals}/{@code equalsAny} (where an absent value reads as "matches nothing") need an
     * entry; a form gating with {@code not-equals} already defaults to visible without one.
     */
    private static final Map<String, String> DEFAULT_VARIANTS = Map.of(
            "add-external", "account",
            "create-account", "default",
            "test-drive", "test-drive");

    /**
     * {@code authored}, with {@code variant} filled in from {@link #DEFAULT_VARIANTS} when the form
     * names an entry there and the block itself carries none - so a block whose author never touched
     * the variant dropdown is evaluated exactly as its own dialog default would render it, not as
     * though variant-gated fields simply do not exist.
     */
    Map<String, Object> withDefaultVariant(final String formName, final Map<String, Object> authored) {
        if (formName == null || authored == null) {
            return authored;
        }
        final String defaultVariant = DEFAULT_VARIANTS.get(formName);
        if (defaultVariant == null) {
            return authored;
        }
        final Object existing = authored.get("variant");
        if (existing instanceof String && StringUtils.isNotBlank((String) existing)) {
            return authored;
        }
        final Map<String, Object> merged = new LinkedHashMap<>(authored);
        merged.put("variant", defaultVariant);
        return merged;
    }

    /** One form's heading property name(s) (most specific first) and its block's own default text. */
    private static final class Heading {
        private final String[] keys;
        private final String defaultText;

        Heading(final String[] keys, final String defaultText) {
            this.keys = keys;
            this.defaultText = defaultText;
        }
    }

    private final FormSchemaService formSchemaService;
    private SiteDefaults siteDefaults;

    FormBlockBuilder(final FormSchemaService formSchemaService) {
        this.formSchemaService = formSchemaService;
    }

    /**
     * Wires the per-site defaults source. Called once, on service activation; when unset (in the
     * few tests that construct the builder directly), heading() falls back to its compiled-in
     * {@link #FORM_HEADINGS} defaults.
     */
    void setSiteDefaults(final SiteDefaults siteDefaults) {
        this.siteDefaults = siteDefaults;
    }

    /**
     * The form's on-page heading, as a standalone {@code title} component to sit right before the
     * form in {@code content[]} - the block's own settings panel carries a heading field (e.g.
     * {@code create-account}'s "Heading", authored value "Create an account"), but that text
     * describes the page, not a form field, so it does not belong inside the form's own {@code meta}
     * or field list. Returns {@code null} for a form with no heading in the reference (most of
     * them) - see {@link #FORM_HEADINGS}.
     *
     * @param formName the schema name, used to look up which property (if any) carries the heading
     * @param authored the block's own properties
     * @param id       a stable id, generated the same way every other synthetic component's is
     * @param order    position among its siblings
     */
    Map<String, Object> heading(final String formName, final Map<String, Object> authored,
                                final String id, final int order, final String resourcePath) {
        final Heading config = FORM_HEADINGS.get(formName);
        if (config == null) {
            return null;
        }
        // Per-site override for the default text, e.g. Luma's defaults.json.formHeadings.
        // The keys (block property names) stay compiled-in - those describe the block itself, not
        // the site. Only the display text is site-overridable.
        final String defaultText = siteOverride(formName, resourcePath, config.defaultText);
        final String text = first(authored, config.keys, defaultText);
        // A form whose block authors no title and whose reference carries no default (test-drive)
        // gets no pre-form heading at all, rather than an empty title box.
        if (StringUtils.isBlank(text)) {
            return null;
        }

        final Map<String, Object> meta = new LinkedHashMap<>();
        meta.put(SchemaConstants.KEY_ID, id);
        meta.put(SchemaConstants.KEY_ORDER, order);
        meta.put(SchemaConstants.KEY_COMPONENT, "title");

        final Map<String, Object> content = new LinkedHashMap<>();
        content.put("size", "medium");
        content.put("text", text);
        content.put("weight", "bold");
        content.put("textAlignment", "left");

        final Map<String, Object> title = new LinkedHashMap<>();
        title.put(SchemaConstants.KEY_META, meta);
        title.put(SchemaConstants.KEY_CONTENT, content);
        return title;
    }

    /**
     * @return the site's overridden default text for {@code formName}, or {@code compiledDefault}
     *         when no site override applies (no defaults file, or no {@code formHeadings} entry
     *         for this form). Never null.
     */
    private String siteOverride(final String formName, final String resourcePath,
                                final String compiledDefault) {
        if (siteDefaults == null || resourcePath == null) {
            return compiledDefault;
        }
        final Map<String, String> perSite = SiteDefaults.stringMap(
                siteDefaults.forPath(resourcePath), SiteDefaults.KEY_FORM_HEADINGS);
        return perSite.getOrDefault(formName, compiledDefault);
    }

    /**
     * @param resource  the authored form block
     * @param formName  the schema to use, which may name none
     * @param id        the component id, shared with the rest of the export's id scheme
     * @param order     position among its siblings
     * @param authored  the block's own properties, already read
     * @return the form component
     */
    Map<String, Object> build(final Resource resource, final String formName, final String id,
                              final int order, final Map<String, Object> authored) {
        final List<Map<String, Object>> fields = fields(resource, formName, authored);
        applyCheckboxWidth(fields, authored);

        final Map<String, Object> meta = new LinkedHashMap<>();
        meta.put(SchemaConstants.KEY_ID, id);
        meta.put("link", redirectScreen(first(authored, MOBILE_REDIRECT_KEYS, StringUtils.EMPTY)));
        meta.put(SchemaConstants.KEY_ORDER, order);
        meta.put(SchemaConstants.KEY_COMPONENT, COMPONENT_FORM);
        final Object mobileFormId = authored.get("mobileFormID");
        if (mobileFormId instanceof String && StringUtils.isNotBlank((String) mobileFormId)) {
            meta.put("formId", mobileFormId);
        }
        meta.put("eventType", first(authored, EVENT_TYPE_KEYS, StringUtils.EMPTY));
        meta.put("webhookUrl", first(authored, WEBHOOK_KEYS, StringUtils.EMPTY));
        meta.put("toastMessage", first(authored, TOAST_KEYS, StringUtils.EMPTY));

        // A reset button is opt-in: most authored forms have no reset control, and showing one the
        // author never asked for is worse than omitting a rarely-used affordance.
        meta.put("hideResetButton", true);
        meta.put("resetButtonLabel", first(authored, RESET_LABEL_KEYS, DEFAULT_RESET_LABEL));
        meta.put("submitButtonLabel",
                StringUtils.defaultIfBlank(first(authored, SUBMIT_LABEL_KEYS, null),
                        StringUtils.defaultIfBlank(submitLabelFromChildren(resource),
                                DEFAULT_SUBMIT_LABEL)));

        final Map<String, Object> form = new LinkedHashMap<>();
        form.put(SchemaConstants.KEY_META, meta);
        form.put(SchemaConstants.KEY_CONTENT, fields);
        return form;
    }

    /**
     * The secondary navigation link a form's own schema may declare - the generic form of what the
     * reference {@code sign-in} block does for its "Create an account" link (see
     * {@code addCreateAccountLink} in the EDS block's own {@code sign-in.js}): a link rendered
     * alongside the form but not one of its fields, pointing at one of the app's own named nav
     * screens rather than a value the form submits.
     *
     * <p>Declared as one more entry in the bundled field array (see {@code sign-in.json}):</p>
     * <pre>{ "type": "link", "label": "Create an account", "target": "registrationScreen" }</pre>
     *
     * <p>{@code target} names one of the three screen roles {@code PageJsonExporterImpl
     * .buildNavigation} already resolves for every export - {@code loginScreen}, {@code
     * profileScreen} or {@code registrationScreen} - so a link always points wherever the app's own
     * nav already sends a user for that role, whichever form declares it. Emitted as a standalone
     * {@code button} component, the same shape {@code edsMobileApp/mappings/button.json} produces
     * for any other navigable link, styled {@code text} rather than a filled button since it is a
     * secondary action beside the submit button, not a competing one.
     *
     * <p>Returns {@code null} when the form's schema declares no such field, or when the field names
     * a role this export could not resolve - a link with nowhere to go is worse than no link.</p>
     *
     * @param formName        the schema name being built
     * @param navScreensByRole {@code loginScreen}/{@code profileScreen}/{@code registrationScreen},
     *                        already resolved for this export (see {@code PageJsonExporterImpl
     *                        .buildNavigation}) - reused rather than re-derived so both the app's own
     *                        nav menu and this link agree on where each role goes
     * @param id              a stable id, generated the same way every other synthetic component's is
     * @param order           position among its siblings
     */
    Map<String, Object> secondaryLink(final String formName, final Map<String, String> navScreensByRole,
                                      final String id, final int order) {
        final Map<String, Object> field = linkField(formName);
        if (field == null) {
            return null;
        }
        final String target = str(field.get("target"));
        final String screen = navScreensByRole == null || target == null ? null : navScreensByRole.get(target);
        if (StringUtils.isBlank(screen)) {
            return null;
        }

        final Map<String, Object> meta = new LinkedHashMap<>();
        meta.put(SchemaConstants.KEY_ID, id);
        meta.put(SchemaConstants.KEY_ORDER, order);
        meta.put(SchemaConstants.KEY_COMPONENT, "button");

        final Map<String, Object> content = new LinkedHashMap<>();
        content.put("label", StringUtils.defaultString(str(field.get("label"))));
        content.put("linkTo", screen);
        content.put("variant", "text");
        content.put("alignment", "center");

        final Map<String, Object> button = new LinkedHashMap<>();
        button.put(SchemaConstants.KEY_META, meta);
        button.put(SchemaConstants.KEY_CONTENT, content);
        return button;
    }

    private static String redirectScreen(final String redirect) {
        final String text = StringUtils.trimToEmpty(redirect);
        if (!text.startsWith("/content/")) {
            return redirect;
        }
        final int suffixIndex = StringUtils.indexOfAny(text, '?', '#');
        final String suffix = suffixIndex >= 0 ? text.substring(suffixIndex) : "";
        final String path = StringUtils.stripEnd(
                suffixIndex >= 0 ? text.substring(0, suffixIndex) : text, "/");
        final String screen = StringUtils.removeEndIgnoreCase(
                StringUtils.substringAfterLast(path, "/"), ".html");
        return StringUtils.isEmpty(screen) ? redirect : screen + suffix;
    }

    /** @return the form's own {@code "type": "link"} schema entry, or {@code null} when it has none */
    private Map<String, Object> linkField(final String formName) {
        if (StringUtils.isBlank(formName)) {
            return null;
        }
        for (final Map<String, Object> field : formSchemaService.getSchema(formName)) {
            if (TYPE_LINK.equals(StringUtils.lowerCase(str(field.get("type")), java.util.Locale.ROOT))) {
                return field;
            }
        }
        return null;
    }

    private static String str(final Object value) {
        return value instanceof String ? (String) value : null;
    }

    /**
     * Configuration first, then whatever the author built.
     *
     * <p>The bundled schema wins because it is a contract that versions with the code, where an
     * authored form can be changed by anyone with edit access. Falling back rather than requiring
     * configuration is what lets a form appear on a page before anyone has written a schema for
     * it.</p>
     */
    private List<Map<String, Object>> fields(final Resource resource, final String formName,
                                             final Map<String, Object> authored) {
        final List<Map<String, Object>> configured = fromSchema(formName, authored);
        if (!configured.isEmpty()) {
            return configured;
        }
        final List<Map<String, Object>> collected = new ArrayList<>();
        collectAuthoredFields(resource, collected, 0);

        if (collected.isEmpty()) {
            LOG.debug("Form block {} has neither a bundled schema nor authored fields", resource.getPath());
        }
        return collected;
    }

    private List<Map<String, Object>> fromSchema(final String formName,
                                                 final Map<String, Object> authored) {
        if (StringUtils.isBlank(formName)) {
            return new ArrayList<>();
        }
        final List<Map<String, Object>> schema = formSchemaService.getSchema(formName);
        final List<Map<String, Object>> fields = new ArrayList<>(schema.size());

        // 'order' is the visible position - increment only when the field survives the visibleIf
        // filter, so the client renders 1..N without gaps that would look like dropped fields.
        int order = 1;
        for (final Map<String, Object> field : schema) {
            if (!FormFieldFactory.isVisible(field, authored)) {
                LOG.debug("Field '{}' hidden by visibleIf against authored properties",
                        field.get("id"));
                continue;
            }
            final Map<String, Object> built;
            if (field.get(SchemaConstants.KEY_COMPONENT) != null) {
                final Map<String, Object> meta = new LinkedHashMap<>();
                meta.put(SchemaConstants.KEY_ID, StringUtils.defaultIfBlank(
                    (String) field.get(SchemaConstants.KEY_ID),
                    field.get(SchemaConstants.KEY_COMPONENT) + "-" + order));
                meta.put(SchemaConstants.KEY_ORDER, order);
                meta.put(SchemaConstants.KEY_COMPONENT, field.get(SchemaConstants.KEY_COMPONENT));
                built = new LinkedHashMap<>(field);
                built.remove(SchemaConstants.KEY_COMPONENT);
                built.put(SchemaConstants.KEY_META, meta);
                built.putIfAbsent(SchemaConstants.KEY_CONTENT, Collections.emptyMap());
            } else {
                built = FormFieldFactory.fromConfig(field, order);
            }
            if (built != null) {
                fields.add(built);
                order++;
            }
        }
        return fields;
    }

    /**
     * Walks the authored tree collecting fields, descending through panels.
     *
     * <p>AEM Forms nests fields inside {@code panelcontainer} components, which group them visually
     * but hold no value of their own. Flattening them keeps the client's job simple: one form, one
     * ordered list of fields.</p>
     */
    private void collectAuthoredFields(final Resource parent, final List<Map<String, Object>> sink,
                                       final int depth) {
        if (depth > MAX_FIELD_DEPTH) {
            LOG.warn("Form nesting below {} exceeds {} levels - not descending further",
                    parent.getPath(), MAX_FIELD_DEPTH);
            return;
        }
        for (final Resource child : parent.getChildren()) {
            final String fieldType = child.getValueMap().get("fieldType", String.class);

            if (FormFieldFactory.isPanel(fieldType)) {
                collectAuthoredFields(child, sink, depth + 1);
                continue;
            }
            final Map<String, Object> field = FormFieldFactory.fromResource(child, sink.size() + 1);
            if (field != null) {
                sink.add(field);
            } else if (child.hasChildren()) {
                // Not a field and not a declared panel - a layout wrapper. Its children may still be
                // fields, so it is walked through rather than stopped at.
                collectAuthoredFields(child, sink, depth + 1);
            }
        }
    }

    /**
     * The submit label an author set on the form's button component, used when the block itself does
     * not name one.
     */
    private String submitLabelFromChildren(final Resource resource) {
        for (final Resource child : resource.getChildren()) {
            final ValueMap props = child.getValueMap();
            if (FormFieldFactory.isButton(props.get("fieldType", String.class))) {
                return props.get("title", String.class);
            }
            if (child.hasChildren()) {
                final String nested = submitLabelFromChildren(child);
                if (StringUtils.isNotBlank(nested)) {
                    return nested;
                }
            }
        }
        return null;
    }

    private static String first(final Map<String, Object> authored, final String[] keys,
                                final String fallback) {
        for (final String key : keys) {
            final Object value = authored.get(key);
            if (value instanceof String && StringUtils.isNotBlank((String) value)) {
                return (String) value;
            }
        }
        return fallback;
    }

    /**
     * Applies the block's authored {@code custommobilestyle} - a multifield of free-text
     * {@code key:value} rows (e.g. {@code "checkbox-width:250"}, {@code "radio-width:250"}) - as {@code
     * styles.width} on every {@code checkboxGroup} field this form emits. The mapping has no way
     * to express a per-instance width, and several checkboxGroup labels run long enough on a phone
     * screen to need one (WKND Fly Club's consent, SecurFinancial's mortgage authorization and
     * child-account consents, create-account's trial/marketing/privacy consents, checkout's email
     * consent) - see the {@code custommobilestyle} field added to each of those blocks' own
     * component models.
     *
     * <p>{@code custommobilestyle} is authored once per block, not per component, so today there is
     * no way to say which of a form's several components a given row targets - only {@code checkbox-width}
     * is read here, and it is applied to every {@code checkboxGroup} field, because a checkboxGroup
     * is the one component this form-building path currently needs a custom width for. Every other
     * row key ({@code padding}, or anything else) is ignored for now rather than guessed at; how a
     * block with more than one styleable component should target its rows is still open - Prafful
     * to confirm before this reads more than {@code checkbox-width}.</p>
     *
     * <p>A blank/unauthored value, or no {@code checkbox-width} row, leaves every checkboxGroup exactly as
     * built.</p>
     */
    @SuppressWarnings("unchecked")
    private static void applyCheckboxWidth(final List<Map<String, Object>> fields,
                                           final Map<String, Object> authored) {
        applyWidth(fields, authored, STYLE_KEY_WIDTH, FormFieldFactory.CHECKBOX_GROUP);
        applyWidth(fields, authored, STYLE_KEY_RADIO_WIDTH, FormFieldFactory.RADIO_GROUP);
    }

    /**
     * {@code checkbox-width} reaches every {@code checkboxGroup} and {@code radio-width} every
     * {@code radioGroup}, each under its own row key so a form can size one without the other.
     */
    @SuppressWarnings("unchecked")
    private static void applyWidth(final List<Map<String, Object>> fields,
                                   final Map<String, Object> authored, final String key,
                                   final String component) {
        final Integer width = authoredCustomStyleInt(authored, key);
        if (width == null) {
            return;
        }
        for (final Map<String, Object> field : fields) {
            final Object meta = field.get(SchemaConstants.KEY_META);
            if (!(meta instanceof Map) || !component.equals(
                    ((Map<String, Object>) meta).get(SchemaConstants.KEY_COMPONENT))) {
                continue;
            }
            // Merged into whatever styles the schema already gave the group (its layout), not
            // replacing them.
            final Object existing = field.get(SchemaConstants.KEY_STYLES);
            final Map<String, Object> styles = existing instanceof Map
                    ? new LinkedHashMap<>((Map<String, Object>) existing) : new LinkedHashMap<>();
            styles.put("width", width);
            field.put(SchemaConstants.KEY_STYLES, styles);
        }
    }

    /**
     * @return the integer value of {@code custommobilestyle}'s {@code key:value} row named
     *         {@code key} (the first one found, case-insensitive on the key), or {@code null} when
     *         the field is absent, carries no such row, or that row's value isn't a number.
     */
    private static Integer authoredCustomStyleInt(final Map<String, Object> authored, final String key) {
        final Object raw = authored.get(CUSTOM_MOBILE_STYLE_KEY);
        final List<?> rows = raw instanceof List ? (List<?>) raw
                : raw instanceof String[] ? Arrays.asList((String[]) raw) : null;
        if (rows == null) {
            return null;
        }
        for (final Object row : rows) {
            if (!(row instanceof String)) {
                continue;
            }
            final String[] parts = ((String) row).split(":", 2);
            if (parts.length != 2 || !parts[0].trim().equalsIgnoreCase(key)) {
                continue;
            }
            final Integer value = asInteger(parts[1].trim());
            if (value == null) {
                LOG.warn("custommobilestyle row '{}' is not a number - ignoring it", row);
                return null;
            }
            return value;
        }
        return null;
    }

    /** @return {@code value} parsed as an integer, or {@code null} when it is not one. */
    private static Integer asInteger(final String value) {
        try {
            return Integer.valueOf(value.trim());
        } catch (final NumberFormatException e) {
            return null;
        }
    }

}

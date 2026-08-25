package com.dept.contentbridge.core.services.impl;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ValueMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.dept.contentbridge.core.schema.SchemaConstants;
import com.dept.contentbridge.core.services.FormSchemaService;

/**
 * Assembles an authored form into the single {@code form} component the client renders.
 *
 * <pre>
 * { "meta":    { "id", "order", "component": "form", "eventType", "webhookUrl",
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

    /** Guard against a pathological or cyclic authored tree while collecting fields. */
    private static final int MAX_FIELD_DEPTH = 6;

    private static final String DEFAULT_RESET_LABEL = "Reset";
    private static final String DEFAULT_SUBMIT_LABEL = "Submit";

    /** Authored property names per emitted setting, most specific first. */
    private static final String[] EVENT_TYPE_KEYS = {"eventType", "buttoneventtype", "event"};
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
        headings.put("insurance-plan-selection", new Heading(
                new String[]{"formHeading", "formheading"}, "Which type of health insurance should I get?"));
        headings.put("join-us", new Heading(new String[]{"form-title", "formtitle"}, "JOIN WKND FLY CLUB"));
        headings.put("loan-application-form",
                new Heading(new String[]{"form-title", "formtitle"}, "Home Loan Application Form"));
        headings.put("medicare-plan-selection", new Heading(
                new String[]{"formHeading", "formheading"}, "Which type of Medicare plan should I get?"));
        headings.put("new-account",
                new Heading(new String[]{"form-title", "formtitle"}, "New Account Application"));
        headings.put("test-drive", new Heading(new String[]{"form-title", "formtitle"}, "Schedule test drive"));

        // Forms with no heading property in their block - default is the form's own name.
        headings.put("add-external", new Heading(NO_KEYS, "Add External"));
        headings.put("checkout", new Heading(NO_KEYS, "Checkout"));
        headings.put("checkout-shipping", new Heading(NO_KEYS, "Checkout Shipping"));
        headings.put("child-account-application", new Heading(NO_KEYS, "Child Account Application"));
        headings.put("flight-search", new Heading(NO_KEYS, "Flight Search"));
        headings.put("get-offer", new Heading(NO_KEYS, "Get Offer"));
        headings.put("name-change-request", new Heading(NO_KEYS, "Name Change Request"));
        headings.put("plan-application", new Heading(NO_KEYS, "Plan Application"));
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

    FormBlockBuilder(final FormSchemaService formSchemaService) {
        this.formSchemaService = formSchemaService;
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
                                final String id, final int order) {
        final Heading config = FORM_HEADINGS.get(formName);
        if (config == null) {
            return null;
        }
        final Map<String, Object> meta = new LinkedHashMap<>();
        meta.put(SchemaConstants.KEY_ID, id);
        meta.put(SchemaConstants.KEY_ORDER, order);
        meta.put(SchemaConstants.KEY_COMPONENT, "title");

        final Map<String, Object> content = new LinkedHashMap<>();
        content.put("size", "medium");
        content.put("text", first(authored, config.keys, config.defaultText));
        content.put("weight", "bold");
        content.put("textAlignment", "left");

        final Map<String, Object> title = new LinkedHashMap<>();
        title.put(SchemaConstants.KEY_META, meta);
        title.put(SchemaConstants.KEY_CONTENT, content);
        return title;
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

        final Map<String, Object> meta = new LinkedHashMap<>();
        meta.put(SchemaConstants.KEY_ID, id);
        meta.put(SchemaConstants.KEY_ORDER, order);
        meta.put(SchemaConstants.KEY_COMPONENT, COMPONENT_FORM);
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
            final Map<String, Object> built = FormFieldFactory.fromConfig(field, order);
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

}

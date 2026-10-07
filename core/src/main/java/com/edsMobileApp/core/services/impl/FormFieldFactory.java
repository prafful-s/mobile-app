package com.edsMobileApp.core.services.impl;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ValueMap;

import com.edsMobileApp.core.schema.SchemaConstants;

/**
 * Builds one field of a form, in the shape the client renders.
 *
 * <p>The bundled configuration carries only what a person has to decide - an id, a label, where the
 * value lives in the profile, and what kind of thing it is:</p>
 *
 * <pre>
 * { "id": "email", "name": "personalEmail.address", "label": "Email address",
 *   "value": "", "type": "text", "placeholder": "", "defaultValue": "" }
 * </pre>
 *
 * <p>Everything else the client needs - which widget renders it, whether it takes multiple lines,
 * which on-screen keyboard to show - follows from the type, so it is generated here rather than
 * repeated in eighteen files. That is the point of the split: the JSON holds decisions, this class
 * holds consequences. A field type gaining a new content key is one edit here, not eighteen.</p>
 *
 * <p>Fields also arrive already authored, from AEM Forms core components, where the discriminator is
 * {@code fieldType} rather than {@code type}. Both routes converge on the same output, so a form
 * built in the Forms editor and one described by configuration are indistinguishable downstream.</p>
 */
final class FormFieldFactory {

    /* ---- target widget names ---- */
    static final String TEXT_FIELD = "textField";
    static final String DROPDOWN = "dropdown";
    static final String RADIO_GROUP = "radioGroup";
    static final String CHECKBOX_GROUP = "checkboxGroup";
    static final String DATE_PICKER = "datePicker";

    /* ---- configuration field types ---- */
    private static final String TYPE_TEXT = "text";
    private static final String TYPE_PASSWORD = "password";
    private static final String TYPE_SELECT = "select";
    private static final String TYPE_RADIO = "radio";
    private static final String TYPE_CHECKBOX = "checkbox";
    private static final String TYPE_DATE = "date";
    private static final String TYPE_FILE = "file";
    /** A secondary navigation link declared beside the form's own fields - not a field itself, and
     *  not rendered here at all: {@link FormBlockBuilder#secondaryLink} reads it directly off the
     *  schema and emits it as a standalone component alongside the form. */
    private static final String TYPE_LINK = "link";
    /** A static section divider inside the field list - "Communication preferences", "LET US KNOW
     *  YOU BETTER" - not a value the user supplies. Reuses the {@code title} component's shape
     *  ({@code size}/{@code text}/{@code weight}/{@code textAlignment}), the only heading-shaped
     *  component this project already emits (the form's own pre-form heading, see
     *  {@link FormBlockBuilder#heading}). */
    private static final String TYPE_HEADING = "heading";
    private static final String COMPONENT_TITLE = "title";
    /** A day/time grid the user picks an appointment slot from - test-drive and schedule-appointment
     *  both carry one. Unlike every other field it has its own content contract (timeOptions,
     *  numberOfDays, numberOfTimeColumns) and a styles block, so it is built directly rather than
     *  through the typed/choice widget path. */
    private static final String TYPE_TIME_SLOT = "timeslot";
    private static final String COMPONENT_TIME_SLOT_PICKER = "timeSlotPicker";

    /**
     * The two authored node kinds that are part of a form but are not fields: a panel groups fields
     * and a button submits them. Every other fieldType is matched after normalisation, in
     * {@code componentForFieldType} - the spellings vary too much to be worth naming individually.
     */
    private static final String FD_PANEL = "panel";
    private static final String FD_BUTTON = "button";

    /** Rendering mode the target uses for inputs. */
    private static final String MODE_OUTLINED = "outlined";

    /** Adornment slots, off unless a field asks for one. */
    private static final String ELEMENT_OFF = "off";
    private static final String ENTER_KEY_HINT = "enter";

    private FormFieldFactory() {
        // static helpers only
    }

    /**
     * Field visibility gated by an authored property on the enclosing block.
     *
     * <p>Three shapes at the field level, checked in this order:</p>
     * <pre>
     *   "conditions": [ {gate}, {gate}, ... ]      // every gate must pass (AND)
     *   "variants": ["luma", "wknd-fly"]           // shorthand for one gate on the "variant" prop
     *   "condition": { gate }                      // single gate
     * </pre>
     *
     * <p>A gate itself declares one of:</p>
     * <pre>
     *   { "field": "showcreditcard", "default": false }              // truthy check
     *   { "field": "variant", "equals": "luma" }                     // string equality
     *   { "field": "variant", "not-equals": "wizard" }               // string inequality
     *   { "field": "variant", "equalsAny": ["luma","wknd-fly"] }     // one of several
     *   { "field": "form-title", "blank": true }                    // only while not authored
     *   { "field": "showconsentcheckbox", "default": true,
     *     "fallback": { "field": "variant", "not-equals": "no-checkbox" } }
     * </pre>
     *
     * <p>{@code fallback} is a second gate, evaluated instead of {@code default} when the primary
     * {@code field} is not authored at all - the reference form falls back to a variant check rather
     * than a fixed boolean when the more specific flag was never set.</p>
     *
     * <p>The reference client uses two shapes for conditional rendering: a boolean {@code show*}
     * property (which becomes the truthy check), and a discriminator like {@code variant} that
     * decides whether a field even exists in the JS-built form (which becomes the string check).
     * Both collapse to the same drop-when-false decision here. String comparisons trim and
     * lower-case both sides to match {@code normalizeVariant} in reference code.</p>
     *
     * @param field    a schema entry
     * @param authored the block's own authored properties
     * @return true when the field should be emitted; true also when the entry has none of the
     *         three shapes above, so annotating a schema is opt-in per field
     */
    static boolean isVisible(final Map<String, Object> field, final Map<String, Object> authored) {
        final Object conditions = field.get("conditions");
        if (conditions instanceof List) {
            for (final Object gate : (List<?>) conditions) {
                if (gate instanceof Map && !evaluateGate(asStringMap(gate), authored)) {
                    return false;
                }
            }
            return true;
        }

        final Object variants = field.get("variants");
        if (variants instanceof List) {
            final Map<String, Object> gate = new java.util.LinkedHashMap<>();
            gate.put("field", "variant");
            gate.put("equalsAny", variants);
            return evaluateGate(gate, authored);
        }

        final Object condition = field.get("condition");
        if (!(condition instanceof Map)) {
            return true;
        }
        return evaluateGate(asStringMap(condition), authored);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asStringMap(final Object value) {
        return (Map<String, Object>) value;
    }

    /** One gate of a {@code condition}/{@code conditions} entry - see {@link #isVisible}. */
    private static boolean evaluateGate(final Map<String, Object> gate, final Map<String, Object> authored) {
        final String prop = str(gate.get("field"));
        if (StringUtils.isBlank(prop)) {
            return true; // malformed gate - fail open
        }
        final Object authoredValue = authored == null ? null : authored.get(prop);
        final String authoredStr = normaliseCompare(authoredValue);

        // blank: true shows an entry only while the property is not authored - a schema default that
        // gives way to the author's own value (as a form's compiled-in heading default does).
        // blank: false is the opposite.
        final Object blank = gate.get("blank");
        if (blank != null) {
            return (authoredStr == null) == Boolean.parseBoolean(String.valueOf(blank));
        }

        // String-equality gates. equals: visible when authored value matches (hidden if missing,
        // since reference JS only includes the field when the variant equals a specific value).
        final Object equals = gate.get("equals");
        if (equals != null) {
            return authoredStr != null && authoredStr.equals(normaliseCompare(equals));
        }
        final Object notEquals = gate.get("not-equals");
        if (notEquals != null) {
            // Default visible - reference emits the field for every variant except the named one.
            return authoredStr == null || !authoredStr.equals(normaliseCompare(notEquals));
        }
        final Object equalsAny = gate.get("equalsAny");
        if (equalsAny instanceof List) {
            if (authoredStr == null) {
                return false;
            }
            for (final Object candidate : (List<?>) equalsAny) {
                if (authoredStr.equals(normaliseCompare(candidate))) {
                    return true;
                }
            }
            return false;
        }

        // Truthy check (the original visibleIf semantics) - falling back to a nested gate, rather
        // than a fixed default, when the primary field itself was never authored.
        if (authoredValue == null || (authoredValue instanceof String
                && StringUtils.isBlank((String) authoredValue))) {
            final Object fallback = gate.get("fallback");
            if (fallback instanceof Map) {
                return evaluateGate(asStringMap(fallback), authored);
            }
            final Object def = gate.get("default");
            return def instanceof Boolean ? (Boolean) def
                    : Boolean.parseBoolean(String.valueOf(def));
        }
        if (authoredValue instanceof Boolean) {
            return (Boolean) authoredValue;
        }
        return Boolean.parseBoolean(String.valueOf(authoredValue).trim());
    }

    /** Trim + lower-case, or null for blank/null. Mirrors reference's {@code normalizeVariant}. */
    private static String normaliseCompare(final Object value) {
        if (value == null) {
            return null;
        }
        final String trimmed = String.valueOf(value).trim();
        return trimmed.isEmpty() ? null : trimmed.toLowerCase(java.util.Locale.ROOT);
    }

    /**
     * Builds a field from one entry of a bundled schema.
     *
     * @param field the configured field
     * @param order its position in the form, which the client renders in
     */
    static Map<String, Object> fromConfig(final Map<String, Object> field, final int order) {
        final String type = StringUtils.lowerCase(str(field.get("type")), java.util.Locale.ROOT);

        if (TYPE_HEADING.equals(type)) {
            final String id = StringUtils.defaultIfBlank(str(field.get("id")), "heading-" + order);
            return component(id, order, COMPONENT_TITLE, headingContent(str(field.get("label")), field));
        }
        if (TYPE_LINK.equals(type)) {
            return null;
        }
        if (TYPE_TIME_SLOT.equals(type)) {
            return timeSlotPicker(field, order);
        }

        final String component = componentFor(type);
        // File upload is not rendered as a client field, so it produces no mapping.
        if (component == null) {
            return null;
        }

        // Not every configured field carries an id - twenty of the checkout fields do not. An empty
        // id would leave the client unable to address the field at all, so the profile path stands
        // in: it is unique within a form and already describes what the field holds.
        final String id = StringUtils.defaultIfBlank(str(field.get("id")),
                StringUtils.defaultIfBlank(str(field.get("name")), "field-" + order));

        // 'name' in the configuration is where the value lives in the client's profile model, which
        // is what the target calls 'path'. An explicit 'path' wins if a schema ever sets one.
        final String path = StringUtils.defaultString(
                StringUtils.defaultIfBlank(str(field.get("path")), str(field.get("name"))));
        final String label = StringUtils.defaultString(str(field.get("label")));

        // Only a field that knows where its value lives can be pre-filled - and a password never is,
        // whatever the configuration says.
        final boolean prefill = StringUtils.isNotBlank(path) && !TYPE_PASSWORD.equals(type);

        final Map<String, Object> content;
        if (isToggleGroup(component)) {
            content = toggleContent(component, path, label, options(field), prefill,
                    str(field.get("variant")));
        } else if (DROPDOWN.equals(component)) {
            content = dropdownContent(path, label, options(field), prefill);
        } else {
            content = typedContent(component, path, label, prefill,
                    inputMode(type, id, component), TYPE_PASSWORD.equals(type),
                    str(field.get("value")), str(field.get("placeholder")),
                    str(field.get("defaultValue")));
        }
        return component(id, order, component, content);
    }

    /**
     * Builds a field from an authored AEM Forms component.
     *
     * <p>Used for forms with no bundled configuration - the block is read as it was authored, so a
     * form built in the Forms editor still reaches the client without anyone writing a schema for
     * it.</p>
     *
     * @return the field, or {@code null} when this resource is not one - a panel, a button or static
     *         text is part of the form but not a value the user supplies
     */
    static Map<String, Object> fromResource(final Resource resource, final int order) {
        final ValueMap props = resource.getValueMap();
        final String fieldType = props.get("fieldType", String.class);
        final String component = componentForFieldType(fieldType);
        if (component == null) {
            return null;
        }
        final String name = props.get("name", resource.getName());

        // The label is jcr:title, not title: AEM Forms stores it under the standard JCR property, so
        // reading 'title' returns nothing and every field arrives unlabelled.
        final String label = StringUtils.defaultString(
                StringUtils.defaultIfBlank(props.get("jcr:title", String.class),
                        props.get("title", String.class)));

        // Same rule as the configured route: 'name' is the profile location, and an explicit 'path'
        // property wins where an author set one.
        final String path = StringUtils.defaultString(
                StringUtils.defaultIfBlank(props.get("path", String.class), name));
        final boolean prefill = StringUtils.isNotBlank(path) && !isPasswordType(fieldType);

        final Map<String, Object> content;
        if (isToggleGroup(component)) {
            content = toggleContent(component, path, label, optionsFromNode(props, path, label),
                    prefill, props.get("variant", String.class));
        } else if (DROPDOWN.equals(component)) {
            content = dropdownContent(path, label, optionsFromEnum(props), prefill);
        } else {
            content = typedContent(component, path, label, prefill,
                    TEXT_FIELD.equals(component) ? authoredInputMode(fieldType, name) : "none",
                    isPasswordType(fieldType),
                    props.get("value", String.class), props.get("placeholder", String.class),
                    // 'default' is what the Forms editor calls a starting value.
                    props.get("default", String.class));
        }

        if (props.get("required", false)) {
            content.put("required", true);
        }
        return component(name, order, component, content);
    }

    /** @return true when this authored resource is a container to walk into rather than a field */
    static boolean isPanel(final String fieldType) {
        return FD_PANEL.equals(normalise(fieldType));
    }

    /** @return true when this authored resource is the form's submit button */
    static boolean isButton(final String fieldType) {
        return FD_BUTTON.equals(normalise(fieldType));
    }

    /**
     * A dropdown: what to render, where it goes, and what can be chosen. Nothing about typing,
     * because nothing is typed into it.
     */
    private static Map<String, Object> dropdownContent(final String path, final String label,
                                                       final List<Object> options,
                                                       final boolean prefill) {
        final Map<String, Object> content = new LinkedHashMap<>();
        content.put("mode", MODE_OUTLINED);
        content.put("path", path);
        content.put("label", label);
        content.put("options", options);
        content.put("prefill", prefill);
        return content;
    }

    /**
     * A checkbox or radio group. The two diverge in shape, and that divergence is real, not an
     * inconsistency to smooth over: a checkbox group's options are separately addressable - three
     * checkboxes write three values - so each needs its own path and what it reads when ticked. A
     * radio group is mutually exclusive - exactly one path, exactly one selected value - so the
     * group carries the path once and each option is just the label/value pair it offers.
     */
    private static Map<String, Object> toggleContent(final String component, final String path,
                                                     final String label,
                                                     final List<Object> options,
                                                     final boolean prefill,
                                                     final String configuredVariant) {
        return RADIO_GROUP.equals(component)
                ? radioGroupContent(path, label, options, prefill)
                : checkboxGroupContent(path, label, options, prefill, configuredVariant);
    }

    /**
     * {@code { ["label"], "path", "options": [{"label","value"}, ...], "prefill" }} - one path for
     * the whole group, plain label/value options, no {@code variant} key (the reference radioGroup
     * carries none).
     */
    private static Map<String, Object> radioGroupContent(final String path, final String label,
                                                          final List<Object> options,
                                                          final boolean prefill) {
        final List<Object> rewritten = new ArrayList<>(options.size());
        for (final Object option : options) {
            if (!(option instanceof Map)) {
                continue;
            }
            @SuppressWarnings("unchecked")
            final Map<String, Object> source = (Map<String, Object>) option;
            final Map<String, Object> target = new LinkedHashMap<>();
            target.put("label", StringUtils.defaultString(
                    StringUtils.defaultIfBlank(str(source.get("label")), label)));
            target.put("value", StringUtils.defaultString(
                    StringUtils.defaultIfBlank(str(source.get("checkedValue")),
                            str(source.get("value")))));
            rewritten.add(target);
        }

        final Map<String, Object> content = new LinkedHashMap<>();
        if (StringUtils.isNotBlank(label) && !labelsAnOption(rewritten, label)) {
            content.put("label", label);
        }
        content.put("path", StringUtils.defaultString(path));
        content.put("options", rewritten);
        content.put("prefill", prefill);
        return content;
    }

    /**
     * {@code { ["label"], "options": [{"path","label","checkedValue","uncheckedValue"}, ...],
     * "prefill", "variant" }} - the path and label live on each option rather than on the field,
     * because a group's options are separately addressable.
     */
    private static Map<String, Object> checkboxGroupContent(final String path, final String label,
                                                             final List<Object> options,
                                                             final boolean prefill,
                                                             final String configuredVariant) {
        final List<Object> rewritten = new ArrayList<>(options.size());
        for (final Object option : options) {
            if (!(option instanceof Map)) {
                continue;
            }
            @SuppressWarnings("unchecked")
            final Map<String, Object> source = (Map<String, Object>) option;

            final Map<String, Object> target = new LinkedHashMap<>();
            // An option may name its own path; otherwise every option in the group writes to the
            // field's, which is the single-checkbox case.
            target.put("path", StringUtils.defaultString(
                    StringUtils.defaultIfBlank(str(source.get("path")), path)));
            target.put("label", StringUtils.defaultString(
                    StringUtils.defaultIfBlank(str(source.get("label")), label)));
            // Consent/opt-in checkboxes feed AEP eVars downstream, which expect the reference
            // client's "y"/"n" domain. The bundled schemas and authored nodes carry the checked
            // state as the boolean placeholder "true" (unchecked usually left blank), which would
            // land in AEP as the literal "true"/"" rather than the y/n the eVar is mapped to. So the
            // toggle domain is normalised here - the single place both the authored and configured
            // routes converge on: "true"/blank -> "y", "false"/blank -> "n". An explicit non-boolean
            // value a schema sets deliberately (e.g. "accepted") is preserved.
            target.put("checkedValue", toggleValue(
                    StringUtils.defaultIfBlank(str(source.get("checkedValue")),
                            str(source.get("value"))), "y"));
            target.put("uncheckedValue", toggleValue(str(source.get("uncheckedValue")), "n"));
            rewritten.add(target);
        }

        final Map<String, Object> content = new LinkedHashMap<>();

        // A group normally says nothing about itself: one label cannot describe three separately
        // addressable options, so each option carries its own. An authored checkbox node is titled
        // "SMS", which is the option's own label repeated - dropping it avoids saying the same
        // thing twice.
        if (StringUtils.isNotBlank(label) && !labelsAnOption(rewritten, label)) {
            content.put("label", label);
        }
        content.put("options", rewritten);
        content.put("prefill", prefill);

        // The configuration decides where it says so - all 25 checkbox fields declare 'switch'.
        // Otherwise the variant is named after the control.
        content.put("variant", StringUtils.defaultIfBlank(configuredVariant, "checkbox"));
        return content;
    }

    /**
     * A static heading field inside a form's field list - see {@link #TYPE_HEADING}. Same shape and
     * defaults as {@code title.json}'s mapping rule (size medium, weight bold, left-aligned): a
     * section-divider heading, not a page title, so a smaller/lighter treatment would be a mismatch,
     * but there is no reference distinguishing the two, so the one heading shape this project already
     * has is reused rather than inventing a second.
     */
    private static Map<String, Object> headingContent(final String text, final Map<String, Object> field) {
        final Map<String, Object> content = new LinkedHashMap<>();
        // A heading entry may restyle itself - {@code size}, {@code weight}, {@code textAlignment} -
        // which is how a schema carries a plain description line as a heading: small, regular, centred.
        content.put("size", StringUtils.defaultIfBlank(str(field.get("size")), "medium"));
        content.put("text", StringUtils.defaultString(text));
        content.put("weight", StringUtils.defaultIfBlank(str(field.get("weight")), "bold"));
        content.put("textAlignment", StringUtils.defaultIfBlank(str(field.get("textAlignment")), "left"));
        return content;
    }

    /** Everything a user types into or picks a value for: text, password, date, file. */
    private static Map<String, Object> typedContent(final String component, final String path,
                                                    final String label, final boolean prefill,
                                                    final String inputMode, final boolean isPassword,
                                                    final String value, final String placeholder,
                                                    final String defaultValue) {
        final Map<String, Object> content = new LinkedHashMap<>();
        content.put("mode", MODE_OUTLINED);
        content.put("path", path);

        // A date picker is picked from a calendar, not typed into - none of the typed-input keys
        // below apply to it, and it carries no free-text value/placeholder/defaultValue either.
        if (DATE_PICKER.equals(component)) {
            content.put("type", "single");
            content.put("label", label);
            content.put("prefill", prefill);
            content.put("withDateFormatInLabel", false);
            return content;
        }

        content.put("label", label);
        content.put("prefill", prefill);
        inputContent(content, inputMode, isPassword);

        content.put("value", StringUtils.defaultString(value));
        content.put("placeholder", StringUtils.defaultString(placeholder));
        content.put("defaultValue", StringUtils.defaultString(defaultValue));
        return content;
    }

    private static boolean isToggleGroup(final String component) {
        return CHECKBOX_GROUP.equals(component) || RADIO_GROUP.equals(component);
    }

    /** @return true when the field's label is one of its options' labels rather than a question */
    private static boolean labelsAnOption(final List<Object> options, final String label) {
        for (final Object option : options) {
            if (option instanceof Map
                    && label.equals(((Map<?, ?>) option).get("label"))) {
                return true;
            }
        }
        return false;
    }

    /**
     * The input keys every field carries, whatever widget renders it.
     *
     * <p>Emitted uniformly rather than only on text inputs. Several of them are meaningless on a
     * dropdown - a select cannot be multiline or a password - but a client parsing a fixed shape
     * should not have to branch on the widget to know which keys exist. An absent key and a false
     * one are different things to reason about; this leaves only the second.</p>
     */
    private static void inputContent(final Map<String, Object> content, final String inputMode,
                                     final boolean isPassword) {
        content.put("inputMode", inputMode);
        content.put("multiline", false);
        content.put("isPassword", isPassword);
        content.put("autoCorrect", false);
        content.put("leftElement", ELEMENT_OFF);
        content.put("rightElement", ELEMENT_OFF);
        content.put("enterKeyHint", ENTER_KEY_HINT);
    }

    /**
     * Which on-screen keyboard to raise.
     *
     * <p>{@code none} for anything the user picks rather than types - a dropdown, a date, a file.
     * Otherwise it follows the type, with one exception: an email field is typed as text, and showing
     * someone a keyboard without an {@code @} key is a small cruelty.</p>
     */
    private static String inputMode(final String type, final String id, final String component) {
        if (!TEXT_FIELD.equals(component)) {
            return "none";
        }
        if (StringUtils.containsIgnoreCase(id, "email")) {
            return "email";
        }
        return TYPE_TEXT.equals(type) || TYPE_PASSWORD.equals(type) ? "text" : "none";
    }


    /** Options as configured. A choice field without them renders as an empty box. */
    private static List<Object> options(final Map<String, Object> field) {
        final Object configured = field.get("options");
        return configured instanceof List ? new ArrayList<>((List<?>) configured) : new ArrayList<>();
    }

    /**
     * The choices on an authored field, which AEM Forms stores two different ways.
     *
     * <p>A dropdown or radio group holds them as parallel arrays - {@code enum} for the values,
     * {@code enumNames} for the labels - which are zipped back together. A checkbox does not: it is
     * a single node standing for one choice, carrying its own {@code jcr:title},
     * {@code checkedValue} and {@code name}. Reading only the arrays left every authored checkbox
     * with no options at all, which renders as nothing.</p>
     */
    private static List<Object> optionsFromNode(final ValueMap props, final String path,
                                                final String label) {
        final List<Object> fromEnum = optionsFromEnum(props);
        if (!fromEnum.isEmpty()) {
            return fromEnum;
        }
        final String checkedValue = props.get("checkedValue", String.class);
        if (StringUtils.isBlank(checkedValue) && StringUtils.isBlank(label)) {
            return fromEnum;
        }

        // One node, one choice. The option carries the node's own checkedValue/uncheckedValue when
        // authored; when neither is set, both are left blank here so checkboxGroupContent supplies
        // the "y"/"n" default in one place for both the authored and configured routes.
        final Map<String, Object> option = new LinkedHashMap<>();
        option.put("path", StringUtils.defaultString(path));
        option.put("label", StringUtils.defaultString(label));
        option.put("checkedValue", StringUtils.defaultString(checkedValue));
        option.put("uncheckedValue",
                StringUtils.defaultString(props.get("uncheckedValue", String.class)));

        final List<Object> options = new ArrayList<>(1);
        options.add(option);
        return options;
    }

    /**
     * AEM Forms stores choices as two parallel arrays - {@code enum} holds the values and
     * {@code enumNames} the labels - which are zipped back together here.
     */
    private static List<Object> optionsFromEnum(final ValueMap props) {
        final String[] values = props.get("enum", new String[0]);
        final String[] labels = props.get("enumNames", new String[0]);

        final List<Object> options = new ArrayList<>(values.length);
        for (int i = 0; i < values.length; i++) {
            final Map<String, Object> option = new LinkedHashMap<>();
            // Falling back to the value keeps a mislabelled option selectable rather than blank.
            option.put("label", i < labels.length ? labels[i] : values[i]);
            option.put("value", values[i]);
            options.add(option);
        }
        return options;
    }

    private static String componentFor(final String type) {
        if (type == null) {
            return TEXT_FIELD;
        }
        switch (type) {
            case TYPE_SELECT:
                return DROPDOWN;
            case TYPE_RADIO:
                return RADIO_GROUP;
            case TYPE_CHECKBOX:
                return CHECKBOX_GROUP;
            case TYPE_DATE:
                return DATE_PICKER;
            case TYPE_FILE:
                // File upload is intentionally not rendered - no field mapping is produced.
                return null;
            case TYPE_TEXT:
            case TYPE_PASSWORD:
            default:
                // An unknown type still renders as something a user can type into, which beats
                // dropping the field and leaving a gap in the form.
                return TEXT_FIELD;
        }
    }

    /**
     * @return the widget for an authored field, or null when the resource is not a field
     */
    private static String componentForFieldType(final String fieldType) {
        // Normalised because the same field arrives spelled several ways depending on which editor
        // wrote it - 'text-input', 'textinput', 'checkbox-group', 'checkboxgroup'.
        final String type = normalise(fieldType);
        if (type == null) {
            return null;
        }
        switch (type) {
            case "textinput":
            case "email":
            case "emailinput":
            case "numberinput":
            case "number":
            case "telephone":
            case "password":
                return TEXT_FIELD;
            case "dateinput":
            case "datepicker":
                return DATE_PICKER;
            case "dropdown":
                return DROPDOWN;
            case "radiogroup":
            case "radiobutton":
                return RADIO_GROUP;
            case "checkboxgroup":
            case "checkbox":
                return CHECKBOX_GROUP;
            case "fileinput":
            case "fileupload":
                // File upload is intentionally not rendered - no field mapping is produced.
                return null;
            default:
                return null;
        }
    }

    /** Strips the punctuation that varies between editors, so one case covers every spelling. */
    private static String normalise(final String fieldType) {
        final String trimmed = StringUtils.trimToNull(fieldType);
        return trimmed == null
                ? null : trimmed.toLowerCase(java.util.Locale.ROOT).replace("-", "").replace("_", "");
    }

    private static boolean isPasswordType(final String fieldType) {
        return "password".equals(normalise(fieldType));
    }

    /** Same reasoning as the configured path: an email field needs a keyboard with an @ key. */
    private static String authoredInputMode(final String fieldType, final String name) {
        final String type = normalise(fieldType);
        if ("email".equals(type) || "emailinput".equals(type)
                || StringUtils.containsIgnoreCase(name, "email")) {
            return "email";
        }
        if ("numberinput".equals(type) || "number".equals(type) || "telephone".equals(type)) {
            return "numeric";
        }
        return "text";
    }


    /**
     * A time-slot picker field. Unlike a typed input it carries its own content contract - the day
     * grid ({@code numberOfDays}), the per-day slots ({@code timeOptions}) and the column count
     * ({@code numberOfTimeColumns}) - plus a {@code styles.gap}, so it is assembled directly here
     * rather than through {@link #component} / the typed-content path. Everything comes from the
     * field's own configuration; only sensible reference defaults fill an unset key.
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> timeSlotPicker(final Map<String, Object> field, final int order) {
        final String path = StringUtils.defaultString(
                StringUtils.defaultIfBlank(str(field.get("path")), str(field.get("name"))));
        final String id = StringUtils.defaultIfBlank(str(field.get("id")),
                StringUtils.defaultIfBlank(path, "field-" + order));

        final Map<String, Object> content = new LinkedHashMap<>();
        content.put("path", path);
        content.put("prefill", field.get("prefill") instanceof Boolean ? field.get("prefill") : Boolean.TRUE);
        content.put("timeOptions",
                field.get("timeOptions") instanceof List ? field.get("timeOptions") : new ArrayList<>());
        content.put("defaultValue", StringUtils.defaultString(str(field.get("defaultValue"))));
        content.put("numberOfDays", asInt(field.get("numberOfDays"), 3));
        content.put("numberOfTimeColumns", asInt(field.get("numberOfTimeColumns"), 3));

        final Map<String, Object> meta = new LinkedHashMap<>();
        meta.put(SchemaConstants.KEY_ID, id);
        meta.put(SchemaConstants.KEY_ORDER, order);
        meta.put(SchemaConstants.KEY_COMPONENT, COMPONENT_TIME_SLOT_PICKER);

        final Map<String, Object> styles = new LinkedHashMap<>();
        styles.put("gap", asInt(field.get("gap"), 16));

        final Map<String, Object> entry = new LinkedHashMap<>();
        entry.put(SchemaConstants.KEY_META, meta);
        entry.put(SchemaConstants.KEY_STYLES, styles);
        entry.put(SchemaConstants.KEY_CONTENT, content);
        return entry;
    }

    private static int asInt(final Object value, final int fallback) {
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        if (value instanceof String) {
            try {
                return Integer.parseInt(((String) value).trim());
            } catch (final NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    private static Map<String, Object> component(final String id, final int order,
                                                 final String component,
                                                 final Map<String, Object> content) {
        final Map<String, Object> meta = new LinkedHashMap<>();
        meta.put(SchemaConstants.KEY_ID, StringUtils.defaultString(id));
        meta.put(SchemaConstants.KEY_ORDER, order);
        meta.put(SchemaConstants.KEY_COMPONENT, component);

        final Map<String, Object> field = new LinkedHashMap<>();
        field.put(SchemaConstants.KEY_META, meta);
        field.put(SchemaConstants.KEY_CONTENT, content);
        return field;
    }


    private static String str(final Object value) {
        return value instanceof String ? (String) value : null;
    }

    /**
     * Normalises a checkbox toggle value to the reference client's {@code "y"}/{@code "n"} domain,
     * which is what the AEP eVars these fields feed are mapped to. A blank value (the common case -
     * the schemas and authored nodes rarely set an unchecked value) becomes {@code fallback}; the
     * boolean placeholders {@code "true"}/{@code "false"} the schemas use to mean "checked"/
     * "unchecked" become {@code "y"}/{@code "n"}. Any other value a schema sets deliberately is
     * returned unchanged, so a checkbox that genuinely needs a domain-specific value keeps it.
     */
    private static String toggleValue(final String raw, final String fallback) {
        if (StringUtils.isBlank(raw)) {
            return fallback;
        }
        final String trimmed = raw.trim();
        if ("true".equalsIgnoreCase(trimmed)) {
            return "y";
        }
        if ("false".equalsIgnoreCase(trimmed)) {
            return "n";
        }
        return raw;
    }
}

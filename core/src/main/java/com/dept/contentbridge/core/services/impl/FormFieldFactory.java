package com.dept.contentbridge.core.services.impl;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ValueMap;

import com.dept.contentbridge.core.schema.SchemaConstants;

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
    static final String FILE_UPLOAD = "fileUpload";

    /* ---- configuration field types ---- */
    private static final String TYPE_TEXT = "text";
    private static final String TYPE_PASSWORD = "password";
    private static final String TYPE_SELECT = "select";
    private static final String TYPE_RADIO = "radio";
    private static final String TYPE_CHECKBOX = "checkbox";
    private static final String TYPE_DATE = "date";
    private static final String TYPE_FILE = "file";

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
     * Builds a field from one entry of a bundled schema.
     *
     * @param field the configured field
     * @param order its position in the form, which the client renders in
     */
    static Map<String, Object> fromConfig(final Map<String, Object> field, final int order) {
        final String type = StringUtils.lowerCase(str(field.get("type")), java.util.Locale.ROOT);
        final String component = componentFor(type);

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
     * A checkbox or radio group, whose shape differs from every other field: the path and the label
     * live on each option rather than on the field.
     *
     * <p>That is not an inconsistency to smooth over. A group's options are separately addressable -
     * three checkboxes write three values - so each needs to say where its own value goes and what
     * it reads when ticked. A single label on the field could not describe three.</p>
     */
    private static Map<String, Object> toggleContent(final String component, final String path,
                                                     final String label,
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
            target.put("checkedValue", StringUtils.defaultString(
                    StringUtils.defaultIfBlank(str(source.get("checkedValue")),
                            str(source.get("value")))));
            target.put("uncheckedValue",
                    StringUtils.defaultString(str(source.get("uncheckedValue"))));
            rewritten.add(target);
        }

        final Map<String, Object> content = new LinkedHashMap<>();

        // A group normally says nothing about itself: one label cannot describe three separately
        // addressable options, so each option carries its own. Two cases differ, and the difference
        // is whether the field's label is a question or a caption. A radio group asks "How often do
        // you get care?" and its options answer it - that is worth keeping. An authored checkbox
        // node is titled "SMS", which is the option's own label repeated - dropping it avoids saying
        // the same thing twice.
        if (StringUtils.isNotBlank(label) && !labelsAnOption(rewritten, label)) {
            content.put("label", label);
        }
        content.put("options", rewritten);
        content.put("prefill", prefill);

        // The configuration decides where it says so - all 25 checkbox fields declare 'switch'.
        // Otherwise the variant is named after the control.
        content.put("variant", StringUtils.defaultIfBlank(configuredVariant,
                CHECKBOX_GROUP.equals(component) ? "checkbox" : "radio"));
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

        // One node, one choice. The option is built here rather than in toggleContent so it carries
        // the node's own checkedValue instead of a value invented from the field.
        final Map<String, Object> option = new LinkedHashMap<>();
        option.put("path", StringUtils.defaultString(path));
        option.put("label", StringUtils.defaultString(label));
        option.put("checkedValue", StringUtils.defaultIfBlank(checkedValue, "true"));
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
                return FILE_UPLOAD;
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
                return FILE_UPLOAD;
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
}

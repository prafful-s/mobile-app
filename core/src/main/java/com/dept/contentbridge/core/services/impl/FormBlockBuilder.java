package com.dept.contentbridge.core.services.impl;

import java.util.ArrayList;
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

    private final FormSchemaService formSchemaService;

    FormBlockBuilder(final FormSchemaService formSchemaService) {
        this.formSchemaService = formSchemaService;
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
            fields.add(FormFieldFactory.fromConfig(field, order++));
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

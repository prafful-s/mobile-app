package com.dept.contentbridge.core.services;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.osgi.annotation.versioning.ProviderType;

/**
 * Supplies the field definitions for the forms this project knows about.
 *
 * <p>Schemas are shipped as JSON inside the bundle rather than authored in the repository: they are
 * a contract between the API and its clients, so they should version with the code and be identical
 * on every environment. Nothing in AEM authoring can drift them out of step.</p>
 *
 * <p>A schema is an ordered list of fields, not a keyed object. Order is part of the contract - a
 * form is rendered top to bottom - and a list says so, where a map only happens to preserve it.</p>
 *
 * <pre>
 * [
 *   { "id": "firstName", "name": "person.name.firstName", "label": "First name",
 *     "value": "", "type": "text", "placeholder": "", "defaultValue": "" },
 *   { "id": "state", "name": "homeAddress.state", "label": "State", "value": "",
 *     "type": "select", "options": [ { "label": "AL", "value": "AL" } ] }
 * ]
 * </pre>
 *
 * <p>{@code name} is the path the client reads and writes in its own profile model, which is why it
 * is dotted and why it differs from {@code id} - the identifier within the form.</p>
 */
@ProviderType
public interface FormSchemaService {

    /**
     * @param formName the form key, e.g. {@code sign-in} or {@code loan-application-form}
     * @return the fields in the order they are rendered, or an empty list when the form is unknown
     */
    List<Map<String, Object>> getSchema(String formName);

    /**
     * @return every known form, keyed by form name
     */
    Map<String, List<Map<String, Object>>> getAllSchemas();

    /**
     * @return the names of the forms that have a schema
     */
    Set<String> getFormNames();
}

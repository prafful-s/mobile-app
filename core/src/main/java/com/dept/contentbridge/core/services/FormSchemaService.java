package com.dept.contentbridge.core.services;

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
 * <p>Each schema maps a field key to its definition:</p>
 * <pre>
 * "email": { "id": "email", "label": "Email address", "name": "email", "value": "", "type": "text" }
 * </pre>
 */
@ProviderType
public interface FormSchemaService {

    /**
     * @param formName the form key, e.g. {@code sign-in} or {@code loan-application-form}
     * @return the field definitions in authoring order, or an empty map when the form is unknown
     */
    Map<String, Object> getSchema(String formName);

    /**
     * @return every known form, keyed by form name
     */
    Map<String, Object> getAllSchemas();

    /**
     * @return the names of the forms that have a schema
     */
    Set<String> getFormNames();
}

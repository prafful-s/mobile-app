package com.edsMobileApp.core.services.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.sling.api.resource.Resource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import com.edsMobileApp.core.schema.SchemaConstants;
import com.edsMobileApp.core.services.FormSchemaService;

import io.wcm.testing.mock.aem.junit5.AemContext;
import io.wcm.testing.mock.aem.junit5.AemContextExtension;

/**
 * A form block whose fields are authored rather than taken from a bundled schema has no schema name
 * at all, so {@code formName} reaches {@link FormBlockBuilder#build} as {@code null}. That is the
 * documented contract ("the schema to use, which may name none"), and every site carrying such a
 * form - secur-financial, we-healthcare, citi-signal - had its entire export fail on it, because a
 * {@code Map.of} lookup keyed on that null threw rather than missing.
 */
@ExtendWith(AemContextExtension.class)
class FormBlockBuilderTest {

    private final AemContext context = new AemContext();

    /** The schema service is never consulted for a blank form name, so an empty one suffices. */
    private static final FormSchemaService NO_SCHEMAS = new FormSchemaService() {
        @Override
        public List<Map<String, Object>> getSchema(final String formName) {
            return Collections.emptyList();
        }

        @Override
        public Map<String, List<Map<String, Object>>> getAllSchemas() {
            return Collections.emptyMap();
        }

        @Override
        public java.util.Set<String> getFormNames() {
            return Collections.emptySet();
        }
    };

    @SuppressWarnings("unchecked")
    private static Map<String, Object> meta(final Map<String, Object> component) {
        return (Map<String, Object>) component.get(SchemaConstants.KEY_META);
    }

    @Test
    void aFormWithNoSchemaNameStillBuilds() {
        final Resource resource = context.create().resource("/content/site/page/jcr:content/form");
        final Map<String, Object> built =
                new FormBlockBuilder(NO_SCHEMAS).build(resource, null, "abc123", 0, new HashMap<>());

        assertNotNull(built);
        assertEquals("abc123", meta(built).get(SchemaConstants.KEY_ID));
        // no form-specific redirect keys apply, so the link stays blank for a per-site default to fill
        assertEquals("", meta(built).get("link"));
    }

    @Test
    void aNamedFormStillReadsItsOwnRedirectKey() {
        final Resource resource = context.create().resource("/content/site/page/jcr:content/form2");
        final Map<String, Object> authored = new HashMap<>();
        authored.put("redirectUrl", "/content/site/page/home");

        final Map<String, Object> built = new FormBlockBuilder(NO_SCHEMAS)
                .build(resource, "create-account", "def456", 1, authored);

        assertEquals("/content/site/page/home", meta(built).get("link"));
    }

    @Test
    void anUnknownFormNameFallsBackToNoRedirectKeys() {
        final Resource resource = context.create().resource("/content/site/page/jcr:content/form3");
        final Map<String, Object> authored = new HashMap<>();
        authored.put("redirectUrl", "/content/site/page/home");

        final Map<String, Object> built = new FormBlockBuilder(NO_SCHEMAS)
                .build(resource, "not-a-known-form", "ghi789", 2, authored);

        // redirectUrl is only read for the forms that declare it, so this one is ignored
        assertEquals("", meta(built).get("link"));
    }
}

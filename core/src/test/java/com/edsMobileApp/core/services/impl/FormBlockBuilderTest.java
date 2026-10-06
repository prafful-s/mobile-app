package com.edsMobileApp.core.services.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.models.factory.ModelFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;

import com.day.cq.wcm.api.Page;
import com.edsMobileApp.core.models.impl.GenericComponent;
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
        @SuppressWarnings("unchecked")
        void coffeeQuizBuildsAsAFormWithItsConfiguredComponents() {
        final FormSchemaServiceImpl schemas = new FormSchemaServiceImpl();
        schemas.activate();
        final Resource resource = context.create().resource("/content/site/page/jcr:content/quiz");
        final Map<String, Object> built = new FormBlockBuilder(schemas).build(resource,
            "coffee-quiz-frescopa", "quiz123", 0,
            Map.of("mobilebuttoneventtype", "registration"));

        assertEquals("form", meta(built).get(SchemaConstants.KEY_COMPONENT));
        assertEquals("registration", meta(built).get("eventType"));
        final List<Map<String, Object>> configured = schemas.getSchema("coffee-quiz-frescopa");
        final List<Map<String, Object>> fields =
            (List<Map<String, Object>>) built.get(SchemaConstants.KEY_CONTENT);
        assertEquals(8, fields.size());
        for (int index = 0; index < configured.size(); index++) {
            assertEquals(configured.get(index).get(SchemaConstants.KEY_COMPONENT),
                meta(fields.get(index)).get(SchemaConstants.KEY_COMPONENT));
            assertEquals(index + 1, meta(fields.get(index)).get(SchemaConstants.KEY_ORDER));
                assertEquals(configured.get(index).get(SchemaConstants.KEY_COMPONENT) + "-" + (index + 1),
                    meta(fields.get(index)).get(SchemaConstants.KEY_ID));
            assertEquals(configured.get(index).get(SchemaConstants.KEY_CONTENT),
                fields.get(index).get(SchemaConstants.KEY_CONTENT));
        }
        final Map<String, Object> withoutEvent = new FormBlockBuilder(schemas).build(resource,
            "coffee-quiz-frescopa", "quiz123", 0, Collections.emptyMap());
        assertEquals("", meta(withoutEvent).get("eventType"));
        }

        @Test
            @SuppressWarnings("unchecked")
            void coffeeQuizExportsAsOneFormInsteadOfTopLevelSteps() {
            final FormSchemaServiceImpl schemas = new FormSchemaServiceImpl();
            schemas.activate();
            final UrlServiceImpl urlService = new UrlServiceImpl();
            final ModelFactory modelFactory = Mockito.mock(ModelFactory.class);
            Mockito.when(modelFactory.getModelFromResource(Mockito.any()))
                .thenAnswer(invocation -> new GenericComponent(invocation.getArgument(0), urlService));
            final ComponentTreeWalker walker = new ComponentTreeWalker(modelFactory, urlService, schemas,
                new StyleResolver(new String[0]), ComponentMappings.load());
            final Page page = context.create().page("/content/frescopa/en/quiz");
            context.create().resource(page.getPath() + "/jcr:content/root");
            context.create().resource(page.getPath() + "/jcr:content/root/quiz",
                "sling:resourceType", "eds/components/coffee-quiz-frescopa",
                "mobilebuttoneventtype", "registration");

            final List<Map<String, Object>> components = walker.componentsOf(page, "", true);
            assertEquals(1, components.size());
            assertEquals("form", meta(components.get(0)).get(SchemaConstants.KEY_COMPONENT));
            assertEquals("registration", meta(components.get(0)).get("eventType"));
            assertEquals(8, ((List<Map<String, Object>>) components.get(0)
                .get(SchemaConstants.KEY_CONTENT)).size());
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
        authored.put("redirectUrl", "/content/frescopa/language-masters/en/sign-in");

        final Map<String, Object> built = new FormBlockBuilder(NO_SCHEMAS)
                .build(resource, "create-account", "def456", 1, authored);

        assertEquals("sign-in", meta(built).get("link"));
        }

        @Test
        void aJoinUsRedirectPreservesItsQueryAndFragment() {
        final Resource resource = context.create().resource("/content/site/page/jcr:content/join-us");
        final Map<String, Object> authored = new HashMap<>();
        authored.put("submit-redirect-url", "/content/site/en/sign-in.html/?source=registration#login");

        final Map<String, Object> built = new FormBlockBuilder(NO_SCHEMAS)
            .build(resource, "join-us", "join123", 0, authored);

        assertEquals("sign-in?source=registration#login", meta(built).get("link"));
        }

        @Test
        void aRedirectOutsideAemContentIsUnchanged() {
        final Resource resource = context.create().resource("/content/site/page/jcr:content/redirects");
        for (final String redirect : new String[]{"sign-in", "", "#login", "mailto:help@example.com",
            "https://example.com/sign-in"}) {
            final Map<String, Object> authored = new HashMap<>();
            authored.put("redirectUrl", redirect);

            final Map<String, Object> built = new FormBlockBuilder(NO_SCHEMAS)
                .build(resource, "create-account", "redirect123", 0, authored);

            assertEquals(redirect, meta(built).get("link"));
        }
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

    @Test
    void everyRegisteredFormReadsItsMobileRedirect() {
        final FormSchemaServiceImpl schemas = new FormSchemaServiceImpl();
        schemas.activate();
        final Resource resource = context.create().resource("/content/site/page/jcr:content/mobile-redirect");
        for (final String formName : schemas.getFormNames()) {
            final Map<String, Object> authored = new HashMap<>();
            authored.put("mobileRedirectUrl", "/content/site/en/confirmation.html?source=mobile#complete");
            authored.put("redirectUrl", "/content/site/en/web-confirmation");
            authored.put("submit-redirect-url", "/content/site/en/web-confirmation");

            final Map<String, Object> built = new FormBlockBuilder(NO_SCHEMAS)
                    .build(resource, formName, "mobile123", 0, authored);

            assertEquals("confirmation?source=mobile#complete", meta(built).get("link"), formName);
        }
    }

    @Test
    void anAuthoredFormWithoutASchemaReadsItsMobileRedirect() {
        final Resource resource = context.create().resource("/content/site/page/jcr:content/adaptive-redirect");
        final Map<String, Object> authored = new HashMap<>();
        authored.put("mobileRedirectUrl", "/content/site/en/confirmation");

        final Map<String, Object> built = new FormBlockBuilder(NO_SCHEMAS)
                .build(resource, null, "adaptive123", 0, authored);

        assertEquals("confirmation", meta(built).get("link"));
    }

    @Test
    void aBlankMobileRedirectKeepsTheLegacyFallback() {
        final Resource resource = context.create().resource("/content/site/page/jcr:content/blank-redirect");
        final Map<String, Object> authored = new HashMap<>();
        authored.put("mobileRedirectUrl", " ");
        authored.put("redirectUrl", "/content/site/en/sign-in");

        final Map<String, Object> built = new FormBlockBuilder(NO_SCHEMAS)
                .build(resource, "create-account", "blank123", 0, authored);

        assertEquals("sign-in", meta(built).get("link"));
        final Map<String, Object> otherForm = new FormBlockBuilder(NO_SCHEMAS)
                .build(resource, "transfer-funds", "blank456", 0, authored);
        assertEquals("", meta(otherForm).get("link"));
    }
}

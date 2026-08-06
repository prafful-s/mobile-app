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
 * A block whose name matches a known form carries that form's field definitions, so a client can
 * render the form without a second call.
 */
@ExtendWith(AemContextExtension.class)
class FormBlockSchemaTest {

    private static final String SITE = "/content/acme/us/en";
    private static final String CONTAINER = SITE + "/jcr:content/root";

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

    private void block(final String name, final Object... properties) {
        final Object[] all = new Object[properties.length + 2];
        all[0] = "sling:resourceType";
        all[1] = "core/franklin/components/block/v1/block";
        System.arraycopy(properties, 0, all, 2, properties.length);
        context.build().resource(CONTAINER + "/" + name, all);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> map(final Object value) {
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> content() {
        final Page home = context.pageManager().getPage(SITE);
        final Map<String, Object> screens = map(exporter.export(home).get("screens"));
        return (List<Map<String, Object>>) map(screens.get("en")).get("content");
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> schemaOf(final String componentName) {
        return (List<Map<String, Object>>) component(componentName).get("schema");
    }

    private List<String> fieldIds(final String componentName) {
        final List<String> ids = new java.util.ArrayList<>();
        schemaOf(componentName).forEach(f -> ids.add(String.valueOf(f.get("id"))));
        return ids;
    }

    private Map<String, Object> component(final String componentName) {
        return content().stream()
                .filter(c -> componentName.equals(map(c.get("meta")).get("component")))
                .findFirst()
                .orElseThrow(() -> new AssertionError(componentName + " was not exported"));
    }

    @Test
    @DisplayName("a block named after a form carries that form's schema")
    void schemaAttachedByBlockName() {
        block("signin", "model", "sign-in", "title", "Sign in");

        // An ordered list, because a form is rendered top to bottom.
        assertEquals(List.of("email", "password"), fieldIds("sign-in"));

        final List<Map<String, Object>> schema = schemaOf("sign-in");
        assertEquals("Email address", schema.get(0).get("label"));
        assertEquals("personalEmail.address", schema.get(0).get("name"));
        assertEquals("password", schema.get(1).get("type"));
    }

    @Test
    @DisplayName("the authored content is left untouched - schema sits beside it")
    void schemaDoesNotDisturbContent() {
        block("signin", "model", "sign-in", "title", "Sign in");

        final Map<String, Object> component = component("sign-in");
        assertEquals(List.of("meta", "content", "schema"), List.copyOf(component.keySet()));
        assertEquals("Sign in", map(component.get("content")).get("title"));
    }

    @Test
    @DisplayName("a block that is not a form gets no schema key at all")
    void nonFormBlocksAreUntouched() {
        block("hero", "model", "hero", "text", "<h1>Welcome</h1>");

        assertFalse(component("hero").containsKey("schema"));
    }

    @Test
    @DisplayName("an explicit formName property wins over the block name")
    void explicitFormNameWins() {
        block("genericForm", "model", "form", "formName", "checkout-shipping");

        assertTrue(fieldIds("form").contains("shippingMethod"), fieldIds("form").toString());
    }

    @Test
    @DisplayName("a formName naming no known form leaves the block without a schema")
    void unknownFormName() {
        block("mystery", "model", "form", "formName", "not-a-real-form");

        assertFalse(component("form").containsKey("schema"));
    }

    @Test
    @DisplayName("several forms on one page each get their own schema")
    void multipleFormsOnAPage() {
        block("a", "model", "sign-in");
        block("b", "model", "create-account");
        block("c", "model", "hero", "text", "x");

        assertEquals(2, content().stream().filter(c -> c.containsKey("schema")).count());
        assertTrue(fieldIds("create-account").contains("trialConsent"),
                fieldIds("create-account").toString());
    }
}

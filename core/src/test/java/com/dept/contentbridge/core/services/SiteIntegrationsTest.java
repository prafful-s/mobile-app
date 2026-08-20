package com.dept.contentbridge.core.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertFalse;

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
 * integrations is shipped per-site data keyed by the site root's last segment - it never derives
 * from a page - so the exported {@code integrations} is exactly the object under
 * {@code integrations/sites.json}, nesting and all.
 */
@ExtendWith(AemContextExtension.class)
class SiteIntegrationsTest {

    private static final String SITE = "/content/secur-financial/language-masters/en";

    private final AemContext context = new AemContext(ResourceResolverType.JCR_MOCK);

    private PageJsonExporter exporter;

    @BeforeEach
    void setUp() {
        context.create().page(SITE, "", Map.of("jcr:title", "Home"));
        context.addModelsForPackage("com.dept.contentbridge.core.models.impl");
        context.registerInjectActivateService(new UrlServiceImpl(), "publishDomain", "");
        context.registerInjectActivateService(new FormSchemaServiceImpl());
        context.registerInjectActivateService(new PageJsonExporterImpl(),
                "allowedRoots", new String[] {"/content/secur-financial"}, "cacheMaxAge", 0);

        exporter = context.getService(PageJsonExporter.class);
        assertNotNull(exporter);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> map(final Object value) {
        return (Map<String, Object>) value;
    }

    private Map<String, Object> integrations() {
        final Page home = context.pageManager().getPage(SITE);
        return map(exporter.export(home, ExportOptions.of(false)).get("integrations"));
    }

    @Test
    @DisplayName("the shipped per-site integrations is served verbatim, nested groups and all")
    void shippedIntegrationsIsServed() {
        final Map<String, Object> integrations = integrations();

        assertFalse(integrations.isEmpty(), "secur-financial ships integrations");

        final Map<String, Object> aep = map(integrations.get("aep"));
        assertEquals(11507, aep.get("id"), "numeric types are preserved");
        assertEquals("public-securfinancial", aep.get("sandbox"));

        final Map<String, Object> launch = map(integrations.get("launch"));
        assertEquals("mobile", launch.get("type"));
        assertEquals("PR4b8b2aefbdf8475da4d857b793d50625", launch.get("propertyID"));

        final Map<String, Object> imsOrg = map(integrations.get("imsOrg"));
        assertEquals("demosystem4", imsOrg.get("tenantID"));
    }
}

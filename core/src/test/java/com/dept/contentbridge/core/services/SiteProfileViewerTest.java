package com.dept.contentbridge.core.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertFalse;

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
 * profileViewer is shipped per-site data keyed by the site root's last segment - it never derives
 * from a page - so the exported {@code profileViewer} is exactly the object under
 * {@code profileviewer/sites.json}, nesting and all.
 */
@ExtendWith(AemContextExtension.class)
class SiteProfileViewerTest {

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

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> list(final Object value) {
        return (List<Map<String, Object>>) value;
    }

    private Map<String, Object> profileViewer() {
        final Page home = context.pageManager().getPage(SITE);
        return map(exporter.export(home, ExportOptions.of(false)).get("profileViewer"));
    }

    @Test
    @DisplayName("the shipped per-site profileViewer is served verbatim, nested groups and all")
    void shippedProfileViewerIsServed() {
        final Map<String, Object> profileViewer = profileViewer();

        assertFalse(profileViewer.isEmpty(), "secur-financial ships a profileViewer");

        final List<String> segmentFilter =
                (List<String>) map(profileViewer.get("segments")).get("segmentFilter");
        assertEquals(List.of("SecurFinancial"), segmentFilter);

        final List<Map<String, Object>> identities = list(profileViewer.get("identities"));
        assertEquals("Email", identities.get(0).get("label"));

        final List<Map<String, Object>> profileFields = list(profileViewer.get("profileFields"));
        assertEquals("Name", profileFields.get(0).get("label"));
        assertEquals(7, profileFields.size(), "all shipped profile fields are kept");
    }
}

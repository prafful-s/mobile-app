package com.dept.contentbridge.core.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

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
 * Branding is shipped per-site data keyed by the site root's last segment - it never derives from a
 * page - so the exported {@code branding} is exactly the object under {@code sites.json}, nesting and
 * all, with only logo paths made absolute.
 */
@ExtendWith(AemContextExtension.class)
class SiteBrandingTest {

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

    private Map<String, Object> branding() {
        final Page home = context.pageManager().getPage(SITE);
        return map(exporter.export(home, ExportOptions.of(false)).get("branding"));
    }

    @Test
    @DisplayName("the shipped per-site branding is served verbatim, nested groups and all")
    void shippedBrandingIsServed() {
        final Map<String, Object> branding = branding();

        assertEquals("advanced", branding.get("variant"));
        assertEquals("#0056B3", branding.get("primaryColor"), "colour tokens pass through untouched");
        assertEquals(100, branding.get("logoWidth"), "numeric types are preserved");

        final Map<String, Object> cardStyles = map(branding.get("cardStyles"));
        assertEquals(12, cardStyles.get("borderRadius"), "nested style groups are kept");
        assertEquals("#FFFFFF", cardStyles.get("backgroundColor"));

        final Map<String, Object> fontFamily = map(branding.get("fontFamily"));
        assertEquals("Montserrat", fontFamily.get("heading"));
    }

    @Test
    @DisplayName("absolute logo URLs are left alone by the asset-host pass")
    void absoluteLogosSurvive() {
        assertEquals("https://demo-system-next.s3.eu-north-1.amazonaws.com/assets/securfinancial/logo.png",
                branding().get("logo"));
    }
}

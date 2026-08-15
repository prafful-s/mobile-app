package com.dept.contentbridge.core.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.List;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;
import org.apache.sling.testing.mock.sling.ResourceResolverType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
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
 * {@code product-card} carries little of its own: the title, image and description a client needs
 * live on the Content Fragment it references via {@code contentFragmentFolder}, not on the block
 * itself. The {@code productCard} rule dereferences that fragment and projects the result onto the
 * reference contract's {@code banner} shape - authored fields like {@code buttonText}/{@code ctastyle}
 * still come from the block itself.
 */
@ExtendWith(AemContextExtension.class)
@Disabled("productCard mapping temporarily removed from index.json; re-enable when the rule is restored")
class ProductCardMappingTest {

    private static final String SITE = "/content/secur-financial";
    private static final String HOME = SITE + "/language-masters/en";
    private static final String FRAGMENT =
            "/content/dam/secur-financial/en/fragments/credit-cards/securfinancial-gold";

    private final AemContext context = new AemContext(ResourceResolverType.JCR_MOCK);

    private PageJsonExporter exporter;

    @BeforeEach
    void setUp() {
        context.create().page(HOME, "", Map.of("jcr:title", "Home"));
        context.addModelsForPackage("com.dept.contentbridge.core.models.impl");
        context.registerInjectActivateService(new UrlServiceImpl(), "publishDomain", "");
        context.registerInjectActivateService(new FormSchemaServiceImpl());
        context.registerInjectActivateService(new PageJsonExporterImpl(),
                "allowedRoots", new String[] {"/content"}, "cacheMaxAge", 0);

        exporter = context.getService(PageJsonExporter.class);
    }

    /** Same node shape {@code ProductExportTest} builds - real Content Fragment layout, not a mock. */
    private void createFragment(final String path, final Map<String, String> elements) {
        context.build().resource(path, "jcr:primaryType", "dam:Asset");
        context.build().resource(path + "/jcr:content",
                "jcr:primaryType", "dam:AssetContent",
                "contentFragment", Boolean.TRUE,
                "jcr:title", StringUtils.substringAfterLast(path, "/"));
        context.build().resource(path + "/jcr:content/metadata",
                "dc:title", StringUtils.substringAfterLast(path, "/"));
        context.build().resource(path + "/jcr:content/data",
                "cq:model", "/conf/secur-financial/settings/dam/cfm/models/secur-financial-product");

        final Object[] properties = new Object[elements.size() * 2];
        int i = 0;
        for (final Map.Entry<String, String> entry : elements.entrySet()) {
            properties[i++] = entry.getKey();
            properties[i++] = entry.getValue();
        }
        context.build().resource(path + "/jcr:content/data/master", properties);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> map(final Object value) {
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> list(final Object value) {
        return (List<Map<String, Object>>) value;
    }

    private Map<String, Object> exportProductCard(final Object... blockProperties) {
        final Object[] all = new Object[blockProperties.length + 2];
        all[0] = "sling:resourceType";
        all[1] = "core/franklin/components/block/v1/block";
        System.arraycopy(blockProperties, 0, all, 2, blockProperties.length);
        context.build().resource(HOME + "/jcr:content/root/block", all);

        final Page home = context.pageManager().getPage(HOME);
        final Map<String, Object> app = exporter.export(home, ExportOptions.of(ExportOptions.SCHEMA_V2, null));
        final Map<String, Object> screens = map(app.get("screens"));
        return list(map(screens.get("en")).get("content")).get(0);
    }

    @Test
    @DisplayName("a product-card is projected onto the reference contract's banner shape, sourced "
            + "from the fragment it references")
    void productCardBecomesBanner() {
        createFragment(FRAGMENT, Map.of(
                "productName", "SecurFinancial GOLD",
                "productImage", "/content/dam/secur-financial/en/images/Secure-credit-cards/card-gold.png",
                "productSku", "QCjXPYxvM"));

        final Map<String, Object> exported = exportProductCard(
                "model", "product-card",
                "contentFragmentFolder", FRAGMENT,
                "buttonText", "Apply Now",
                "link", "/content/secur-financial/language-masters/en/credit-cards/credit-card-application",
                "ctastyle", "button");

        assertEquals("banner", map(exported.get("meta")).get("component"));

        final Map<String, Object> content = map(exported.get("content"));
        assertEquals("SecurFinancial GOLD", content.get("title"), "title comes from the fragment's productName");
        assertEquals("/content/dam/secur-financial/en/images/Secure-credit-cards/card-gold.png",
                content.get("image"), "image comes from the fragment's productImage");
        assertEquals("Apply Now", content.get("buttonLabel"), "buttonLabel comes from the block itself");
        assertEquals("/content/secur-financial/language-masters/en/credit-cards/credit-card-application",
                content.get("linkTo"), "linkTo comes from the block itself and stays relative");
        assertEquals(true, content.get("showButton"));
        assertEquals(0, content.get("opacity"));
    }

    @Test
    @DisplayName("ctastyle 'button' is translated to the reference contract's buttonVariant 'contained'")
    void ctaStyleButtonMapsToContained() {
        createFragment(FRAGMENT, Map.of("productName", "GOLD"));

        assertEquals("contained", map(exportProductCard(
                "model", "product-card", "contentFragmentFolder", FRAGMENT, "ctastyle", "button")
                .get("content")).get("buttonVariant"));
    }

    @Test
    @DisplayName("ctastyle 'cta-link' is translated to the reference contract's buttonVariant 'text'")
    void ctaStyleLinkMapsToText() {
        createFragment(FRAGMENT, Map.of("productName", "GOLD"));

        assertEquals("text", map(exportProductCard(
                "model", "product-card", "contentFragmentFolder", FRAGMENT, "ctastyle", "cta-link")
                .get("content")).get("buttonVariant"));
    }

    @Test
    @DisplayName("a contentFragmentFolder that does not resolve leaves the block's own fields as the "
            + "only source, rather than failing the export")
    void unresolvableFragmentDoesNotBreakExport() {
        final Map<String, Object> exported = exportProductCard(
                "model", "product-card",
                "contentFragmentFolder", "/content/dam/secur-financial/en/fragments/does-not-exist",
                "buttonText", "Apply Now");

        assertEquals("banner", map(exported.get("meta")).get("component"));
        final Map<String, Object> content = map(exported.get("content"));
        assertEquals("Apply Now", content.get("buttonLabel"));
        assertEquals("", content.get("title"), "no fragment resolved, so skeleton mode fills the empty default");
    }

    @Test
    @DisplayName("the block's own contentFragmentFolder path never leaks into extras")
    void contentFragmentFolderIsConsumedNotPassedThrough() {
        createFragment(FRAGMENT, Map.of("productName", "GOLD"));

        final Map<String, Object> content = map(exportProductCard(
                "model", "product-card", "contentFragmentFolder", FRAGMENT).get("content"));

        assertFalse(content.containsKey("contentFragmentFolder"));
    }
}

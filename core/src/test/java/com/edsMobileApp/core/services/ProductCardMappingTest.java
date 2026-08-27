package com.edsMobileApp.core.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.List;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;
import org.apache.sling.testing.mock.sling.ResourceResolverType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import com.edsMobileApp.core.services.impl.FormSchemaServiceImpl;
import com.edsMobileApp.core.services.impl.PageJsonExporterImpl;
import com.edsMobileApp.core.services.impl.UrlServiceImpl;
import com.day.cq.wcm.api.Page;

import io.wcm.testing.mock.aem.junit5.AemContext;
import io.wcm.testing.mock.aem.junit5.AemContextExtension;

/**
 * {@code product-card} carries little of its own: the title, image and description a client needs
 * live on the Content Fragment it references via {@code contentFragmentFolder}, not on the block
 * itself. The {@code productCard} rule dereferences that fragment and projects the result onto the
 * reference contract's {@code card} shape - the {@code link} still comes from the block itself.
 */
@ExtendWith(AemContextExtension.class)
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
        context.addModelsForPackage("com.edsMobileApp.core.models.impl");
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
        final Map<String, Object> app = exporter.export(home, ExportOptions.of(false));
        final Map<String, Object> screens = map(app.get("screens"));
        return list(map(screens.get("en")).get("content")).get(0);
    }

    @Test
    @DisplayName("a product-card is projected onto the reference contract's card shape, sourced "
            + "from the fragment it references")
    void productCardBecomesCard() {
        createFragment(FRAGMENT, Map.of(
                "productName", "SecurFinancial GOLD",
                "productImage", "/content/dam/secur-financial/en/images/Secure-credit-cards/card-gold.png",
                "productSku", "QCjXPYxvM",
                "productDescription", "<p>Premium rewards.</p><ul><li>No annual fee</li></ul>"));

        final Map<String, Object> exported = exportProductCard(
                "model", "product-card",
                "contentFragmentFolder", FRAGMENT,
                "buttonText", "Apply Now",
                "link", "/content/secur-financial/language-masters/en/credit-cards/credit-card-application",
                "ctastyle", "button");

        assertEquals("card", map(exported.get("meta")).get("component"));

        final Map<String, Object> content = map(exported.get("content"));
        assertEquals("SecurFinancial GOLD", content.get("title"), "title comes from the fragment's productName");
        assertEquals("/content/dam/secur-financial/en/images/Secure-credit-cards/card-gold.png",
                content.get("image"), "image comes from the fragment's productImage");
        assertEquals("Premium rewards.\nNo annual fee", content.get("subtitle"),
                "subtitle text comes from the fragment's productDescription");
        assertEquals("credit-card-application?productId=QCjXPYxvM", content.get("link"),
                "link is the screen key with the fragment's sku appended as productId");
        assertEquals("stacked", content.get("cardType"));

        assertFalse(exported.containsKey("styles"), "cards carry no styles block");
    }

    @Test
    @DisplayName("a product-card with no link on the block falls back to '#'")
    void productCardLinkDefaultsToHash() {
        createFragment(FRAGMENT, Map.of("productName", "GOLD"));

        assertEquals("#", map(exportProductCard(
                "model", "product-card", "contentFragmentFolder", FRAGMENT)
                .get("content")).get("link"));
    }

    @Test
    @DisplayName("a contentFragmentFolder that does not resolve leaves the block's own fields as the "
            + "only source, rather than failing the export")
    void unresolvableFragmentDoesNotBreakExport() {
        final Map<String, Object> exported = exportProductCard(
                "model", "product-card",
                "contentFragmentFolder", "/content/dam/secur-financial/en/fragments/does-not-exist",
                "link", "/content/secur-financial/language-masters/en/credit-cards/credit-card-application");

        assertEquals("card", map(exported.get("meta")).get("component"));
        final Map<String, Object> content = map(exported.get("content"));
        assertEquals("credit-card-application",
                content.get("link"), "the block's own link still projects, reduced to its screen key");
        assertFalse(content.containsKey("title"), "no fragment resolved, so there is no title");
    }

    @Test
    @DisplayName("a product-card with only text and a link (no image) still renders as a card")
    void productCardWithTextAndLinkOnlyStillCard() {
        createFragment(FRAGMENT, Map.of(
                "productName", "SecurFinancial GOLD",
                "productDescription", "<p>Premium rewards.</p>"));

        final Map<String, Object> exported = exportProductCard(
                "model", "product-card",
                "contentFragmentFolder", FRAGMENT,
                "link", "/content/secur-financial/language-masters/en/credit-cards/credit-card-application");

        assertEquals("card", map(exported.get("meta")).get("component"), "no image, but still a card");
        final Map<String, Object> content = map(exported.get("content"));
        assertEquals("SecurFinancial GOLD", content.get("title"));
        assertEquals("Premium rewards.", content.get("subtitle"));
        assertEquals("credit-card-application", content.get("link"));
        assertFalse(content.containsKey("image"), "no image was authored on the fragment");
    }

    @Test
    @DisplayName("the block's own contentFragmentFolder path never leaks into the card content")
    void contentFragmentFolderIsConsumedNotPassedThrough() {
        createFragment(FRAGMENT, Map.of("productName", "GOLD"));

        final Map<String, Object> content = map(exportProductCard(
                "model", "product-card", "contentFragmentFolder", FRAGMENT).get("content"));

        assertFalse(content.containsKey("contentFragmentFolder"));
    }
}

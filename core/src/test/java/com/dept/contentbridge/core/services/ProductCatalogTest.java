package com.dept.contentbridge.core.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import com.dept.contentbridge.core.ContentBridgeTestContext;
import com.day.cq.wcm.api.Page;

import io.wcm.testing.mock.aem.junit5.AemContext;
import io.wcm.testing.mock.aem.junit5.AemContextExtension;

@ExtendWith(AemContextExtension.class)
@DisplayName("products are built from the site's product Content Fragments")
class ProductCatalogTest {

    private static final String MODEL =
            "/conf/secur-financial/settings/dam/cfm/models/secur-financial-product";
    private static final String FRAGMENTS = "/content/dam/contentbridge/en/fragments";

    private final AemContext context = ContentBridgeTestContext.newContext();

    private PageJsonExporter exporter;

    @BeforeEach
    void setUp() {
        ContentBridgeTestContext.setUp(context);
        exporter = context.getService(PageJsonExporter.class);
    }

    /** Authors one product Content Fragment under a sub-folder of the site's DAM tree. */
    private void product(final String folder, final Object... elements) {
        final String base = FRAGMENTS + "/" + folder;
        context.build().resource(base, "jcr:primaryType", "dam:Asset");
        context.build().resource(base + "/jcr:content",
                "jcr:primaryType", "dam:AssetContent", "contentFragment", true);
        context.build().resource(base + "/jcr:content/data", "cq:model", MODEL);
        context.build().resource(base + "/jcr:content/data/master", elements);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> products() {
        final Page home = context.pageManager().getPage(ContentBridgeTestContext.APP_ROOT);
        return (List<Map<String, Object>>) exporter.export(home).get("products");
    }

    @Test
    @DisplayName("a secur-financial-product fragment projects onto the product shape")
    void productShape() {
        product("insurance/health",
                "productSku", "axHhfaW$D",
                "productName", "Health & Accident Insurance",
                "productCategory", "Insurance",
                "productDescription", "<p>Get set to deal with exigencies.</p>",
                "productImage", "/content/dam/contentbridge/en/images/health.jpg");

        final List<Map<String, Object>> products = products();
        assertEquals(1, products.size());

        final Map<String, Object> product = products.get(0);
        assertEquals(List.of("id", "name", "category", "description", "image"),
                List.copyOf(product.keySet()), "keys are in the rule's declared order");
        assertEquals("axHhfaW$D", product.get("id"));
        assertEquals("Health & Accident Insurance", product.get("name"));
        assertEquals("Insurance", product.get("category"));
        assertEquals("<p>Get set to deal with exigencies.</p>", product.get("description"));
        assertEquals(ContentBridgeTestContext.PUBLISH_DOMAIN
                + "/content/dam/contentbridge/en/images/health.jpg", product.get("image"),
                "the image path is made absolute against the delivery host");
    }

    @Test
    @DisplayName("fragments in sub-folders are all collected")
    void nestedFoldersAreScanned() {
        product("insurance/health", "productSku", "a", "productName", "Health");
        product("loans/home", "productSku", "b", "productName", "Home Loan");
        product("credit-cards/basic", "productSku", "c", "productName", "Basic Card");

        assertEquals(3, products().size());
    }

    @Test
    @DisplayName("a fragment of an unrecognised model is not a product")
    void otherModelsAreIgnored() {
        context.build().resource(FRAGMENTS + "/themes/blue", "jcr:primaryType", "dam:Asset");
        context.build().resource(FRAGMENTS + "/themes/blue/jcr:content",
                "jcr:primaryType", "dam:AssetContent", "contentFragment", true);
        context.build().resource(FRAGMENTS + "/themes/blue/jcr:content/data",
                "cq:model", "/conf/ref-demo-eds/settings/dam/cfm/models/brand-theme");
        context.build().resource(FRAGMENTS + "/themes/blue/jcr:content/data/master",
                "themeName", "Blue");

        assertTrue(products().isEmpty(), "only known product models are exported");
    }

    @Test
    @DisplayName("a site with no product fragments exports an empty list, not null")
    void noProducts() {
        final List<Map<String, Object>> products = products();
        assertTrue(products.isEmpty());
        assertFalse(products == null);
    }
}

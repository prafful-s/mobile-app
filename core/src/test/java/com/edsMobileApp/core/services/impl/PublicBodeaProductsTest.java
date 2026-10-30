package com.edsMobileApp.core.services.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.wcm.testing.mock.aem.junit5.AemContext;
import io.wcm.testing.mock.aem.junit5.AemContextExtension;

/**
 * Public Bodea's Content Fragments produced no products at all: {@link ProductCatalog} discovers
 * fragments by {@code cq:model} <em>and</em> by rules scoped per site, and no rule claimed
 * {@code /content/public-bodea}. Its 29 fragments were walked past, so the envelope's
 * {@code products} came out empty - and a category-products-lister, which exports only the ids the
 * client then resolves against that catalogue, had nothing to list.
 *
 * <p>Bodea's consultants carry their image in {@code externalImageURL} (an S3 URL) rather than
 * {@code damImageURL}, and no {@code sku} at all, so the rule has to fall back for both the way
 * citi-signal's does.</p>
 */
@ExtendWith(AemContextExtension.class)
class PublicBodeaProductsTest {

    private static final String PRODUCT_MODEL =
            "/conf/dsn-eds-configuration/settings/dam/cfm/models/product-model";

    private final AemContext context = new AemContext();

    /** Creates a fragment the way the Assets HTTP API lays one out. */
    private void fragment(final String path, final Map<String, Object> elements) {
        context.create().resource(path + "/jcr:content", "contentFragment", Boolean.TRUE);
        context.create().resource(path + "/jcr:content/data", "cq:model", PRODUCT_MODEL);
        final Object[] props = elements.entrySet().stream()
                .flatMap(e -> java.util.stream.Stream.of(e.getKey(), e.getValue()))
                .toArray();
        context.create().resource(path + "/jcr:content/data/master", props);
    }

    private List<Map<String, Object>> productsOf(final String sitePath) {
        final com.day.cq.wcm.api.Page page = context.create().page(sitePath + "/language-masters/en");
        return new ProductCatalog(ProductMappings.load()).export(page, "https://assets.example.com");
    }

    @Test
    void aConsultantFragmentBecomesAProduct() {
        fragment("/content/dam/public-bodea/en/fragments/consultants/martin-buergi",
                Map.of("id", "73POxTSmk", "name", "Martin Buergi",
                        "externalImageURL", "https://s3.example.com/martin.jpeg",
                        "category", new String[] {"public-bodea:consultant/lawyer"}));

        final List<Map<String, Object>> products = productsOf("/content/public-bodea");

        assertEquals(1, products.size(), "the rule claims product-model for this site");
        final Map<String, Object> product = products.get(0);
        assertEquals("73POxTSmk", product.get("id"), "no sku is authored, so id stands in");
        assertEquals("73POxTSmk", product.get("sku"));
        assertEquals("Martin Buergi", product.get("name"));
        assertEquals("https://s3.example.com/martin.jpeg", product.get("image"),
                "the image is an external URL, not a DAM path");
        assertEquals("https://s3.example.com/martin.jpeg", product.get("thumbnail"));
    }

    @Test
    void theNestedJobTitleTagBecomesReadableText() {
        fragment("/content/dam/public-bodea/en/fragments/consultants/lena-novik",
                Map.of("id", "ur2hB6M8K6", "name", "Lena Novik",
                        "externalImageURL", "https://s3.example.com/lena.jpeg",
                        "category", new String[] {"public-bodea:consultant/financial-specialist"}));

        assertEquals("Consultant / Financial Specialist",
                productsOf("/content/public-bodea").get(0).get("category"),
                "categoryLabel turns the tag path into the label pair the client can show");
    }

    @Test
    void theServiceFragmentsInTheSameTreeAreProductsToo() {
        fragment("/content/dam/public-bodea/en/fragments/service/accounting-and-reporting",
                Map.of("id", "z1kAi8L3P", "name", "Accounting and Reporting",
                        "externalImageURL", "https://s3.example.com/acc.jpeg",
                        "category", new String[] {"public-bodea:services"},
                        "description", "<p>Regulatory complexities…</p>"));
        fragment("/content/dam/public-bodea/en/fragments/consultants/lisa-smith",
                Map.of("id", "1IaS8cEH_", "name", "Lisa Smith",
                        "externalImageURL", "https://s3.example.com/lisa.jpeg",
                        "category", new String[] {"public-bodea:consultant/marketer"}));

        final List<String> names = productsOf("/content/public-bodea").stream()
                .map(p -> String.valueOf(p.get("name"))).sorted().collect(Collectors.toList());

        assertEquals(List.of("Accounting and Reporting", "Lisa Smith"), names,
                "the scope covers en/fragments, so both folders contribute - listers filter by tag");
    }

    @Test
    void aBrandThemeFragmentInTheSameFolderIsNotAProduct() {
        context.create().resource(
                "/content/dam/public-bodea/en/fragments/brand-themes/blue-and-black/jcr:content/data",
                "cq:model", "/conf/dsn-eds-configuration/settings/dam/cfm/models/mobile-app-theme");

        assertTrue(productsOf("/content/public-bodea").isEmpty(),
                "only product-model fragments are exported; the rest are walked past");
    }

    @Test
    void anotherSitesFragmentsAreUntouchedByThisRule() {
        fragment("/content/dam/some-other-site/en/fragments/products/thing",
                Map.of("id", "x1", "name", "Thing", "externalImageURL", "https://s3.example.com/t.jpeg"));

        assertTrue(productsOf("/content/some-other-site").isEmpty(),
                "the rule is scoped to /content/public-bodea and must not widen any other site");
    }
}

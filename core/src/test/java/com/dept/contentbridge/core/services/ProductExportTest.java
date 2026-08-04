package com.dept.contentbridge.core.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;
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
 * Products are read from Content Fragments and projected onto one fixed key set, whatever the
 * underlying model calls its fields.
 */
@ExtendWith(AemContextExtension.class)
class ProductExportTest {

    private static final String SITE = "/content/halliby";
    private static final String HOME = SITE + "/en";
    private static final String PRODUCTS = "/content/dam/halliby/en/fragments/halliby/products";

    private final AemContext context = new AemContext(ResourceResolverType.JCR_MOCK);

    @BeforeEach
    void setUp() {
        context.create().page(HOME, "", Map.of("jcr:title", "Home"));
        context.registerInjectActivateService(new UrlServiceImpl(), "publishDomain", "");
    }

    private PageJsonExporter exporter(final Object... extraConfig) {
        context.addModelsForPackage("com.dept.contentbridge.core.models.impl");

        final Object[] config = new Object[extraConfig.length + 4];
        config[0] = "allowedRoots";
        config[1] = new String[] {"/content"};
        config[2] = "cacheMaxAge";
        config[3] = 0;
        System.arraycopy(extraConfig, 0, config, 4, extraConfig.length);

        context.registerInjectActivateService(new FormSchemaServiceImpl());

        context.registerInjectActivateService(new PageJsonExporterImpl(), config);
        final PageJsonExporter exporter = context.getService(PageJsonExporter.class);
        assertNotNull(exporter);
        return exporter;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> products(final PageJsonExporter exporter) {
        final Page home = context.pageManager().getPage(HOME);
        return (List<Map<String, Object>>) exporter.export(home).get("products");
    }

    /**
     * Builds the Content Fragment node structure directly rather than via a mock helper, so the test
     * exercises the same layout AEM stores and does not depend on aem-mock's fragment support.
     */
    private void createProductAt(final String path, final Map<String, String> elements) {
        context.build().resource(path, "jcr:primaryType", "dam:Asset");
        context.build().resource(path + "/jcr:content",
                "jcr:primaryType", "dam:AssetContent",
                "contentFragment", Boolean.TRUE,
                "jcr:title", StringUtils.substringAfterLast(path, "/"));
        // Real DAM assets always carry this; without it the Content Fragment adapter throws.
        context.build().resource(path + "/jcr:content/metadata",
                "dc:title", StringUtils.substringAfterLast(path, "/"));
        context.build().resource(path + "/jcr:content/data",
                "cq:model", "/conf/halliby/settings/dam/cfm/models/product");

        final List<Object> properties = new ArrayList<>();
        elements.forEach((key, value) -> {
            properties.add(key);
            properties.add(value);
        });
        context.build().resource(path + "/jcr:content/data/master", properties.toArray());
    }

    private void createProduct(final String name, final Map<String, String> elements) {
        createProductAt(PRODUCTS + "/" + name, elements);
    }

    @Test
    @DisplayName("no products folder yields an empty list, not a failure")
    void noProductsFolder() {
        assertTrue(products(exporter()).isEmpty());
    }

    @Test
    @DisplayName("every product carries the full key set in a stable order")
    void fixedKeySet() {
        createProduct("mh13", Map.of("sku", "MH13", "name", "Marco Lightweight Active Hoodie"));

        final List<Map<String, Object>> products = products(exporter());
        assertEquals(1, products.size());
        assertEquals(List.of("id", "sku", "name", "image", "price", "type_id", "category",
                        "thumbnail", "description", "category_ids", "importedFrom",
                        "category_uids", "short_description"),
                List.copyOf(products.get(0).keySet()));
    }

    @Test
    @DisplayName("missing strings become empty, missing lists become empty arrays")
    void missingValuesGetEmptyDefaults() {
        createProduct("mh13", Map.of("sku", "MH13"));

        final Map<String, Object> product = products(exporter()).get(0);
        assertEquals("", product.get("short_description"));
        assertEquals(List.of(), product.get("category_ids"));
        assertEquals(List.of(), product.get("category_uids"));
    }

    @Test
    @DisplayName("an absent price is null, never zero - zero would read as free")
    void absentPriceIsNull() {
        createProduct("mh13", Map.of("sku", "MH13"));

        assertNull(products(exporter()).get(0).get("price"));
    }

    @Test
    @DisplayName("price is emitted as a number, not a string")
    void priceIsNumeric() {
        createProduct("mh13", Map.of("sku", "MH13", "price", "74"));

        assertEquals(74L, products(exporter()).get(0).get("price"));
    }

    @Test
    @DisplayName("category_ids parse to integers and category_uids stay strings")
    void listCoercion() {
        createProduct("mh13", Map.of(
                "sku", "MH13",
                "category_ids", "12,15",
                "category_uids", "MTI=,MTU="));

        final Map<String, Object> product = products(exporter()).get(0);
        assertEquals(List.of(12, 15), product.get("category_ids"));
        assertEquals(List.of("MTI=", "MTU="), product.get("category_uids"));
    }

    @Test
    @DisplayName("a model using different element names is mapped via configured aliases")
    void aliasesMapForeignModels() {
        createProduct("mh13", Map.of("productCode", "MH13", "productName", "Hoodie"));

        final List<Map<String, Object>> products = products(exporter(
                "productFieldAliases", new String[] {"sku=productCode", "name=productName"}));

        assertEquals("MH13", products.get(0).get("sku"));
        assertEquals("Hoodie", products.get(0).get("name"));
    }

    @Test
    @DisplayName("id and sku fall back to the fragment name when the model has neither")
    void identityFallsBackToFragmentName() {
        createProduct("mh13", Map.of("someUnrelatedField", "x"));

        final Map<String, Object> product = products(exporter()).get(0);
        assertEquals("mh13", product.get("id"));
        assertEquals("mh13", product.get("sku"));
    }

    @Test
    @DisplayName("the product cap truncates rather than letting a big catalogue stall the response")
    void capIsEnforced() {
        for (int i = 0; i < 5; i++) {
            createProduct("p" + i, Map.of("sku", "SKU" + i));
        }

        assertEquals(3, products(exporter("maxProducts", 3)).size());
    }

    @Test
    @DisplayName("the productsPath request parameter wins over everything else")
    void requestParameterWins() {
        createProduct("from-search", Map.of("sku", "SEARCH"));
        createProductAt("/content/dam/elsewhere/catalogue/from-param", Map.of("sku", "PARAM"));

        final Page home = context.pageManager().getPage(HOME);
        @SuppressWarnings("unchecked")
        final List<Map<String, Object>> products = (List<Map<String, Object>>) exporter()
                .export(home, "/content/dam/elsewhere/catalogue").get("products");

        assertEquals("PARAM", products.get(0).get("sku"));
    }

    @Test
    @DisplayName("fragments nested in category sub-folders are still collected")
    void nestedCategoryFolders() {
        createProductAt(PRODUCTS + "/beverages/tea", Map.of("sku", "TEA"));
        createProductAt(PRODUCTS + "/snacks/pizza", Map.of("sku", "PIZZA"));

        assertEquals(2, products(exporter()).size());
    }

    @Test
    @DisplayName("productsPath on the site root overrides the folder search")
    void explicitProductsPath() {
        createProductAt("/content/dam/elsewhere/catalogue/mh13", Map.of("sku", "MH13"));
        context.create().page(SITE + "/en2", "", Map.of(
                "jcr:title", "Other",
                "productsPath", "/content/dam/elsewhere/catalogue"));

        final Page page = context.pageManager().getPage(SITE + "/en2");
        @SuppressWarnings("unchecked")
        final List<Map<String, Object>> products =
                (List<Map<String, Object>>) exporter().export(page).get("products");

        assertEquals("MH13", products.get(0).get("sku"));
    }
}

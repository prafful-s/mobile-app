package com.dept.contentbridge.core.services;

import org.apache.commons.lang3.StringUtils;
import org.osgi.annotation.versioning.ProviderType;

/**
 * Per-request options for an export.
 *
 * <p>Introduced so the exporter signature stops growing a parameter per feature. Immutable, so it
 * can be built once per request and passed down without defensive copying.</p>
 */
@ProviderType
public final class ExportOptions {

    /** The original flat contract. Default, so existing clients are unaffected. */
    public static final String SCHEMA_V1 = "v1";

    /**
     * The nested contract: layout containers are preserved as {@code panel} components whose
     * {@code content} is an array of children, rather than being flattened away.
     */
    public static final String SCHEMA_V2 = "v2";

    private final String schemaVersion;
    private final String productsPath;
    private final String graphQlConfig;
    private final String graphQlQuery;
    private final boolean includeProducts;

    private ExportOptions(final String schemaVersion, final String productsPath,
                          final String graphQlConfig, final String graphQlQuery,
                          final boolean includeProducts) {
        this.schemaVersion = schemaVersion;
        this.productsPath = productsPath;
        this.graphQlConfig = graphQlConfig;
        this.graphQlQuery = graphQlQuery;
        this.includeProducts = includeProducts;
    }

    public static ExportOptions defaults() {
        return new ExportOptions(SCHEMA_V1, null, null, null, true);
    }

    /**
     * @param schemaVersion {@code v1} or {@code v2}; anything else falls back to v1 rather than
     *                      failing, so a typo degrades to the stable contract
     * @param productsPath  explicit catalogue location, may be blank
     */
    public static ExportOptions of(final String schemaVersion, final String productsPath) {
        return of(schemaVersion, productsPath, null, null);
    }

    /**
     * @param schemaVersion {@code v1} or {@code v2}; anything else falls back to v1
     * @param productsPath  catalogue location, may be blank
     * @param graphQlConfig GraphQL endpoint configuration name, e.g. {@code dsn-eds-configuration}
     * @param graphQlQuery  persisted query name, e.g. {@code productListByPathBinji}. Blank means
     *                      the site's placeholders decide, and if they say nothing either, products
     *                      are read from Content Fragments directly.
     */
    public static ExportOptions of(final String schemaVersion, final String productsPath,
                                   final String graphQlConfig, final String graphQlQuery) {
        return of(schemaVersion, productsPath, graphQlConfig, graphQlQuery, true);
    }

    /**
     * @param includeProducts when false the {@code products} array is emitted empty and no product
     *                        source (GraphQL, Content Fragments or authored JSON) is consulted
     */
    public static ExportOptions of(final String schemaVersion, final String productsPath,
                                   final String graphQlConfig, final String graphQlQuery,
                                   final boolean includeProducts) {
        return new ExportOptions(
                SCHEMA_V2.equalsIgnoreCase(StringUtils.trimToEmpty(schemaVersion))
                        ? SCHEMA_V2 : SCHEMA_V1,
                productsPath, graphQlConfig, graphQlQuery, includeProducts);
    }

    public String getSchemaVersion() {
        return schemaVersion;
    }

    public String getProductsPath() {
        return productsPath;
    }

    public String getGraphQlConfig() {
        return graphQlConfig;
    }

    public String getGraphQlQuery() {
        return graphQlQuery;
    }

    /** @return false when the caller asked for the {@code products} array to be left empty */
    public boolean isIncludeProducts() {
        return includeProducts;
    }

    /** @return true when layout containers should be preserved rather than flattened */
    public boolean isHierarchical() {
        return SCHEMA_V2.equals(schemaVersion);
    }

    @Override
    public String toString() {
        return "ExportOptions[schema=" + schemaVersion + ", productsPath=" + productsPath + "]";
    }
}

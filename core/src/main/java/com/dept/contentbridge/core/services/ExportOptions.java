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

    private ExportOptions(final String schemaVersion, final String productsPath) {
        this.schemaVersion = schemaVersion;
        this.productsPath = productsPath;
    }

    public static ExportOptions defaults() {
        return new ExportOptions(SCHEMA_V1, null);
    }

    /**
     * @param schemaVersion {@code v1} or {@code v2}; anything else falls back to v1 rather than
     *                      failing, so a typo degrades to the stable contract
     * @param productsPath  explicit catalogue location, may be blank
     */
    public static ExportOptions of(final String schemaVersion, final String productsPath) {
        return new ExportOptions(
                SCHEMA_V2.equalsIgnoreCase(StringUtils.trimToEmpty(schemaVersion))
                        ? SCHEMA_V2 : SCHEMA_V1,
                productsPath);
    }

    public String getSchemaVersion() {
        return schemaVersion;
    }

    public String getProductsPath() {
        return productsPath;
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

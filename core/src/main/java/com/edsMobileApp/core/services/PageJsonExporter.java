package com.edsMobileApp.core.services;

import java.util.Map;

import org.osgi.annotation.versioning.ProviderType;

import com.day.cq.wcm.api.Page;

/**
 * Turns an authored AEM page tree into the ContentBridge JSON contract.
 */
@ProviderType
public interface PageJsonExporter {

    /**
     * Builds the full app envelope.
     *
     * @param requested the page named by the caller, treated as the root of the export. The
     *                  envelope (meta, branding, integrations) is read from it, and every content
     *                  page beneath it is exported as a screen. Structural containers such as
     *                  {@code language-masters} are walked through rather than emitted, so the
     *                  screens start at the first real content page below the root.
     * @return an insertion-ordered map ready for serialisation
     */
    Map<String, Object> export(Page requested);

    /**
     * As {@link #export(Page)}, with explicit per-request options.
     *
     * @param requested the page named by the caller
     * @param options   per-request options; never null
     * @return an insertion-ordered map ready for serialisation
     */
    Map<String, Object> export(Page requested, ExportOptions options);

    /**
     * @return the configured Cache-Control max-age in seconds
     */
    int getCacheMaxAge();

    /**
     * @return true when a raw JSON payload found on the requested node should be served verbatim
     *         instead of exporting the page
     */
    boolean isOverrideEnabled();

    /**
     * @return the String property name checked for a raw JSON override payload
     */
    String getOverridePropertyName();
}

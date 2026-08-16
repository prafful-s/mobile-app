package com.dept.contentbridge.core.services;

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
     * @param path a repository path
     * @return true when the path sits under one of the configured allowed roots
     */
    boolean isAllowed(String path);

    /**
     * @return the configured Cache-Control max-age in seconds
     */
    int getCacheMaxAge();
}

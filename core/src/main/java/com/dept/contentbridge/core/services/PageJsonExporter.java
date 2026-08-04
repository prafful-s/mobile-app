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
     * @param requested the page named by the caller. The envelope (meta, branding, integrations) is
     *                  always read from the nearest ancestor carrying an {@code appId}; when the
     *                  requested page <em>is</em> that root, every descendant screen is included,
     *                  otherwise only the requested page is returned.
     * @return an insertion-ordered map ready for serialisation
     */
    Map<String, Object> export(Page requested);

    /**
     * As {@link #export(Page)}, but with an explicit catalogue location.
     *
     * @param requested    the page named by the caller
     * @param productsPath overrides where product Content Fragments are read from; when blank the
     *                     site's placeholders page, then its page properties, then a DAM search decide
     * @return an insertion-ordered map ready for serialisation
     */
    Map<String, Object> export(Page requested, String productsPath);

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

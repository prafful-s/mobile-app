package com.dept.contentbridge.core.services;

import org.osgi.annotation.versioning.ProviderType;

/**
 * Turns repository paths into the URLs the consuming client should call.
 */
@ProviderType
public interface UrlService {

    /**
     * Externalises a DAM path into an absolute URL using the configured publish domain. Values that
     * are already absolute, are anchors, or are blank are returned untouched.
     *
     * @param path a repository path, absolute URL, or anchor
     * @return the URL to place in the JSON payload
     */
    String externalize(String path);

    /**
     * The configured publish domain, without a trailing slash, or an empty string when none is set.
     *
     * <p>Exposed so the exporter can fall back to it for sites whose {@code placeholders} page
     * carries no {@code hostname}. Without the fallback such a site would emit bare repository paths
     * even on an instance that knows its own delivery domain.</p>
     *
     * @return the publish domain, never {@code null}
     */
    String getPublishDomain();
}

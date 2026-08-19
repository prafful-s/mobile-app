package com.dept.contentbridge.core.services;

import java.util.List;

import org.osgi.annotation.versioning.ProviderType;

/**
 * One site's GraphQL persisted-query settings.
 *
 * <p>Loaded from {@code /contentbridge/graphql/&lt;name&gt;.json}. Names the AEM Sites configuration
 * folder that owns the query, the query itself and the variable name to pass the CF folder path in;
 * plus a fallback {@code defaultPath} for when no other source has one and the fields to project onto
 * each product's identifier.</p>
 */
@ProviderType
public interface GraphQLSiteConfig {

    /** Site root path this config matches ({@code /content/&lt;site&gt;}). */
    String getSiteRoot();

    /** Sites config folder (last segment of {@code /graphql/execute.json/&lt;configName&gt;/...}). */
    String getConfigName();

    /** Persisted query name. */
    String getQueryName();

    /** Variable name the query expects for the CF folder path (e.g. {@code _path}). */
    String getPathVariable();

    /** Fallback CF folder path when the caller has none of its own. */
    String getDefaultPath();

    /** Per-item identifier fields, in preference order. First non-blank per product wins. */
    List<String> getCollectFields();
}

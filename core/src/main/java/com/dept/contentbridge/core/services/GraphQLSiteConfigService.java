package com.dept.contentbridge.core.services;

import java.util.Set;

import org.osgi.annotation.versioning.ProviderType;

import com.day.cq.wcm.api.Page;

/**
 * Serves the per-site GraphQL persisted-query settings bundled under
 * {@code /contentbridge/graphql/}.
 */
@ProviderType
public interface GraphQLSiteConfigService {

    /**
     * @param page any page in the site being exported
     * @return the config whose {@code siteRoot} the page sits under, or {@code null} when no site
     *         config matches
     */
    GraphQLSiteConfig forPage(Page page);

    /** The names of every site config that loaded successfully - for tests and diagnostics. */
    Set<String> configuredSites();
}

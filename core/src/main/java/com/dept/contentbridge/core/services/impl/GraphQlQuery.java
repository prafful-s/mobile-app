package com.dept.contentbridge.core.services.impl;

import java.util.function.Predicate;
import java.util.regex.Pattern;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.dept.contentbridge.core.services.ExportOptions;
import com.day.cq.wcm.api.Page;

/**
 * Which persisted query to run for a site, and over what.
 *
 * <p>Resolved from the request first, then the site's {@code placeholders} page. The request wins so
 * one site can be pointed at a different catalogue for a demo without editing content; placeholders
 * carry the answer for everyone else, in the same place {@code hostname} already lives.</p>
 *
 * <pre>
 * ?gqlConfig=dsn-eds-configuration&amp;gqlQuery=productListByPathBinji
 *   &amp;productsPath=/content/dam/binji/products
 * </pre>
 *
 * <p>Nothing configured means no query, which is not an error: the site falls back to reading its
 * Content Fragments directly, exactly as before this existed.</p>
 */
final class GraphQlQuery {

    private static final Logger LOG = LoggerFactory.getLogger(GraphQlQuery.class);

    /**
     * Config and query names address a path segment, so they are restricted to what can appear in
     * one. This is not the security boundary - persisted queries have to be registered before they
     * can run, so a caller can only pick among queries that already exist - but it keeps a malformed
     * value from being pasted into a URL and resolving somewhere unintended.
     */
    private static final Pattern SAFE_NAME = Pattern.compile("[A-Za-z0-9._-]+");

    private final String configName;
    private final String queryName;
    private final String path;

    private GraphQlQuery(final String configName, final String queryName, final String path) {
        this.configName = configName;
        this.queryName = queryName;
        this.path = path;
    }

    /** A query that will not run, so the caller falls back. */
    static GraphQlQuery none() {
        return new GraphQlQuery(null, null, null);
    }

    /**
     * @param page      any page in the site, used to find its placeholders
     * @param options   the request
     * @param isAllowed the exporter's path guard - see {@link #getPath()}
     */
    static GraphQlQuery resolve(final Page page, final ExportOptions options,
                                final Predicate<String> isAllowed) {
        final String configName = pick(options.getGraphQlConfig(),
                SiteHostResolver.placeholder(page, GraphQlProductSource.PN_CONFIG));
        final String queryName = pick(options.getGraphQlQuery(),
                SiteHostResolver.placeholder(page, GraphQlProductSource.PN_QUERY));

        if (StringUtils.isBlank(configName) || StringUtils.isBlank(queryName)) {
            return none();
        }
        if (!SAFE_NAME.matcher(configName).matches() || !SAFE_NAME.matcher(queryName).matches()) {
            LOG.warn("Refusing persisted query '{}/{}' - names may only contain letters, digits, "
                    + "dots, dashes and underscores", configName, queryName);
            return none();
        }

        final String path = pick(options.getProductsPath(),
                SiteHostResolver.placeholder(page, GraphQlProductSource.PN_PRODUCTS_PATH));
        if (StringUtils.isBlank(path)) {
            LOG.warn("Persisted query {}/{} is configured but no {} was given - not running it",
                    configName, queryName, GraphQlProductSource.PN_PRODUCTS_PATH);
            return none();
        }

        // The path arrives from the request and is handed to a query that reads content. Without
        // this it would be a way to read fragments outside the roots the endpoint is allowed to
        // serve - the guard the rest of the export already applies, applied here too.
        if (isAllowed != null && !isAllowed.test(path)) {
            LOG.warn("Refusing products path '{}' - outside the exporter's allowed roots", path);
            return none();
        }

        return new GraphQlQuery(configName.trim(), queryName.trim(), path.trim());
    }

    /** Request value if present, otherwise the authored one. */
    private static String pick(final String fromRequest, final String fromPlaceholders) {
        return StringUtils.isNotBlank(fromRequest) ? fromRequest : fromPlaceholders;
    }

    boolean isConfigured() {
        return StringUtils.isNotBlank(configName) && StringUtils.isNotBlank(queryName)
                && StringUtils.isNotBlank(path);
    }

    String getConfigName() {
        return configName;
    }

    String getQueryName() {
        return queryName;
    }

    /** The folder the query searches. Guaranteed to be inside the exporter's allowed roots. */
    String getPath() {
        return path;
    }

    @Override
    public String toString() {
        return isConfigured() ? configName + "/" + queryName + " over " + path : "none";
    }
}

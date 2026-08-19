package com.dept.contentbridge.core.services.impl;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.commons.lang3.StringUtils;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.day.cq.wcm.api.Page;
import com.dept.contentbridge.core.services.GraphQLSiteConfig;

/**
 * Builds a site's product list from its Content Fragments.
 *
 * <p>Every fragment under the site's DAM tree is inspected: when its {@code cq:model} is one a
 * {@link ProductMappings product rule} claims, the fragment's elements are projected onto the shared
 * product shape and added to the list. Fragments of any other model - brand themes, CTAs, mobile
 * configuration - are walked through, not exported, so one folder can hold products and non-products
 * side by side.</p>
 *
 * <p>Discovery is by model rather than by a configured path so a new product model needs only a rule
 * file, and so the caller never has to know where in the DAM tree a site keeps its catalogue.</p>
 */
final class ProductCatalog {

    private static final Logger LOG = LoggerFactory.getLogger(ProductCatalog.class);

    private static final String CONTENT_ROOT = "/content/";
    private static final String DAM_ROOT = "/content/dam/";

    /**
     * Placeholder entry naming the Content Fragment folder to scan for products. When present, wins
     * over the DAM-root fallback so a site whose fragments live outside {@code /content/dam/<site>}
     * (e.g. Luma stores products under {@code /content/dam/luma3/en/fragments}) still resolves.
     */
    private static final String PN_CF_FOLDER = "cf-folder-path";

    /** Subfolder names skipped during the scan - non-product fragments that would just waste budget. */
    private static final Set<String> EXCLUDED_FOLDERS = Set.of("brand-themes", "dynamic-media-template");

    /** Node visit budget, so a pathological DAM tree cannot stall the export. */
    private static final int MAX_NODES_SCANNED = 5000;

    private final ProductMappings mappings;

    ProductCatalog(final ProductMappings mappings) {
        this.mappings = mappings == null ? ProductMappings.EMPTY : mappings;
    }

    /**
     * @param site      any page in the site being exported
     * @param assetHost publish host to prefix repository paths with, may be blank
     * @return the site's products in document order, or an empty list when it has none
     */
    List<Map<String, Object>> export(final Page site, final String assetHost) {
        return export(site, assetHost, null, null);
    }

    /**
     * When {@code graphqlConfig} and {@code graphqlClient} are supplied, the site's persisted
     * GraphQL query is asked first - one call the CF authors control, versus a walk of every
     * fragment under the DAM root. The JCR walk fires as fallback for sites without a GraphQL
     * config or when the query returned nothing.
     */
    List<Map<String, Object>> export(final Page site, final String assetHost,
                                     final GraphQLSiteConfig graphqlConfig,
                                     final GraphQLProductClient graphqlClient) {
        final List<Map<String, Object>> viaGraphql = fetchViaGraphql(site, assetHost, graphqlConfig, graphqlClient);
        if (!viaGraphql.isEmpty()) {
            return viaGraphql;
        }
        final List<Map<String, Object>> products = new ArrayList<>();
        final Resource root = productsRoot(site);
        if (root != null) {
            collect(root, products, assetHost, new int[] {0});
        }
        return products;
    }

    private List<Map<String, Object>> fetchViaGraphql(final Page site, final String assetHost,
                                                      final GraphQLSiteConfig graphqlConfig,
                                                      final GraphQLProductClient graphqlClient) {
        if (graphqlConfig == null || graphqlClient == null || site == null) {
            return Collections.emptyList();
        }
        final Resource siteContent = site.getContentResource();
        if (siteContent == null) {
            return Collections.emptyList();
        }
        final ResourceResolver resolver = siteContent.getResourceResolver();
        final String path = SiteHostResolver.placeholder(site, PN_CF_FOLDER);
        final List<Map<String, Object>> raw = graphqlClient.fetch(graphqlConfig,
                StringUtils.defaultIfBlank(path, graphqlConfig.getDefaultPath()), resolver);
        if (raw.isEmpty()) {
            return raw;
        }
        // Prefix DAM references so the client gets absolute publish URLs, same as the JCR walk.
        final List<Map<String, Object>> out = new ArrayList<>(raw.size());
        for (final Map<String, Object> product : raw) {
            final Map<String, Object> copy = new LinkedHashMap<>(product);
            for (final Map.Entry<String, Object> entry : copy.entrySet()) {
                if (entry.getValue() instanceof String) {
                    entry.setValue(ContentPaths.prefix((String) entry.getValue(), assetHost));
                }
            }
            out.add(copy);
        }
        return out;
    }

    /**
     * Where the product scan starts. Prefers the site's {@code cf-folder-path} placeholder, which lets
     * a site point at a fragment folder outside its own {@code /content/dam/<site>} tree. Falls back
     * to that DAM root when no placeholder is set, so sites that follow the convention keep working.
     */
    private Resource productsRoot(final Page site) {
        final String path = site.getPath();
        if (path == null || !path.startsWith(CONTENT_ROOT)) {
            return null;
        }
        final ResourceResolver resolver = site.getContentResource().getResourceResolver();

        final String configured = StringUtils.trimToNull(SiteHostResolver.placeholder(site, PN_CF_FOLDER));
        if (configured != null) {
            final Resource root = resolver.getResource(configured);
            if (root != null) {
                return root;
            }
            LOG.warn("Placeholder '{}' on site {} points at {} which does not resolve - falling back to DAM root",
                    PN_CF_FOLDER, path, configured);
        }

        final String siteName = path.substring(CONTENT_ROOT.length()).split("/", 2)[0];
        if (siteName.isEmpty()) {
            return null;
        }
        return resolver.getResource(DAM_ROOT + siteName);
    }

    /** Depth-first so fragments organised into sub-folders (accounts, loans, ...) are still found. */
    private void collect(final Resource folder, final List<Map<String, Object>> sink,
                         final String assetHost, final int[] budget) {
        for (final Resource child : folder.getChildren()) {
            if (budget[0]++ >= MAX_NODES_SCANNED) {
                LOG.warn("Product scan hit the {}-node budget under {} - some fragments may be skipped",
                        MAX_NODES_SCANNED, folder.getPath());
                return;
            }
            if (EXCLUDED_FOLDERS.contains(child.getName())) {
                continue;
            }
            final ComponentMapping mapping = mappings.find(modelOf(child));
            if (mapping != null) {
                final Map<String, String> elements = ContentFragmentReader.readElements(child);
                if (elements != null) {
                    final Map<String, Object> product = toProduct(mapping, elements, assetHost);
                    if (!product.isEmpty()) {
                        sink.add(product);
                    }
                }
                // A recognised fragment has no product children to descend into.
                continue;
            }
            if (child.hasChildren()) {
                collect(child, sink, assetHost, budget);
            }
        }
    }

    /** @return the fragment's {@code cq:model} path, or null when the resource is not a fragment. */
    private String modelOf(final Resource resource) {
        final Resource data = resource.getChild("jcr:content/data");
        return data == null ? null : data.getValueMap().get("cq:model", String.class);
    }

    private Map<String, Object> toProduct(final ComponentMapping mapping,
                                          final Map<String, String> elements, final String assetHost) {
        final Map<String, Object> product = mapping.apply(new LinkedHashMap<>(elements),
                Collections.emptyMap());
        for (final Map.Entry<String, Object> entry : product.entrySet()) {
            if (entry.getValue() instanceof String) {
                entry.setValue(ContentPaths.prefix((String) entry.getValue(), assetHost));
            }
        }
        return product;
    }
}

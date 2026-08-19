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
    private static final int MAX_NODES_SCANNED = 20000;

    /** Marks a rendered image asset - has jcr:content/renditions we must NOT walk into. */
    private static final String DAM_ASSET = "dam:Asset";

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
        final List<Map<String, Object>> products = new ArrayList<>();
        final String sitePath = site.getPath();
        final Resource root = productsRoot(site);
        if (root == null) {
            LOG.info("ContentBridge product scan: site={} FOLDER=null (placeholder + DAM fallback both unresolved) - 0 products",
                    sitePath);
            return products;
        }
        final int[] budget = {0};
        collect(root, products, assetHost, budget);
        LOG.info("ContentBridge product scan: site={} folder={} nodesVisited={} knownModels={} productsEmitted={}",
                sitePath, root.getPath(), budget[0], mappings.knownModelCount(), products.size());
        return products;
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
        LOG.info("ContentBridge product scan: site={} placeholder[{}]={}", path, PN_CF_FOLDER, configured);
        if (configured != null) {
            final Resource root = resolver.getResource(configured);
            if (root != null) {
                LOG.info("ContentBridge product scan: site={} using placeholder folder {}", path, configured);
                return root;
            }
            LOG.warn("ContentBridge product scan: placeholder '{}' on site {} points at {} which does not resolve - falling back to DAM root",
                    PN_CF_FOLDER, path, configured);
        }

        final String siteName = path.substring(CONTENT_ROOT.length()).split("/", 2)[0];
        if (siteName.isEmpty()) {
            LOG.info("ContentBridge product scan: site={} has no site segment - skipping", path);
            return null;
        }
        final String fallback = DAM_ROOT + siteName;
        final Resource root = resolver.getResource(fallback);
        LOG.info("ContentBridge product scan: site={} fallback DAM path={} resolved={}", path, fallback, root != null);
        return root;
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
                LOG.debug("ContentBridge product scan: skip excluded folder {}", child.getPath());
                continue;
            }
            final String model = modelOf(child);
            final ComponentMapping mapping = mappings.find(model);
            if (mapping != null) {
                final Map<String, String> elements = ContentFragmentReader.readElements(child);
                if (elements == null) {
                    LOG.debug("ContentBridge product scan: CF {} model={} elements=null - skipped", child.getPath(), model);
                } else {
                    final Map<String, Object> product = toProduct(mapping, elements, assetHost);
                    if (product.isEmpty()) {
                        LOG.debug("ContentBridge product scan: CF {} model={} mapped to empty - dropped", child.getPath(), model);
                    } else {
                        LOG.debug("ContentBridge product scan: CF {} model={} -> product OK", child.getPath(), model);
                        sink.add(product);
                    }
                }
                // A recognised fragment has no product children to descend into.
                continue;
            }
            if (model != null) {
                LOG.debug("ContentBridge product scan: CF {} model={} unmapped - no rule matches", child.getPath(), model);
                // A fragment we do not export still has no product children to descend into.
                continue;
            }
            // Plain DAM assets carry jcr:content/renditions with thousands of metadata sub-nodes
            // per asset. Recursing into them stalls the walk against MAX_NODES_SCANNED before we
            // ever reach a fragment folder, so we only descend into real folder nodes.
            if (isFolder(child) && child.hasChildren()) {
                collect(child, sink, assetHost, budget);
            }
        }
    }

    /** @return the fragment's {@code cq:model} path, or null when the resource is not a fragment. */
    private String modelOf(final Resource resource) {
        final Resource data = resource.getChild("jcr:content/data");
        return data == null ? null : data.getValueMap().get("cq:model", String.class);
    }

    /**
     * True when {@code resource} is a container the walker should recurse into. Anything but a
     * plain {@code dam:Asset} node is walked (folders under any primary type, nt:unstructured mock
     * nodes in tests, and so on). Assets are stopped at because their {@code jcr:content/renditions}
     * subtree carries hundreds of metadata sub-nodes per file, which stalls the walk against the
     * scan budget before it reaches any Content Fragment folder.
     */
    private static boolean isFolder(final Resource resource) {
        final String primaryType = resource.getValueMap().get("jcr:primaryType", String.class);
        return !DAM_ASSET.equals(primaryType);
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

package com.edsMobileApp.core.services.impl;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;

import com.day.cq.wcm.api.Page;

/**
 * Builds a site's product list from its Content Fragments.
 *
 * <p>Every fragment under each {@link ProductMappings#rootsForSite site-applicable root} is
 * inspected: when its {@code cq:model} is one a product rule claims for that site, the fragment's
 * elements are projected onto the shared product shape and added to the list. Fragments of any
 * other model - brand themes, CTAs, mobile configuration - are walked through, not exported, so
 * one folder can hold products and non-products side by side.</p>
 *
 * <p>Discovery is by {@code cq:model}, and the DAM roots to walk come from the rules themselves.
 * A rule can point at its own subtree via a {@code scope} field (Luma does, so its export walks
 * {@code /content/dam/luma3/en/fragments}); a rule that stays silent falls back to
 * {@code /content/dam/<siteName>}, which is what the walker did before rules could opt in.</p>
 *
 * <p>Two rules can legitimately hand back roots that overlap (an unscoped rule's fallback root is
 * a parent of another rule's explicit {@code scope}), so the same fragment can be reachable from
 * more than one root in one export. {@link #export} tracks every fragment path already turned into
 * a product across all roots, so it is still produced exactly once regardless of how many roots
 * reach it - the walk stays correct whether or not rules bother to declare {@code sites}/
 * {@code scope} at all.</p>
 */
final class ProductCatalog {

    /** Node visit budget per root, so a pathological DAM tree cannot stall the export. */
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
        if (site == null || site.getContentResource() == null) {
            return products;
        }
        final ResourceResolver resolver = site.getContentResource().getResourceResolver();
        final String sitePath = site.getPath();
        final Set<String> seenPaths = new HashSet<>();
        for (final String root : mappings.rootsForSite(sitePath)) {
            final Resource start = resolver.getResource(root);
            if (start == null) {
                continue;
            }
            final int[] budget = {0};
            collect(start, products, assetHost, budget, sitePath, seenPaths);
        }
        return products;
    }

    /** Depth-first so fragments organised into sub-folders (accounts, loans, ...) are still found. */
    private void collect(final Resource folder, final List<Map<String, Object>> sink,
                         final String assetHost, final int[] budget, final String sitePath,
                         final Set<String> seenPaths) {
        for (final Resource child : folder.getChildren()) {
            if (budget[0]++ >= MAX_NODES_SCANNED) {
                return;
            }
            final String model = modelOf(child);
            final ComponentMapping mapping = mappings.findForSite(model, sitePath);
            if (mapping != null) {
                // Two roots can overlap (see the class comment), so the same fragment path may be
                // offered again from a different root - skip it rather than double-count the product.
                if (!seenPaths.add(child.getPath())) {
                    continue;
                }
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
            if (model != null) {
                // A fragment we do not export - foreign model, or a rule that doesn't claim this
                // site - still has no product children to descend into.
                continue;
            }
            // Plain DAM assets carry jcr:content/renditions with thousands of metadata sub-nodes
            // per asset. Recursing into them stalls the walk against MAX_NODES_SCANNED before we
            // ever reach a fragment folder, so we only descend into real folder nodes.
            if (isFolder(child) && child.hasChildren()) {
                collect(child, sink, assetHost, budget, sitePath, seenPaths);
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

package com.dept.contentbridge.core.services.impl;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
        final List<Map<String, Object>> products = new ArrayList<>();
        final Resource damRoot = damRoot(site);
        if (damRoot != null) {
            collect(damRoot, products, assetHost, new int[] {0});
        }
        return products;
    }

    /** The site's DAM tree, {@code /content/dam/<site>}, or null when the page is not under a site. */
    private Resource damRoot(final Page site) {
        final String path = site.getPath();
        if (path == null || !path.startsWith(CONTENT_ROOT)) {
            return null;
        }
        final String siteName = path.substring(CONTENT_ROOT.length()).split("/", 2)[0];
        if (siteName.isEmpty()) {
            return null;
        }
        final ResourceResolver resolver = site.getContentResource().getResourceResolver();
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

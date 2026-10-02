package com.edsMobileApp.core.services.impl;

import org.apache.sling.api.resource.Resource;

/**
 * The bit {@link ProductCatalog} and {@link SiteBranding} both need when walking a site's DAM
 * subtree looking for a Content Fragment by {@code cq:model}: what counts as a folder worth
 * descending into, what a resource's model is, and how many nodes one walk may visit before giving
 * up. Each class still does its own walking loop - one collects every match across several roots
 * with cross-root dedup, the other stops at the first match under one root - only this shared,
 * stateless part of "is this a fragment, and can I recurse past it" is common to both.
 */
final class DamFragmentSearch {

    /** Node visit budget per walk, so a pathological DAM tree cannot stall an export. */
    static final int MAX_NODES_SCANNED = 20000;

    /** Marks a rendered image asset - has jcr:content/renditions we must NOT walk into. */
    private static final String DAM_ASSET = "dam:Asset";

    private DamFragmentSearch() {
        // static helpers only
    }

    /** @return the fragment's {@code cq:model} path, or null when the resource is not a fragment. */
    static String modelOf(final Resource resource) {
        final Resource data = resource.getChild("jcr:content/data");
        return data == null ? null : data.getValueMap().get("cq:model", String.class);
    }

    /**
     * True when {@code resource} is a container a walk should recurse into. Anything but a plain
     * {@code dam:Asset} node qualifies; assets are stopped at because their
     * {@code jcr:content/renditions} subtree carries hundreds of metadata sub-nodes per file, which
     * would stall the walk against {@link #MAX_NODES_SCANNED} before it ever reaches a fragment.
     */
    static boolean isFolder(final Resource resource) {
        final String primaryType = resource.getValueMap().get("jcr:primaryType", String.class);
        return !DAM_ASSET.equals(primaryType);
    }
}

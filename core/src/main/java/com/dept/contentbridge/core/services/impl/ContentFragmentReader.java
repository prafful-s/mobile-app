package com.dept.contentbridge.core.services.impl;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.sling.api.resource.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.adobe.cq.dam.cfm.ContentElement;
import com.adobe.cq.dam.cfm.ContentFragment;
import com.adobe.cq.dam.cfm.FragmentData;

/**
 * Reads a single Content Fragment's master variation as flat name/value pairs.
 *
 * <p>Extracted out of {@link ProductExporter} so a component mapping rule can dereference one
 * fragment (e.g. a {@code product-card} block's {@code contentFragmentFolder} reference) with the
 * exact same read strategy the product catalogue export already relies on, rather than a second,
 * subtly different implementation.</p>
 */
final class ContentFragmentReader {

    private static final Logger LOG = LoggerFactory.getLogger(ContentFragmentReader.class);

    private ContentFragmentReader() {
        // static helpers only
    }

    /**
     * Convenience for a caller that just wants "whatever this reference has, or nothing" - a broken
     * or missing reference costs a merge, not the export.
     *
     * @param resource the fragment's own resource (an {@code nt:file}-like DAM asset), or {@code null}
     * @return the element values, or an empty map when {@code resource} is null, not a fragment, or
     *         unreadable - never {@code null}
     */
    static Map<String, String> read(final Resource resource) {
        if (resource == null) {
            return java.util.Collections.emptyMap();
        }
        final Map<String, String> elements = readElements(resource);
        return elements == null ? java.util.Collections.emptyMap() : elements;
    }

    /**
     * The Content Fragment API is tried first, then the underlying {@code jcr:content/data/master}
     * node directly. The direct read is not just a safety net: the CF adapter is unavailable in some
     * contexts, and the node layout it sits on is stable, so falling back keeps the caller working
     * where {@code adaptTo} would return null.
     *
     * <p>Nullable rather than folded into {@link #read} because {@link ProductExporter} needs to tell
     * "not a fragment at all" (null - recurse into children, this might be a category folder) apart
     * from "a fragment with nothing on it" (empty map - a real, if empty, product).</p>
     *
     * @return the element values, or {@code null} when {@code resource} is not a fragment at all
     */
    static Map<String, String> readElements(final Resource resource) {
        final ContentFragment fragment = adaptQuietly(resource);
        if (fragment != null) {
            final Map<String, String> viaApi = readViaApi(fragment);
            if (!viaApi.isEmpty()) {
                return viaApi;
            }
            LOG.debug("Fragment {} exposed no elements via the API - reading its master node directly",
                    resource.getPath());
        }
        final Resource master = resource.getChild("jcr:content/data/master");
        if (master != null) {
            return readViaNode(master);
        }
        return fragment == null ? null : new LinkedHashMap<>();
    }

    /**
     * {@code adaptTo} is specified to return null when adaption fails, but the Content Fragment
     * adapter throws instead for an asset it considers malformed - a missing {@code metadata} node is
     * enough. One such asset would otherwise abort the caller entirely, so the throw is treated as
     * "not a fragment" and the node-level read takes over.
     */
    private static ContentFragment adaptQuietly(final Resource resource) {
        try {
            return resource.adaptTo(ContentFragment.class);
        } catch (final RuntimeException e) {
            LOG.debug("Content Fragment adapter rejected {} - falling back to a direct node read",
                    resource.getPath(), e);
            return null;
        }
    }

    private static Map<String, String> readViaApi(final ContentFragment fragment) {
        final Map<String, String> values = new LinkedHashMap<>();
        final Iterator<ContentElement> iterator = fragment.getElements();

        while (iterator.hasNext()) {
            final ContentElement element = iterator.next();
            try {
                final FragmentData data = element.getValue();
                final Object value = data == null ? null : data.getValue();
                values.put(element.getName(), stringify(value));
            } catch (final RuntimeException e) {
                LOG.debug("Could not read element {} of {}", element.getName(), fragment.getName(), e);
            }
        }
        return values;
    }

    private static Map<String, String> readViaNode(final Resource master) {
        final Map<String, String> values = new LinkedHashMap<>();
        for (final Map.Entry<String, Object> entry : master.getValueMap().entrySet()) {
            if (entry.getKey().startsWith("jcr:") || entry.getKey().startsWith("cq:")) {
                continue;
            }
            values.put(entry.getKey(), stringify(entry.getValue()));
        }
        return values;
    }

    private static String stringify(final Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Object[]) {
            final Object[] array = (Object[]) value;
            final List<String> parts = new ArrayList<>(array.length);
            for (final Object item : array) {
                if (item != null) {
                    parts.add(String.valueOf(item));
                }
            }
            return String.join(",", parts);
        }
        return String.valueOf(value);
    }
}

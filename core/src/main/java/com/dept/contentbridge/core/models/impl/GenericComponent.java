package com.dept.contentbridge.core.models.impl;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.commons.lang3.StringUtils;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ValueMap;

import com.dept.contentbridge.core.schema.ContentBridgeComponent;
import com.dept.contentbridge.core.schema.SchemaConstants;
import com.dept.contentbridge.core.services.UrlService;

/**
 * Exports any authored resource without needing a Sling Model for it.
 *
 * <p>This is what makes the API work against an arbitrary site under {@code /content} rather than
 * only against resource types this project happens to know. A dedicated model still wins when one is
 * registered - that is how you curate the payload for components you care about - but everything
 * else degrades to a faithful, mechanical projection instead of vanishing from the response.</p>
 *
 * <p>Naming and field selection follow the conventions AEM already uses:</p>
 * <ul>
 *   <li><b>Component name</b> - the {@code model} property when present (Edge Delivery / Franklin),
 *       otherwise the last segment of the resource type, so
 *       {@code acme/components/content/teaser} becomes {@code teaser}.</li>
 *   <li><b>Content</b> - the fields named by {@code modelFields} when present, otherwise every
 *       property that is not JCR, Sling, CQ or Granite bookkeeping.</li>
 * </ul>
 *
 * <p>Not a Sling Model on purpose: registering one adapter for every resource type in the
 * repository would collide with genuinely registered models and with other bundles.</p>
 */
public final class GenericComponent implements ContentBridgeComponent {

    /** Identity and bookkeeping - never content. */
    private static final Set<String> IGNORED_PROPERTIES = Set.of(
            SchemaConstants.PN_MODEL,
            SchemaConstants.PN_MODEL_FIELDS,
            SchemaConstants.PN_FILTER,
            SchemaConstants.PN_NAME);

    private static final List<String> IGNORED_PREFIXES =
            List.of("jcr:", "sling:", "cq:", "mix:", "nt:", "granite:", "oak:", "rep:");

    private static final String DAM_PREFIX = "/content/dam/";

    private final Resource resource;
    private final UrlService urlService;

    public GenericComponent(final Resource resource, final UrlService urlService) {
        this.resource = resource;
        this.urlService = urlService;
    }

    @Override
    public String getId() {
        return AbstractComponentModel.generateId(resource.getPath());
    }

    @Override
    public String getComponentName() {
        final ValueMap properties = resource.getValueMap();

        final String model = properties.get(SchemaConstants.PN_MODEL, String.class);
        if (StringUtils.isNotBlank(model)) {
            return model;
        }
        final String resourceType = StringUtils.trimToEmpty(resource.getResourceType());
        final String lastSegment = StringUtils.substringAfterLast(resourceType, "/");

        return StringUtils.defaultIfBlank(lastSegment,
                StringUtils.defaultIfBlank(resourceType, resource.getName()));
    }

    @Override
    public Map<String, Object> getContent() {
        final ValueMap properties = resource.getValueMap();
        final Map<String, Object> content = new LinkedHashMap<>();

        for (final String field : contentFields(properties)) {
            final Object value = properties.get(field);
            if (value != null) {
                content.put(field, normalize(value));
            }
        }
        return content;
    }

    /**
     * Prefers an explicit {@code modelFields} declaration, which names the authored fields in
     * authoring order. Anything present but undeclared is appended rather than dropped, because that
     * list is not always kept in step with the content.
     */
    private Set<String> contentFields(final ValueMap properties) {
        final Set<String> fields = new LinkedHashSet<>();
        final String[] declared = properties.get(SchemaConstants.PN_MODEL_FIELDS, String[].class);

        if (declared != null) {
            for (final String field : declared) {
                if (properties.containsKey(field)) {
                    fields.add(field);
                }
            }
        }
        for (final String key : properties.keySet()) {
            if (isContentProperty(key)) {
                fields.add(key);
            }
        }
        return fields;
    }

    private boolean isContentProperty(final String key) {
        if (IGNORED_PROPERTIES.contains(key)) {
            return false;
        }
        for (final String prefix : IGNORED_PREFIXES) {
            if (key.startsWith(prefix)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Rewrites DAM references into absolute URLs. Keyed on the value rather than the property name,
     * because projects variously use {@code image}, {@code imageRef}, {@code fileReference} and
     * {@code assetPath} - and a generic exporter cannot enumerate them.
     */
    private Object normalize(final Object value) {
        if (value instanceof String) {
            return externalizeIfAsset((String) value);
        }
        if (value instanceof String[]) {
            final String[] source = (String[]) value;
            final String[] result = new String[source.length];
            for (int i = 0; i < source.length; i++) {
                result[i] = externalizeIfAsset(source[i]);
            }
            return Arrays.asList(result);
        }
        return value;
    }

    private String externalizeIfAsset(final String value) {
        if (value != null && value.startsWith(DAM_PREFIX) && urlService != null) {
            return urlService.externalize(value);
        }
        return value;
    }
}

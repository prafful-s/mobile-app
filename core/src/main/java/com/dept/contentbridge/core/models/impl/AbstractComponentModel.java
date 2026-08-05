package com.dept.contentbridge.core.models.impl;

import java.util.LinkedHashMap;
import java.util.Map;

import javax.annotation.PostConstruct;

import org.apache.commons.lang3.StringUtils;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ValueMap;
import org.apache.sling.models.annotations.injectorspecific.OSGiService;
import org.apache.sling.models.annotations.injectorspecific.SlingObject;

import com.dept.contentbridge.core.schema.ContentBridgeComponent;
import com.dept.contentbridge.core.services.UrlService;

/**
 * Shared plumbing for every ContentBridge component model: id generation, property access and
 * null-safe map building.
 */
public abstract class AbstractComponentModel implements ContentBridgeComponent {

    @SlingObject
    protected Resource resource;

    @OSGiService
    protected UrlService urlService;

    private String id;
    private ValueMap properties;

    @PostConstruct
    protected void init() {
        this.properties = resource.getValueMap();
        this.id = generateId(resource.getPath());
    }

    /**
     * Ids are derived from the resource path so they stay stable across exports as long as the
     * component is not moved, and they survive a package rebuild - unlike a random UUID.
     *
     * <p>Public because the exporter generates ids for synthesised components - a panel derived from
     * an AEM section has no model of its own, but still needs an id from the same scheme, or two
     * components in one response would be numbered by different rules.</p>
     */
    public static String generateId(final String path) {
        return Integer.toHexString(path.hashCode());
    }

    @Override
    public String getId() {
        return id;
    }

    @Override
    public Map<String, Object> getContent() {
        final ContentBuilder builder = new ContentBuilder();
        buildContent(builder);
        return builder.build();
    }

    /**
     * Subclasses add their payload here. Blank values added through the builder are dropped.
     */
    protected abstract void buildContent(ContentBuilder builder);

    protected String prop(final String name) {
        return properties.get(name, String.class);
    }

    protected String prop(final String name, final String defaultValue) {
        return StringUtils.defaultIfBlank(properties.get(name, String.class), defaultValue);
    }

    protected boolean bool(final String name, final boolean defaultValue) {
        return properties.get(name, defaultValue);
    }

    protected String asset(final String name) {
        return externalize(prop(name));
    }

    protected String externalize(final String value) {
        return urlService == null ? value : urlService.externalize(value);
    }

    /**
     * Small insertion-ordered map builder that silently skips blank values, which is what keeps the
     * exported JSON free of {@code "title": ""} noise.
     */
    protected static final class ContentBuilder {

        private final Map<String, Object> map = new LinkedHashMap<>();

        ContentBuilder put(final String key, final String value) {
            if (StringUtils.isNotBlank(value)) {
                map.put(key, value);
            }
            return this;
        }

        ContentBuilder put(final String key, final Object value) {
            if (value != null) {
                map.put(key, value);
            }
            return this;
        }

        /** Booleans are emitted as strings to match the published contract. */
        ContentBuilder putFlag(final String key, final boolean value) {
            map.put(key, Boolean.toString(value));
            return this;
        }

        Map<String, Object> build() {
            return map;
        }
    }
}

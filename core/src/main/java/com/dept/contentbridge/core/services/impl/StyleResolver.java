package com.dept.contentbridge.core.services.impl;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ValueMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Turns the style <em>tokens</em> AEM authors choose into the concrete values a client renders.
 *
 * <p>Authors do not set pixels. They pick {@code sec-spacing="section-large"} or
 * {@code style="bg-default"} from a dropdown, and the site's CSS decides what that means. A consuming
 * app has no CSS, so the token has to be resolved here - and only the design system knows the real
 * numbers, which is why the table is OSGi-configurable rather than compiled in.</p>
 *
 * <pre>
 * section-large=padding:24
 * bg-default=backgroundColor:#F5F8FA
 * </pre>
 *
 * <p>An unmapped token is dropped, not guessed at: emitting an invented pixel value would look
 * authoritative and be wrong. Enable DEBUG to see which tokens are going unmapped.</p>
 */
final class StyleResolver {

    private static final Logger LOG = LoggerFactory.getLogger(StyleResolver.class);

    /** Properties whose value is a style token rather than content. */
    private static final List<String> TOKEN_PROPERTIES = List.of(
            "sec-spacing", "sec-spacing-bottom", "sec-alignment", "sec-color",
            "sec-custom-styles", "sec-full-width", "style");

    private final Map<String, Map<String, Object>> tokens;

    StyleResolver(final String[] configuredTokens) {
        this.tokens = parse(configuredTokens);
    }

    /**
     * @param resource a layout container
     * @return the resolved styles, in token order; empty when nothing is mapped
     */
    Map<String, Object> stylesOf(final Resource resource) {
        final ValueMap properties = resource.getValueMap();
        final Map<String, Object> styles = new LinkedHashMap<>();

        for (final String property : TOKEN_PROPERTIES) {
            final String token = StringUtils.trimToNull(properties.get(property, String.class));
            if (token == null) {
                continue;
            }
            final Map<String, Object> resolved = tokens.get(token);
            if (resolved == null) {
                LOG.debug("Style token '{}' on {} has no mapping - dropped", token, resource.getPath());
                continue;
            }
            styles.putAll(resolved);
        }
        return styles;
    }

    /**
     * @param configured entries of the form {@code token=key:value}, or
     *                   {@code token=key:value;key2:value2} for a token setting several properties
     */
    private static Map<String, Map<String, Object>> parse(final String[] configured) {
        final Map<String, Map<String, Object>> parsed = new LinkedHashMap<>();
        if (configured == null) {
            return parsed;
        }
        for (final String entry : configured) {
            final String token = StringUtils.trimToEmpty(StringUtils.substringBefore(entry, "="));
            final String declarations = StringUtils.substringAfter(entry, "=");
            if (StringUtils.isEmpty(token) || StringUtils.isBlank(declarations)) {
                continue;
            }
            final Map<String, Object> values = new LinkedHashMap<>();
            for (final String declaration : StringUtils.split(declarations, ';')) {
                final String key = StringUtils.trimToEmpty(StringUtils.substringBefore(declaration, ":"));
                final String value = StringUtils.trimToEmpty(StringUtils.substringAfter(declaration, ":"));
                if (StringUtils.isNotEmpty(key) && StringUtils.isNotEmpty(value)) {
                    values.put(key, coerce(value));
                }
            }
            if (!values.isEmpty()) {
                parsed.put(token, values);
            }
        }
        return parsed;
    }

    /** Numbers are emitted as numbers - a client doing arithmetic on "24" would be surprised. */
    private static Object coerce(final String value) {
        if (StringUtils.isNumeric(value)) {
            return Long.valueOf(value);
        }
        if ("true".equalsIgnoreCase(value) || "false".equalsIgnoreCase(value)) {
            return Boolean.valueOf(value);
        }
        return value;
    }
}

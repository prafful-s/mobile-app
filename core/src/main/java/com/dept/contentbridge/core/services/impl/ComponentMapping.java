package com.dept.contentbridge.core.services.impl;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;

/**
 * One rule file: which authored components it applies to, what the target calls them, and how each
 * target field is derived.
 *
 * <p>Matching is on component <em>name</em> rather than resource type. That name is what
 * {@code GenericComponent} already derives - the Edge Delivery {@code model} property when present,
 * otherwise the last segment of the resource type - so one rule covers a Franklin {@code hero} and a
 * classic {@code acme/components/content/hero} without either being named in the rule.</p>
 *
 * <p>Immutable once loaded, so a single instance is shared across every request.</p>
 */
final class ComponentMapping {

    private final List<String> matches;
    private final String component;
    private final List<FieldRule> fields;

    private ComponentMapping(final List<String> matches, final String component,
                             final List<FieldRule> fields) {
        this.matches = matches;
        this.component = component;
        this.fields = fields;
    }

    /**
     * @param definition a parsed rule file
     * @return the mapping, or {@code null} when it names no target component or matches nothing
     */
    static ComponentMapping of(final Map<String, Object> definition) {
        final String component = trimmed(definition.get("component"));
        final List<String> matches = names(definition.get("match"));
        if (StringUtils.isBlank(component) || matches.isEmpty()) {
            return null;
        }
        final List<FieldRule> fields = new ArrayList<>();
        final Object declared = definition.get("fields");
        if (declared instanceof List) {
            for (final Object entry : (List<?>) declared) {
                if (entry instanceof Map) {
                    @SuppressWarnings("unchecked")
                    final FieldRule rule = FieldRule.of((Map<String, Object>) entry);
                    if (rule != null) {
                        fields.add(rule);
                    }
                }
            }
        }
        return new ComponentMapping(Collections.unmodifiableList(matches), component,
                Collections.unmodifiableList(fields));
    }

    /** The authored component names this rule claims. */
    List<String> getMatches() {
        return matches;
    }

    /** What the target vocabulary calls the result - {@code banner}, {@code card}, and so on. */
    String getComponent() {
        return component;
    }

    /**
     * Projects authored properties onto the target field set.
     *
     * <p>Only declared fields survive. That is the point of the rule: the target contract is a fixed
     * shape, and passing through whatever else the author happened to set would put site-specific
     * properties into a payload the client parses strictly.</p>
     *
     * <p>Returns nothing at all when no field read a real authored value. A rule carries constants
     * and defaults, so a component with none of the properties the rule looks for would otherwise
     * still produce a plausible-looking shell - a banner with an alignment and a button style and no
     * title. That is worse than leaving the component alone, because it looks like content.</p>
     *
     * @param source the component's authored properties
     * @return the target payload in the order the rule declares, or an empty map when the rule
     *         found nothing the author had set
     */
    Map<String, Object> apply(final Map<String, Object> source) {
        final Map<String, Object> target = new LinkedHashMap<>();
        boolean anyAuthored = false;

        for (final FieldRule field : fields) {
            Object value = field.fromSource(source);
            if (isPresent(value)) {
                anyAuthored = true;
            } else {
                value = field.fallback();
            }
            if (isPresent(value)) {
                target.put(field.getTarget(), value);
            }
        }
        return anyAuthored ? target : Collections.emptyMap();
    }

    private static boolean isPresent(final Object value) {
        return value != null && !(value instanceof String && StringUtils.isBlank((String) value));
    }

    private static String trimmed(final Object value) {
        return value instanceof String ? StringUtils.trimToNull((String) value) : null;
    }

    /** {@code match} accepts a single name or a list, because most rules only need one. */
    private static List<String> names(final Object value) {
        final List<String> names = new ArrayList<>();
        if (value instanceof String) {
            final String single = trimmed(value);
            if (single != null) {
                names.add(single);
            }
        } else if (value instanceof List) {
            for (final Object item : (List<?>) value) {
                final String name = trimmed(item);
                if (name != null) {
                    names.add(name);
                }
            }
        }
        return names;
    }
}

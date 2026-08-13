package com.dept.contentbridge.core.services.impl;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
    private final List<FieldRule> styleFields;
    private final boolean skeleton;
    private final String contentFragmentRef;

    private ComponentMapping(final List<String> matches, final String component,
                             final List<FieldRule> fields, final List<FieldRule> styleFields,
                             final boolean skeleton, final String contentFragmentRef) {
        this.matches = matches;
        this.component = component;
        this.fields = fields;
        this.styleFields = styleFields;
        this.skeleton = skeleton;
        this.contentFragmentRef = contentFragmentRef;
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
        final List<FieldRule> fields = fieldRules(definition.get("fields"));
        final List<FieldRule> styleFields = fieldRules(definition.get("styles"));
        final boolean skeleton = Boolean.TRUE.equals(definition.get("skeleton"));
        final String contentFragmentRef = trimmed(definition.get("contentFragmentRef"));
        return new ComponentMapping(Collections.unmodifiableList(matches), component,
                Collections.unmodifiableList(fields), Collections.unmodifiableList(styleFields),
                skeleton, contentFragmentRef);
    }

    /** Parses either a rule file's {@code fields} or its optional {@code styles} - same shape. */
    private static List<FieldRule> fieldRules(final Object declared) {
        final List<FieldRule> rules = new ArrayList<>();
        if (declared instanceof List) {
            for (final Object entry : (List<?>) declared) {
                if (entry instanceof Map) {
                    @SuppressWarnings("unchecked")
                    final FieldRule rule = FieldRule.of((Map<String, Object>) entry);
                    if (rule != null) {
                        rules.add(rule);
                    }
                }
            }
        }
        return rules;
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
     * True when this rule opts in to fixed-shape output: every declared field is emitted always,
     * typed to an empty default when unauthored (empty string, {@code 0}, or {@code false} - see
     * {@link FieldRule#emptyDefault()}), and the walker appends any authored CRX property this
     * rule did not consume. Rules without the flag behave as they did before - only
     * authored/defaulted keys, empty projection abandoned.
     */
    boolean isSkeleton() {
        return skeleton;
    }

    /**
     * The authored property naming a Content Fragment to dereference before this rule's fields
     * resolve, or {@code null} when the rule reads only the component's own properties.
     *
     * <p>Some blocks (a {@code product-card}) carry nothing of their own beyond a reference to a
     * Content Fragment - the actual title, image and description live on the fragment, not the
     * component. {@link ComponentTreeWalker} reads this property off the component, resolves it to
     * a resource, and merges the fragment's elements into the source map before calling
     * {@link #apply} - the component's own properties still win on a name clash.</p>
     */
    String getContentFragmentRef() {
        return contentFragmentRef;
    }

    /**
     * Every CRX property this rule reads from, across every field. Used by the walker to
     * filter extras passthrough - a property this rule consumed must not reappear as an extra.
     */
    Set<String> consumedProperties() {
        final Set<String> consumed = new LinkedHashSet<>();
        for (final FieldRule field : fields) {
            consumed.addAll(field.consumedProperties());
        }
        for (final FieldRule field : styleFields) {
            consumed.addAll(field.consumedProperties());
        }
        if (contentFragmentRef != null) {
            // Drives the dereference below, not itself a piece of content - it should not also leak
            // into extras passthrough as a raw DAM path.
            consumed.add(contentFragmentRef);
        }
        return consumed;
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
     * @param source       the component's own authored properties
     * @param parentSource the nearest enclosing section's authored properties, for any field
     *                     declared {@code fromParent}; pass an empty map when there is none
     * @return the target payload in the order the rule declares, or an empty map when the rule
     *         found nothing the author had set
     */
    Map<String, Object> apply(final Map<String, Object> source, final Map<String, Object> parentSource) {
        final Map<String, Object> target = new LinkedHashMap<>();
        boolean anyAuthored = false;

        for (final FieldRule field : fields) {
            Object value = field.fromSource(source, parentSource);
            if (isPresent(value)) {
                anyAuthored = true;
            } else {
                value = field.fallback();
            }
            if (isPresent(value)) {
                putTarget(target, field.getTarget(), value);
            } else if (skeleton) {
                // Fixed-shape opt-in: emit the key anyway, typed to what the schema declared.
                // The client always sees the same key set, whatever the author left blank.
                putTarget(target, field.getTarget(), field.emptyDefault());
            }
        }
        if (skeleton) {
            return target;
        }
        return anyAuthored ? target : Collections.emptyMap();
    }

    /**
     * Places {@code value} in {@code target}, supporting dot-notation targets that nest a value
     * inside a keyed sub-object.
     *
     * <p>{@code "rightIcon.icon"} produces {@code { "rightIcon": { "icon": value } }} - and a
     * second rule targeting {@code "rightIcon.name"} extends that same sub-object rather than
     * overwriting it. That is what lets a rule build a target like
     * {@code { "rightIcon": { "icon": "search-icon", "name": "Search", "target": "" } }} out of a
     * flat authored property set.</p>
     *
     * <p>Only one level of nesting is supported. A rule needing deeper structure would be reading a
     * flat authored bag the wrong way round.</p>
     */
    private static void putTarget(final Map<String, Object> target, final String path,
                                  final Object value) {
        final int dot = path.indexOf('.');
        if (dot < 0) {
            target.put(path, value);
            return;
        }
        final String head = path.substring(0, dot);
        final String tail = path.substring(dot + 1);
        final Object existing = target.get(head);
        @SuppressWarnings("unchecked")
        final Map<String, Object> nested = existing instanceof Map
                ? (Map<String, Object>) existing : new LinkedHashMap<>();
        nested.put(tail, value);
        target.put(head, nested);
    }

    /**
     * Projects authored properties onto the rule's declared {@code styles}, the sibling of
     * {@code content} a handful of components carry.
     *
     * <p>Unlike {@link #apply}, not gated on anything being authored: a component this rule already
     * claimed (its content mapping succeeded) still gets its declared style defaults even where no
     * style property was actually set, the same way individual {@code fields} entries already
     * default-fill an unauthored key.</p>
     *
     * @param source       the component's own authored properties
     * @param parentSource the nearest enclosing section's authored properties, for any field
     *                     declared {@code fromParent}; pass an empty map when there is none
     * @return the target styles in the order the rule declares, or an empty map when the rule
     *         declares no {@code styles} at all
     */
    Map<String, Object> applyStyles(final Map<String, Object> source, final Map<String, Object> parentSource) {
        final Map<String, Object> target = new LinkedHashMap<>();
        for (final FieldRule field : styleFields) {
            Object value = field.fromSource(source, parentSource);
            if (!isPresent(value)) {
                value = field.fallback();
            }
            if (isPresent(value)) {
                putTarget(target, field.getTarget(), value);
            } else if (skeleton) {
                // Fixed-shape styles: emit every declared key so the reference contract's
                // styles keyset is always present, typed to what the schema declared.
                putTarget(target, field.getTarget(), field.emptyDefault());
            }
        }
        return target;
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

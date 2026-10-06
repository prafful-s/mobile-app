package com.edsMobileApp.core.services.impl;

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
    private final ContentFragmentFolderRef contentFragmentFolderRef;
    private final String requiresField;
    private final ComponentMapping fallback;
    private final List<Variant> variants;
    private final LinkQuery linkQuery;
    private final Set<String> ignoreExtras;

    private ComponentMapping(final List<String> matches, final String component,
                             final List<FieldRule> fields, final List<FieldRule> styleFields,
                             final boolean skeleton, final String contentFragmentRef,
                             final ContentFragmentFolderRef contentFragmentFolderRef,
                             final String requiresField, final ComponentMapping fallback,
                             final List<Variant> variants, final LinkQuery linkQuery,
                             final Set<String> ignoreExtras) {
        this.matches = matches;
        this.component = component;
        this.fields = fields;
        this.styleFields = styleFields;
        this.skeleton = skeleton;
        this.contentFragmentRef = contentFragmentRef;
        this.contentFragmentFolderRef = contentFragmentFolderRef;
        this.requiresField = requiresField;
        this.fallback = fallback;
        this.variants = variants;
        this.linkQuery = linkQuery;
        this.ignoreExtras = ignoreExtras;
    }

    /** A {@code fallback} mapping: no {@code requires}, no further fallback, no variants/linkQuery/ignoreExtras. */
    private ComponentMapping(final List<String> matches, final String component,
                             final List<FieldRule> fields, final List<FieldRule> styleFields,
                             final boolean skeleton, final String contentFragmentRef,
                             final ContentFragmentFolderRef contentFragmentFolderRef) {
        this(matches, component, fields, styleFields, skeleton, contentFragmentRef,
                contentFragmentFolderRef, null, null, Collections.emptyList(), null, Collections.emptySet());
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
        final ContentFragmentFolderRef contentFragmentFolderRef =
                ContentFragmentFolderRef.of(definition.get("contentFragmentFolderRef"));
        final String requiresField = trimmed(definition.get("requires"));
        final ComponentMapping fallback = fallbackOf(definition.get("fallback"), matches);
        final List<Variant> variants = variants(definition.get("variants"));
        final LinkQuery linkQuery = LinkQuery.of(definition.get("linkQuery"));
        final Set<String> ignoreExtras = new LinkedHashSet<>(names(definition.get("ignoreExtras")));
        return new ComponentMapping(Collections.unmodifiableList(matches), component,
                Collections.unmodifiableList(fields), Collections.unmodifiableList(styleFields),
                skeleton, contentFragmentRef, contentFragmentFolderRef, requiresField, fallback,
                variants, linkQuery, Collections.unmodifiableSet(ignoreExtras));
    }

    /**
     * Builds the alternate mapping applied when {@code requires} is unmet - it shares the parent's
     * {@code match} names but has its own component and fields, and carries no further fallback.
     */
    private static ComponentMapping fallbackOf(final Object declared, final List<String> matches) {
        if (!(declared instanceof Map)) {
            return null;
        }
        @SuppressWarnings("unchecked")
        final Map<String, Object> map = (Map<String, Object>) declared;
        final String component = trimmed(map.get("component"));
        if (component == null) {
            return null;
        }
        return new ComponentMapping(Collections.unmodifiableList(matches), component,
                Collections.unmodifiableList(fieldRules(map.get("fields"))),
                Collections.unmodifiableList(fieldRules(map.get("styles"))),
                Boolean.TRUE.equals(map.get("skeleton")), trimmed(map.get("contentFragmentRef")),
                ContentFragmentFolderRef.of(map.get("contentFragmentFolderRef")));
    }

    /**
     * The mapping that actually applies to {@code source}: this rule when its {@code requires}
     * field is authored (or none is declared), otherwise its {@code fallback} - a card with no
     * image, for instance, is a rich-text block, not an empty card.
     */
    ComponentMapping effectiveFor(final Map<String, Object> source) {
        if (requiresField == null || fallback == null) {
            return this;
        }
        final Object value = source.get(requiresField);
        final boolean present = value != null
                && !(value instanceof String && StringUtils.isBlank((String) value));
        return present ? this : fallback;
    }

    /**
     * The name of another rule this component should be handled by, chosen from {@code variants} on
     * an authored value (a hero with {@code herolayout=image-right} is handled by the card rule), or
     * {@code null} when no variant condition matches.
     */
    String variantRuleFor(final Map<String, Object> source) {
        for (final Variant variant : variants) {
            if (variant.matches(source)) {
                return variant.useRule;
            }
        }
        return null;
    }

    /**
     * {@code source} with the matching variant's {@code defaults} laid underneath it: a default is
     * written only where the authored value is absent or blank, so an authored value always wins.
     * This lets a rule that defers to another say how that rule should read the component when the
     * author has not chosen - a hero laid out image-left is handled by the card rule, which then
     * reads it as {@code stacked} rather than the card rule's own {@code overlay}. Returns
     * {@code source} itself when no variant matches or the matching one declares no defaults.
     */
    Map<String, Object> withVariantDefaults(final Map<String, Object> source) {
        for (final Variant variant : variants) {
            if (!variant.matches(source)) {
                continue;
            }
            if (variant.defaults.isEmpty()) {
                return source;
            }
            final Map<String, Object> merged = new LinkedHashMap<>(source);
            for (final Map.Entry<String, Object> entry : variant.defaults.entrySet()) {
                if (!isPresent(merged.get(entry.getKey()))) {
                    merged.put(entry.getKey(), entry.getValue());
                }
            }
            return merged;
        }
        return source;
    }

    /** One {@code variants} entry: when an authored field equals a value, defer to another rule. */
    private static final class Variant {

        private final String field;
        private final String equals;
        private final String useRule;
        private final Map<String, Object> defaults;

        private Variant(final String field, final String equals, final String useRule,
                         final Map<String, Object> defaults) {
            this.field = field;
            this.equals = equals;
            this.useRule = useRule;
            this.defaults = defaults;
        }

        static Variant of(final Object declared) {
            if (!(declared instanceof Map)) {
                return null;
            }
            @SuppressWarnings("unchecked")
            final Map<String, Object> map = (Map<String, Object>) declared;
            final Object when = map.get("when");
            final String useRule = trimmed(map.get("useRule"));
            if (!(when instanceof Map) || useRule == null) {
                return null;
            }
            @SuppressWarnings("unchecked")
            final Map<String, Object> condition = (Map<String, Object>) when;
            final String field = trimmed(condition.get("field"));
            final String equals = trimmed(condition.get("equals"));
            if (field == null || equals == null) {
                return null;
            }
            final Object declaredDefaults = map.get("defaults");
            final Map<String, Object> defaults = new LinkedHashMap<>();
            if (declaredDefaults instanceof Map) {
                for (final Map.Entry<?, ?> entry : ((Map<?, ?>) declaredDefaults).entrySet()) {
                    if (entry.getKey() != null && entry.getValue() != null) {
                        defaults.put(String.valueOf(entry.getKey()), entry.getValue());
                    }
                }
            }
            return new Variant(field, equals, useRule, Collections.unmodifiableMap(defaults));
        }

        boolean matches(final Map<String, Object> source) {
            final Object value = source.get(field);
            return value != null && equals.equals(String.valueOf(value));
        }
    }

    private static List<Variant> variants(final Object declared) {
        if (!(declared instanceof List)) {
            return Collections.emptyList();
        }
        final List<Variant> parsed = new ArrayList<>();
        for (final Object entry : (List<?>) declared) {
            final Variant variant = Variant.of(entry);
            if (variant != null) {
                parsed.add(variant);
            }
        }
        return Collections.unmodifiableList(parsed);
    }

    /**
     * Declares a folder of Content Fragments to enumerate before the rule's fields resolve.
     *
     * <p>Where {@link #contentFragmentRef} points at a single fragment and merges its elements
     * into the source map, this points at a folder of fragments and collects one specified field
     * off each child into an array. That array is then exposed under {@code injectAs} for a
     * {@code from} field to project onto the target - the shape a reference contract's list
     * component wants ({@code items: [sku1, sku2, ...]}) built from a folder walk without the
     * rule engine growing a list-mapping vocabulary.</p>
     */
    static final class ContentFragmentFolderRef {

        private final String property;
        private final List<Collect> collects;

        private ContentFragmentFolderRef(final String property, final List<Collect> collects) {
            this.property = property;
            this.collects = collects;
        }

        static ContentFragmentFolderRef of(final Object definition) {
            if (!(definition instanceof Map)) {
                return null;
            }
            @SuppressWarnings("unchecked")
            final Map<String, Object> map = (Map<String, Object>) definition;
            final String property = trimmed(map.get("property"));
            if (property == null) {
                return null;
            }
            // Two shapes accepted, chosen by which key the rule uses:
            //   collect: [{fields:[...], injectAs:"...", keepBlank:true}, ...]  (multi-column)
            //   collectField + injectAs (+ keepBlank)                           (single column)
            final List<Collect> collects = new ArrayList<>();
            final Object multi = map.get("collect");
            if (multi instanceof List) {
                for (final Object entry : (List<?>) multi) {
                    final Collect c = Collect.of(entry);
                    if (c != null) {
                        collects.add(c);
                    }
                }
            } else {
                final Collect single = Collect.ofFlat(map);
                if (single != null) {
                    collects.add(single);
                }
            }
            if (collects.isEmpty()) {
                return null;
            }
            return new ContentFragmentFolderRef(property, Collections.unmodifiableList(collects));
        }

        String getProperty() { return property; }
        List<Collect> getCollects() { return collects; }

        /**
         * One (fields, injectAs, keepBlank) tuple - the folder-ref applies each in turn per fragment.
         */
        static final class Collect {

            private final List<String> fields;
            private final String injectAs;
            private final boolean keepBlank;

            private Collect(final List<String> fields, final String injectAs, final boolean keepBlank) {
                this.fields = fields;
                this.injectAs = injectAs;
                this.keepBlank = keepBlank;
            }

            static Collect of(final Object definition) {
                if (!(definition instanceof Map)) {
                    return null;
                }
                @SuppressWarnings("unchecked")
                final Map<String, Object> map = (Map<String, Object>) definition;
                return fromMap(map, names(map.get("fields")));
            }

            /** Parses the flat legacy shape - collectField + injectAs on the parent object. */
            static Collect ofFlat(final Map<String, Object> map) {
                return fromMap(map, names(map.get("collectField")));
            }

            private static Collect fromMap(final Map<String, Object> map, final List<String> fields) {
                final String injectAs = trimmed(map.get("injectAs"));
                if (fields.isEmpty() || injectAs == null) {
                    return null;
                }
                return new Collect(Collections.unmodifiableList(fields), injectAs,
                        Boolean.TRUE.equals(map.get("keepBlank")));
            }

            List<String> getFields() { return fields; }
            String getInjectAs() { return injectAs; }
            boolean keepBlank() { return keepBlank; }
        }
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

    /** @see ContentFragmentFolderRef */
    ContentFragmentFolderRef getContentFragmentFolderRef() {
        return contentFragmentFolderRef;
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
        if (contentFragmentFolderRef != null) {
            consumed.add(contentFragmentFolderRef.getProperty());
        }
        consumed.addAll(ignoreExtras);
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
            final Object authored = field.fromSource(source, parentSource);
            if (isPresent(authored)) {
                anyAuthored = true;
            }
            final Object value = isPresent(authored) ? authored : field.fallback();
            resolveInto(target, field, isPresent(value) ? screenTarget(field.getTarget(), value, source) : value);
        }
        if (skeleton) {
            return target;
        }
        return anyAuthored ? target : Collections.emptyMap();
    }

    /**
     * Resolves one field's value - authored, or its fallback - and places it in {@code target}, or
     * (under skeleton mode) its typed empty default when there was nothing to place. Shared by
     * {@link #apply} and {@link #applyStyles}.
     */
    private void resolveInto(final Map<String, Object> target, final FieldRule field, final Object value) {
        if (isPresent(value)) {
            putTarget(target, field.getTarget(), value);
        } else if (skeleton) {
            // Fixed-shape opt-in: emit the key anyway, typed to what the schema declared.
            // The client always sees the same key set, whatever the author left blank.
            putTarget(target, field.getTarget(), field.emptyDefault());
        }
    }

    /**
     * A {@code link} names a destination screen, so it carries that screen's key - the last path
     * segment, the same key the export files a page under, with a trailing {@code .html} dropped
     * (an authoring artefact, not part of the key) - not the whole authoring path. A value that is
     * not a {@code /content} path (a bare {@code #}, an anchor, {@code mailto:}, ...) is left as
     * authored.
     *
     * <p>Whatever query string or fragment the author put on the link (a product page link's
     * {@code ?productId=<sku>}, say) is carried through onto the leaf unchanged - it was authored,
     * not invented, so cutting it silently would lose real data. When the rule declares a
     * {@code linkQuery} instead, that takes over entirely: its own parameter is appended so a
     * product card's link carries the product it opens (e.g. {@code credit-card-basic?productId=…})
     * from an authored field merged in via {@code contentFragmentRef}, and whatever suffix the raw
     * href happened to have is dropped in favour of it.</p>
     */
    private Object screenTarget(final String targetKey, final Object value,
                                final Map<String, Object> source) {
        if ((!"link".equals(targetKey) && !"linkTo".equals(targetKey) && !"checkoutLink".equals(targetKey))
            || !(value instanceof String)) {
            return value;
        }
        final String text = ((String) value).trim();
        final int cut = StringUtils.indexOfAny(text, '?', '#');
        final String suffix = cut >= 0 ? text.substring(cut) : "";
        final String path = StringUtils.stripEnd(cut >= 0 ? text.substring(0, cut) : text, "/");
        if (path.indexOf('/') < 0) {
            return value;
        }
        final String leaf = StringUtils.removeEndIgnoreCase(StringUtils.substringAfterLast(path, "/"), ".html");
        if (leaf.isEmpty()) {
            return value;
        }
        return linkQuery == null ? leaf + suffix : linkQuery.appendedTo(leaf, source);
    }

    /** A query parameter appended to the {@code link}, sourced from another authored field. */
    private static final class LinkQuery {

        private final String param;
        private final String from;

        private LinkQuery(final String param, final String from) {
            this.param = param;
            this.from = from;
        }

        static LinkQuery of(final Object declared) {
            if (!(declared instanceof Map)) {
                return null;
            }
            @SuppressWarnings("unchecked")
            final Map<String, Object> map = (Map<String, Object>) declared;
            final String param = trimmed(map.get("param"));
            final String from = trimmed(map.get("from"));
            return param == null || from == null ? null : new LinkQuery(param, from);
        }

        String appendedTo(final String link, final Map<String, Object> source) {
            final Object value = source.get(from);
            if (value == null || StringUtils.isBlank(String.valueOf(value))) {
                return link;
            }
            return link + '?' + param + '=' + String.valueOf(value).trim();
        }
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
            final Object authored = field.fromSource(source, parentSource);
            resolveInto(target, field, isPresent(authored) ? authored : field.fallback());
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

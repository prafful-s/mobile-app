package com.dept.contentbridge.core.services.impl;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;

/**
 * One field of a mapping rule: where a target value comes from, and how it is derived.
 *
 * <p>Exactly one operator applies per field, checked in this order:</p>
 * <ul>
 *   <li>{@code const} - a fixed value, for target fields AEM has no equivalent of</li>
 *   <li>{@code extract} - a CSS selector run over an authored rich-text property</li>
 *   <li>{@code first} - the first non-blank of several candidate properties, for sites that
 *       renamed a field over time</li>
 *   <li>{@code from} - a straight copy</li>
 * </ul>
 *
 * <p>Independently of the operator above, an optional {@code map} translates the resolved value
 * through a fixed lookup (e.g. an authored heading level {@code h2} to a size token
 * {@code xlarge}). A value with no entry in the map passes through unchanged, so an unexpected
 * authored value is still visible in the response rather than silently dropped.</p>
 *
 * <p>A field that resolves to blank is omitted rather than emitted empty, matching what the curated
 * models already do. {@code default} fills a value in only when the source is missing, so a
 * deliberate authored blank is not overwritten by a guess.</p>
 *
 * <p>Built from a parsed {@code Map} rather than bound by Jackson: the rules are internal
 * implementation detail, and hand-reading them keeps the classes package-private and avoids relying
 * on reflective access across bundle classloaders.</p>
 */
final class FieldRule {

    /** Coercions available through {@code type}; anything else is left as authored. */
    private static final String TYPE_BOOLEAN = "boolean";
    private static final String TYPE_NUMBER = "number";
    private static final String TYPE_STRING = "string";
    /**
     * Turns a {@code List} (or a {@code String[]}, or any {@code Iterable}) into a single comma-
     * separated string, joining non-blank elements with {@code ", "}. Non-collections and already
     * scalar values pass through unchanged, so a folder-ref that injected a list of category tags
     * can be flattened onto a single string field without a code change.
     */
    private static final String TYPE_CSV = "csv";

    /**
     * Empties to a literal JSON {@code null} rather than a typed zero, for reference fields whose
     * contract is exactly {@code "product": null} (a slot the client fills at runtime). A field
     * declared {@code type: "null"} that resolves to nothing emits {@code null}; one that resolves
     * to an authored value keeps that value as-is.
     */
    private static final String TYPE_NULL = "null";

    /**
     * Turns a collection of taxonomy tags (e.g. {@code lumaproducts:women/bottoms}) into a comma-
     * separated string of unique lowercase tokens. Each tag has any prefix before {@code :} stripped
     * and is then split on {@code /}, so {@code lumaproducts:women/bottoms} contributes both
     * {@code women} and {@code bottoms}. Order is insertion order of first sight, duplicates dropped.
     * Scalar strings are split on {@code ,} first, then processed the same way, so the operator is
     * idempotent on its own output.
     */
    private static final String TYPE_CATEGORY_TOKENS = "categoryTokens";

    /**
     * Same tokenisation as {@link #TYPE_CATEGORY_TOKENS} but title-cased and joined with
     * {@code " / "}, producing the human-readable {@code "Tag1 / Tag2 / Tag3"} shape a mobile client
     * shows as the category path label. Hyphens inside a token become spaces so
     * {@code new-luma-yoga-collection} reads as {@code New Luma Yoga Collection}.
     */
    private static final String TYPE_CATEGORY_PATH = "categoryPath";

    /**
     * The single top-level category the mobile client uses as a filter expression (e.g. {@code Men}
     * matches any product whose {@code category} starts with {@code Men}). Runs a majority vote
     * across every input element: each element (one Content Fragment's tag string, possibly
     * comma-joined multi-value) contributes its first tag's first segment, title-cased and with
     * hyphens turned to spaces. The most frequent segment wins; ties break by first appearance so
     * the output is deterministic. Blank/prefix-only tags (e.g. {@code luma-products:}) contribute
     * no vote. Later segments after the first {@code /} are ignored on purpose - one anchor, not a
     * path.
     */
    private static final String TYPE_CATEGORY_ROOT = "categoryRoot";

    /**
     * {@link #TYPE_CATEGORY_ROOT} with a trailing {@code .*} appended, producing the regex-shaped
     * filter the client applies with a starts-with match (e.g. {@code Women.*} matches
     * {@code Women / Bottoms}, {@code Women / Tops}, ...). Empty when the vote produced nothing so
     * the field falls back to its declared default rather than emitting an unusable {@code .*}.
     */
    private static final String TYPE_CATEGORY_ROOT_REGEX = "categoryRootRegex";

    /**
     * Strips scheme and host off an absolute {@code http(s)} link, keeping only path, query and
     * fragment - a link component should carry a site-relative target, not whichever author/preview
     * host it happened to be authored against. A value that is not an absolute http(s) URL (already
     * a path, an anchor, {@code mailto:}, {@code tel:}, ...) is left untouched.
     */
    private static final String TYPE_PATH = "path";

    /**
     * Substring after the last {@code /}, trimmed of a trailing slash. Turns
     * {@code .../fragments/luma-common-cf-products/men} into {@code men}. Non-strings pass through.
     */
    private static final String TYPE_BASENAME = "basename";

    /**
     * Turns a taxonomy tag list into a human label. Splits on commas, strips the taxonomy prefix
     * before {@code :}, replaces {@code -} with spaces, splits nested paths on {@code /}, title-cases
     * every segment and joins them with {@code  / }. Multiple tags are joined with {@code , }.
     * {@code lumaproducts:men/tops,lumaproducts:summer-collection} becomes
     * {@code Men / Tops, Summer Collection}.
     */
    private static final String TYPE_CATEGORY_LABEL = "categoryLabel";

    /**
     * True when the source produced anything at all, whatever it said.
     *
     * <p>Distinct from {@code boolean}, which reads the value: a banner's {@code showButton} is true
     * because the authored rich text contained a link, not because that link said "true". Coercing
     * "Select your plan" with {@code Boolean.parseBoolean} would answer false.</p>
     */
    private static final String TYPE_PRESENCE = "presence";

    private final String target;
    private final String from;
    private final String parentFrom;
    private final List<String> first;
    private final Object constant;
    private final Extract extract;
    private final Object defaultValue;
    private final String type;
    private final boolean nullable;
    private final Map<String, Object> valueMap;

    private FieldRule(final String target, final String from, final String parentFrom,
                      final List<String> first, final Object constant, final Extract extract,
                      final Object defaultValue, final String type, final boolean nullable,
                      final Map<String, Object> valueMap) {
        this.target = target;
        this.from = from;
        this.parentFrom = parentFrom;
        this.first = first;
        this.constant = constant;
        this.extract = extract;
        this.defaultValue = defaultValue;
        this.type = type;
        this.nullable = nullable;
        this.valueMap = valueMap;
    }

    /**
     * @param definition one entry of a rule file's {@code fields} array
     * @return the rule, or {@code null} when it names no target and so could produce nothing
     */
    static FieldRule of(final Map<String, Object> definition) {
        final String target = string(definition.get("target"));
        if (StringUtils.isBlank(target)) {
            return null;
        }
        return new FieldRule(target,
                string(definition.get("from")),
                string(definition.get("fromParent")),
                strings(definition.get("first")),
                definition.get("const"),
                Extract.of(definition.get("extract")),
                definition.get("default"),
                string(definition.get("type")),
                Boolean.TRUE.equals(definition.get("nullable")),
                stringMap(definition.get("map")));
    }

    String getTarget() {
        return target;
    }

    /**
     * The value this field takes from what the author actually set.
     *
     * <p>Kept separate from {@link #fallback()} so the caller can tell a mapping that read real
     * content from one that produced nothing but its own constants and defaults - see
     * {@link ComponentMapping#apply(Map, Map)}.</p>
     *
     * @param source       the component's own authored properties
     * @param parentSource the nearest enclosing section's authored properties, for fields
     *                     declared {@code fromParent} - a section carries layout settings (such
     *                     as {@code sec-alignment}) that apply to every component inside it
     * @return the authored value, or {@code null} when this field has no source or it is unset
     */
    Object fromSource(final Map<String, Object> source, final Map<String, Object> parentSource) {
        return constant != null ? null : coerce(translate(resolve(source, parentSource)));
    }

    /** Looks the resolved value up in {@code map}; unmapped or absent-map values pass through as-is. */
    private Object translate(final Object value) {
        if (valueMap == null || value == null) {
            return value;
        }
        final Object mapped = valueMap.get(String.valueOf(value));
        return mapped != null ? mapped : value;
    }

    /** The value to use when nothing was authored: the constant, or the declared default. */
    Object fallback() {
        return constant != null ? constant : defaultValue;
    }

    /**
     * True when the schema declares this field nullable. No longer changes what
     * {@link #emptyDefault()} emits - kept on the schema and this accessor for rule files that
     * still declare it, and for any caller wanting to know the field's optionality.
     */
    boolean isNullable() {
        return nullable;
    }

    /**
     * The value to emit when nothing was authored and no fallback fired, under skeleton mode.
     *
     * <p>A field that explicitly declared {@code default} keeps that value even when the resolved
     * default was blank - a rule saying {@code "default": ""} on a number field is asking for the
     * empty string as its placeholder, not a typed zero. Otherwise the type decides: numbers get
     * {@code 0}, booleans get {@code false}, everything else gets an empty string. The point is a
     * fixed, typed shape a client can always index into without a null check.</p>
     */
    Object emptyDefault() {
        if (defaultValue != null) {
            return defaultValue;
        }
        if (TYPE_NUMBER.equals(type)) {
            return 0;
        }
        if (TYPE_BOOLEAN.equals(type) || TYPE_PRESENCE.equals(type)) {
            return Boolean.FALSE;
        }
        if (TYPE_NULL.equals(type)) {
            return null;
        }
        return "";
    }

    /**
     * True when the mapping should still emit the field on skeleton pass even when
     * {@link #emptyDefault()} evaluates to {@code null} - a JSON {@code null} is the reference
     * shape's chosen placeholder for {@code type:"null"} fields, distinct from "not authored".
     */
    boolean emitsNullDefault() {
        return TYPE_NULL.equals(type) && defaultValue == null;
    }

    private Object resolve(final Map<String, Object> source, final Map<String, Object> parentSource) {
        if (extract != null) {
            return extract.apply(source);
        }
        if (parentFrom != null) {
            return blankToNull(parentSource.get(parentFrom));
        }
        if (!first.isEmpty()) {
            for (final String candidate : first) {
                final Object value = blankToNull(source.get(candidate));
                if (value != null) {
                    return value;
                }
            }
            return null;
        }
        return from == null ? null : blankToNull(source.get(from));
    }

    private static Object blankToNull(final Object value) {
        return value instanceof String && StringUtils.isBlank((String) value) ? null : value;
    }

    /**
     * JCR gives back the type the property was stored as, which is not always the type the client
     * contract wants - Edge Delivery writes some booleans as the strings {@code "true"}/{@code
     * "false"}, and a value pulled out of rich text is always a string.
     */
    private Object coerce(final Object value) {
        if (value == null || type == null) {
            return value;
        }
        if (TYPE_CSV.equals(type)) {
            return toCsv(value);
        }
        if (TYPE_CATEGORY_TOKENS.equals(type)) {
            return toCategoryTokens(value);
        }
        if (TYPE_CATEGORY_PATH.equals(type)) {
            return toCategoryPath(value);
        }
        if (TYPE_CATEGORY_ROOT.equals(type)) {
            return toCategoryRoot(value);
        }
        if (TYPE_CATEGORY_ROOT_REGEX.equals(type)) {
            final Object root = toCategoryRoot(value);
            final String text = root == null ? "" : String.valueOf(root);
            return text.isEmpty() ? "" : text + ".*";
        }
        final String text = String.valueOf(value);
        switch (type) {
            case TYPE_PRESENCE:
                return StringUtils.isNotBlank(text);
            case TYPE_BOOLEAN:
                return value instanceof Boolean ? value : Boolean.parseBoolean(text.trim());
            case TYPE_NUMBER:
                return toNumber(text.trim(), value);
            case TYPE_STRING:
                return text;
            case TYPE_PATH:
                return toPath(text);
            case TYPE_BASENAME:
                return basename(text);
            case TYPE_CATEGORY_LABEL:
                return categoryLabel(text);
            default:
                return value;
        }
    }

    /** See {@link #TYPE_CATEGORY_TOKENS}. */
    private static Object toCategoryTokens(final Object value) {
        final Iterable<?> items;
        if (value instanceof Iterable) {
            items = (Iterable<?>) value;
        } else if (value instanceof Object[]) {
            items = java.util.Arrays.asList((Object[]) value);
        } else {
            // Scalar - treat as an already-joined string and re-tokenise it.
            items = java.util.Arrays.asList(String.valueOf(value).split(","));
        }
        final java.util.LinkedHashSet<String> tokens = new java.util.LinkedHashSet<>();
        for (final Object item : items) {
            if (item == null) {
                continue;
            }
            final String raw = String.valueOf(item).trim();
            if (raw.isEmpty()) {
                continue;
            }
            final String body = raw.indexOf(':') >= 0 ? StringUtils.substringAfter(raw, ":") : raw;
            for (final String token : body.split("/")) {
                final String cleaned = token.trim();
                if (!cleaned.isEmpty()) {
                    tokens.add(cleaned);
                }
            }
        }
        return String.join(", ", tokens);
    }

    /** See {@link #TYPE_CATEGORY_PATH}. */
    private static Object toCategoryPath(final Object value) {
        final Iterable<?> items;
        if (value instanceof Iterable) {
            items = (Iterable<?>) value;
        } else if (value instanceof Object[]) {
            items = java.util.Arrays.asList((Object[]) value);
        } else {
            items = java.util.Arrays.asList(String.valueOf(value).split(","));
        }
        final java.util.LinkedHashSet<String> tokens = new java.util.LinkedHashSet<>();
        for (final Object item : items) {
            if (item == null) {
                continue;
            }
            // A single element may itself be a comma-joined string (multi-value tag arrays serialise
            // that way in ContentFragmentReader), so split on commas first.
            for (final String raw : String.valueOf(item).split(",")) {
                final String tag = raw.trim();
                if (tag.isEmpty()) {
                    continue;
                }
                final String body = tag.indexOf(':') >= 0 ? StringUtils.substringAfter(tag, ":") : tag;
                for (final String seg : body.split("/")) {
                    final String cleaned = seg.replace('-', ' ').trim();
                    if (!cleaned.isEmpty()) {
                        tokens.add(titleCase(cleaned));
                    }
                }
            }
        }
        return String.join(" / ", tokens);
    }

    /** See {@link #TYPE_CATEGORY_ROOT}. */
    private static Object toCategoryRoot(final Object value) {
        final Iterable<?> items;
        if (value instanceof Iterable) {
            items = (Iterable<?>) value;
        } else if (value instanceof Object[]) {
            items = java.util.Arrays.asList((Object[]) value);
        } else {
            // A scalar: each comma-split segment counts as one item's vote.
            items = java.util.Arrays.asList(String.valueOf(value).split(","));
        }
        // Majority vote across every item. LinkedHashMap keeps first-seen order so a tie breaks
        // deterministically toward the first appearance.
        final Map<String, Integer> votes = new java.util.LinkedHashMap<>();
        for (final Object item : items) {
            if (item == null) {
                continue;
            }
            final String root = firstRoot(String.valueOf(item));
            if (!root.isEmpty()) {
                votes.merge(root, 1, Integer::sum);
            }
        }
        String winner = "";
        int best = 0;
        for (final Map.Entry<String, Integer> vote : votes.entrySet()) {
            if (vote.getValue() > best) {
                best = vote.getValue();
                winner = vote.getKey();
            }
        }
        return winner;
    }

    /**
     * One item's contribution to a {@link #TYPE_CATEGORY_ROOT} vote: the first-segment of the most
     * category-shaped tag inside a possibly comma-joined multi-value tag string, title-cased with
     * hyphens turned to spaces. Prefers a tag whose body contains a {@code /} (a real
     * {@code Men/Tops}-style category path) over a flat marker tag ({@code sample-tag-1}) so noise
     * tags on the same fragment cannot outvote the category itself. Falls back to the first
     * non-empty tag when nothing has a slash. Empty when no tag has a usable root.
     */
    private static String firstRoot(final String multiTag) {
        String fallback = "";
        for (final String raw : multiTag.split(",")) {
            final String tag = raw.trim();
            if (tag.isEmpty()) {
                continue;
            }
            final String body = tag.indexOf(':') >= 0 ? StringUtils.substringAfter(tag, ":") : tag;
            final int slash = body.indexOf('/');
            final String head = (slash < 0 ? body : body.substring(0, slash))
                    .replace('-', ' ').trim();
            if (head.isEmpty()) {
                continue;
            }
            if (slash >= 0) {
                return titleCase(head);
            }
            if (fallback.isEmpty()) {
                fallback = titleCase(head);
            }
        }
        return fallback;
    }

    /** See {@link #TYPE_CSV}. */
    private static Object toCsv(final Object value) {
        final Iterable<?> items;
        if (value instanceof Iterable) {
            items = (Iterable<?>) value;
        } else if (value instanceof Object[]) {
            items = java.util.Arrays.asList((Object[]) value);
        } else {
            // Already a scalar - the join is a no-op, keep as authored.
            return value;
        }
        final List<String> parts = new ArrayList<>();
        for (final Object item : items) {
            if (item == null) {
                continue;
            }
            final String text = String.valueOf(item).trim();
            if (!text.isEmpty()) {
                parts.add(text);
            }
        }
        return String.join(", ", parts);
    }

    /** Last path segment of a URL or path. Trailing slashes stripped. */
    private static String basename(final String text) {
        final String trimmed = StringUtils.stripEnd(text, "/");
        final int slash = trimmed.lastIndexOf('/');
        return slash < 0 ? trimmed : trimmed.substring(slash + 1);
    }

    /** See {@link #TYPE_CATEGORY_LABEL}. */
    private static String categoryLabel(final String text) {
        final List<String> labels = new ArrayList<>();
        for (final String raw : text.split(",")) {
            final String tag = StringUtils.trimToNull(raw);
            if (tag == null) {
                continue;
            }
            final String tail = StringUtils.substringAfter(tag, ":");
            final String body = StringUtils.defaultIfEmpty(tail, tag);
            final List<String> segments = new ArrayList<>();
            for (final String seg : body.split("/")) {
                final String spaced = seg.replace('-', ' ').trim();
                if (!spaced.isEmpty()) {
                    segments.add(titleCase(spaced));
                }
            }
            if (!segments.isEmpty()) {
                labels.add(String.join(" / ", segments));
            }
        }
        return String.join(", ", labels);
    }

    private static String titleCase(final String text) {
        final StringBuilder sb = new StringBuilder(text.length());
        boolean atStart = true;
        for (int i = 0; i < text.length(); i++) {
            final char c = text.charAt(i);
            if (Character.isWhitespace(c)) {
                atStart = true;
                sb.append(c);
            } else if (atStart) {
                sb.append(Character.toUpperCase(c));
                atStart = false;
            } else {
                sb.append(Character.toLowerCase(c));
            }
        }
        return sb.toString();
    }

    private static Object toPath(final String text) {
        if (!StringUtils.startsWithAny(text, "http://", "https://")) {
            return text;
        }
        try {
            final URI uri = new URI(text);
            final StringBuilder path = new StringBuilder(StringUtils.defaultIfEmpty(uri.getRawPath(), "/"));
            if (uri.getRawQuery() != null) {
                path.append('?').append(uri.getRawQuery());
            }
            if (uri.getRawFragment() != null) {
                path.append('#').append(uri.getRawFragment());
            }
            return path.toString();
        } catch (final URISyntaxException e) {
            // Malformed authored URL: keep what the author wrote rather than guessing at it.
            return text;
        }
    }

    private static Object toNumber(final String text, final Object original) {
        try {
            return text.indexOf('.') >= 0 ? (Object) Double.valueOf(text) : (Object) Long.valueOf(text);
        } catch (final NumberFormatException e) {
            // Authored free text where a number was expected: keep what the author wrote rather
            // than dropping the field, so the problem is visible in the response instead of hidden.
            return original;
        }
    }

    private static String string(final Object value) {
        return value instanceof String ? StringUtils.trimToNull((String) value) : null;
    }

    private static List<String> strings(final Object value) {
        if (!(value instanceof List)) {
            return Collections.emptyList();
        }
        final List<String> names = new ArrayList<>();
        for (final Object item : (List<?>) value) {
            final String name = string(item);
            if (name != null) {
                names.add(name);
            }
        }
        return names;
    }

    private static Map<String, Object> stringMap(final Object value) {
        if (!(value instanceof Map)) {
            return null;
        }
        @SuppressWarnings("unchecked")
        final Map<String, Object> raw = (Map<String, Object>) value;
        return Collections.unmodifiableMap(raw);
    }

    /** The {@code extract} operator's parameters. */
    private static final class Extract {

        private final String from;
        private final String select;
        private final String as;
        private final String attribute;
        private final boolean all;

        private Extract(final String from, final String select, final String as,
                        final String attribute, final boolean all) {
            this.from = from;
            this.select = select;
            this.as = as;
            this.attribute = attribute;
            this.all = all;
        }

        static Extract of(final Object definition) {
            if (!(definition instanceof Map)) {
                return null;
            }
            @SuppressWarnings("unchecked")
            final Map<String, Object> map = (Map<String, Object>) definition;
            final String as = string(map.get("as"));
            return new Extract(string(map.get("from")), string(map.get("select")),
                    as == null ? HtmlExtractor.AS_TEXT : as,
                    string(map.get("attribute")),
                    Boolean.TRUE.equals(map.get("all")));
        }

        Object apply(final Map<String, Object> source) {
            final Object html = from == null ? null : source.get(from);
            return html instanceof String
                    ? HtmlExtractor.extract((String) html, select, as, attribute, all)
                    : null;
        }
    }
}

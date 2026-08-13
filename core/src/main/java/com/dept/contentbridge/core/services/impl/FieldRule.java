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
     * Emits a literal JSON {@code null} rather than an empty string as the skeleton default.
     * Used for reference-contract keys that must show up as {@code null} to signal "no value yet"
     * (a product-detail slot's {@code product}, for instance) rather than as an empty string.
     */
    private static final String TYPE_NULL = "null";

    /**
     * Strips scheme and host off an absolute {@code http(s)} link, keeping only path, query and
     * fragment - a link component should carry a site-relative target, not whichever author/preview
     * host it happened to be authored against. A value that is not an absolute http(s) URL (already
     * a path, an anchor, {@code mailto:}, {@code tel:}, ...) is left untouched.
     */
    private static final String TYPE_PATH = "path";

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
     * Numbers get {@code 0}; booleans get {@code false}; everything else - including nullable
     * fields - gets an empty string rather than {@code null}. The point is a fixed, typed shape a
     * client can always index into without a null check.
     */
    Object emptyDefault() {
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
     * The CRX property names this rule reads from <em>the component's own resource</em>, so
     * extras passthrough can filter them out. Constant-only rules, and {@code fromParent} rules
     * (which read the enclosing section, not the component), return an empty list - neither
     * consumes anything from the component's own authored properties.
     */
    List<String> consumedProperties() {
        if (extract != null) {
            final String source = extract.sourceProperty();
            return source == null ? Collections.emptyList() : List.of(source);
        }
        if (!first.isEmpty()) {
            return first;
        }
        if (from != null) {
            return List.of(from);
        }
        return Collections.emptyList();
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
            default:
                return value;
        }
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

        /** The CRX property this extract reads from, or null when it names none. */
        String sourceProperty() {
            return from;
        }
    }
}

package com.edsMobileApp.core.services.impl;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Parses a block's authored {@code custommobilestyle} - a multifield of free-text {@code
 * key:value} rows (e.g. {@code "width:250"}, {@code "color:#f9e322"}) - into a plain key/value
 * map ready to merge onto a component's {@code styles}. Authored once per block, so every row
 * applies to the same styles object rather than targeting a particular field the way {@code
 * FormBlockBuilder}'s narrower, checkboxGroup-only reading of this same field does.
 *
 * <p>A value is coerced to a number when it parses as one ({@code width:250} becomes the integer
 * {@code 250}, not the string {@code "250"}), and left as a string otherwise ({@code
 * color:#f9e322}). A malformed row (no {@code ":"}, or a blank key/value) is skipped rather than
 * failing the whole field.</p>
 */
final class CustomMobileStyle {

    private static final String FIELD_NAME = "custommobilestyle";

    private CustomMobileStyle() {
    }

    /**
     * @param authored a component's own authored properties (not a Content Fragment's - see
     *                 {@code ContentFragmentReader} for that)
     * @return every authored row as {@code key -> value}, in authoring order; empty when the
     *         field is absent or carries no valid row
     */
    static Map<String, Object> asMap(final Map<String, Object> authored) {
        final Object raw = authored.get(FIELD_NAME);
        final List<?> rows = raw instanceof List ? (List<?>) raw
                : raw instanceof String[] ? Arrays.asList((String[]) raw) : null;
        if (rows == null) {
            return Collections.emptyMap();
        }
        final Map<String, Object> result = new LinkedHashMap<>();
        for (final Object row : rows) {
            if (!(row instanceof String)) {
                continue;
            }
            final String[] parts = ((String) row).split(":", 2);
            if (parts.length != 2) {
                continue;
            }
            final String key = parts[0].trim();
            final String value = parts[1].trim();
            if (key.isEmpty() || value.isEmpty()) {
                continue;
            }
            result.put(key, coerce(value));
        }
        return result;
    }

    private static Object coerce(final String value) {
        try {
            return Long.valueOf(value);
        } catch (final NumberFormatException notLong) {
            try {
                return Double.valueOf(value);
            } catch (final NumberFormatException notNumber) {
                return value;
            }
        }
    }
}

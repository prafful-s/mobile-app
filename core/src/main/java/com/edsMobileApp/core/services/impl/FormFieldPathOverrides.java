package com.edsMobileApp.core.services.impl;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

final class FormFieldPathOverrides {

    private final Map<String, Map<String, String>> byTheme;

    private FormFieldPathOverrides(final Map<String, Map<String, String>> byTheme) {
        this.byTheme = byTheme;
    }

    static FormFieldPathOverrides load() {
        final String path = "/edsMobileApp/forms/field-path-overrides.json";
        try (InputStream input = FormFieldPathOverrides.class.getResourceAsStream(path)) {
            if (input == null) {
                throw new IllegalStateException("Missing form field path overrides: " + path);
            }
            return new FormFieldPathOverrides(new ObjectMapper().readValue(input,
                    new TypeReference<Map<String, Map<String, String>>>() { }));
        } catch (final IOException exception) {
            throw new IllegalStateException("Could not read form field path overrides: " + path, exception);
        }
    }

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> apply(final List<Map<String, Object>> content, final String themeName) {
        final Map<String, String> overrides = themeName == null ? Collections.emptyMap()
                : byTheme.getOrDefault(themeName, Collections.emptyMap());
        if (overrides.isEmpty()) {
            return content;
        }
        return (List<Map<String, Object>>) copy(content, overrides, false);
    }

    private Object copy(final Object value, final Map<String, String> overrides, final boolean inForm) {
        if (value instanceof List) {
            final List<Object> copied = new ArrayList<>();
            for (final Object item : (List<?>) value) {
                copied.add(copy(item, overrides, inForm));
            }
            return copied;
        }
        if (!(value instanceof Map)) {
            return value;
        }
        final Map<?, ?> source = (Map<?, ?>) value;
        final Object meta = source.get("meta");
        final boolean form = meta instanceof Map && "form".equals(((Map<?, ?>) meta).get("component"));
        final Map<String, Object> copied = new LinkedHashMap<>();
        for (final Map.Entry<?, ?> entry : source.entrySet()) {
            final String key = (String) entry.getKey();
            final Object original = entry.getValue();
            final String replacement = inForm && "path".equals(key) && original instanceof String
                    ? overrides.get(original) : null;
            copied.put(key, StringUtils.isNotBlank(replacement) ? replacement
                    : copy(original, overrides, inForm || (form && "content".equals(key))));
        }
        return copied;
    }
}
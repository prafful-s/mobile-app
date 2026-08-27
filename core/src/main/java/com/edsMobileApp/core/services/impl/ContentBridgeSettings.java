package com.edsMobileApp.core.services.impl;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Global settings for the ContentBridge JSON exporter, read once from {@code config.json} at
 * activation instead of an OSGi {@code @ObjectClassDefinition}.
 *
 * <p>These knobs ({@code maxDepth}, {@code navPageNames},
 * {@code excludedPageNames}, {@code includeHomeScreen}, {@code styleTokens},
 * {@code overrideEnabled}, {@code overridePropertyName}) are legitimately global - one servlet
 * exports every site, so
 * unlike branding/integrations/profileViewer there is no per-site variant to keep separate.
 * Trading OSGi's web-console tunability for a single file was a deliberate choice: it keeps every
 * exporter setting in one place instead of split across OSGi config and per-site JSON.</p>
 *
 * <p>Same classloader-only loading arrangement as {@link SiteDefaults}, read from
 * {@code /contentbridge/config.json} on the classpath, so behaviour is identical in OSGi, in
 * tests and in a plain JVM. Loaded once at activation, not per request: the file ships inside the
 * bundle and cannot change without a redeploy.</p>
 *
 * <p>{@code load(Map)} additionally accepts the component properties {@code @Activate} receives
 * (whatever the mock or real OSGi runtime passes, e.g. Sling Mocks'
 * {@code registerInjectActivateService(service, "maxDepth", ...)} in tests) - any key present
 * there wins over {@code config.json}, so a test can still pin a narrower {@code maxDepth}
 * without needing its own {@code config.json} on the test classpath.</p>
 */
final class ContentBridgeSettings {

    private static final Logger LOG = LoggerFactory.getLogger(ContentBridgeSettings.class);

    private static final String PATH = "/contentbridge/config.json";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final int maxDepth;
    private final String[] navPageNames;
    private final String[] excludedPageNames;
    private final boolean includeHomeScreen;
    private final String[] styleTokens;
    private final boolean overrideEnabled;
    private final String overridePropertyName;

    private ContentBridgeSettings(final Map<String, Object> values) {
        this.maxDepth = intValue(values, "maxDepth", 3);
        this.navPageNames = stringArray(values, "navPageNames",
                new String[] {"nav", "header", "navigation"});
        this.excludedPageNames = stringArray(values, "excludedPageNames", new String[] {
                "nav", "header", "navigation", "footer", "placeholders", "language-masters", "redirects"});
        this.includeHomeScreen = boolValue(values, "includeHomeScreen", true);
        this.styleTokens = stringArray(values, "styleTokens",
                new String[] {"bg-default=backgroundColor:#F5F8FA"});
        this.overrideEnabled = boolValue(values, "overrideEnabled", true);
        this.overridePropertyName = StringUtils.defaultIfBlank(
                stringValue(values, "overridePropertyName"), "cbJson");
    }

    static ContentBridgeSettings load() {
        return new ContentBridgeSettings(read());
    }

    /**
     * @param overrides component properties from {@code @Activate}; any key present here wins
     *                  over the same key in {@code config.json}
     */
    static ContentBridgeSettings load(final Map<String, Object> overrides) {
        final Map<String, Object> merged = new LinkedHashMap<>(read());
        if (overrides != null) {
            merged.putAll(overrides);
        }
        return new ContentBridgeSettings(merged);
    }

    int maxDepth() {
        return Math.max(1, maxDepth);
    }

    String[] navPageNames() {
        return navPageNames.clone();
    }

    String[] excludedPageNames() {
        return excludedPageNames.clone();
    }

    boolean includeHomeScreen() {
        return includeHomeScreen;
    }

    String[] styleTokens() {
        return styleTokens.clone();
    }

    boolean overrideEnabled() {
        return overrideEnabled;
    }

    String overridePropertyName() {
        return overridePropertyName;
    }

    private static Map<String, Object> read() {
        try (InputStream in = ContentBridgeSettings.class.getResourceAsStream(PATH)) {
            if (in == null) {
                LOG.error("{} not present on the classpath - falling back to compiled-in defaults", PATH);
                return Collections.emptyMap();
            }
            final Map<String, Object> obj = MAPPER.readValue(
                    new String(in.readAllBytes(), StandardCharsets.UTF_8),
                    new TypeReference<Map<String, Object>>() { });
            LOG.info("Loaded ContentBridge settings from {}", PATH);
            return obj;
        } catch (final IOException | RuntimeException e) {
            LOG.error("{} could not be read - falling back to compiled-in defaults", PATH, e);
            return Collections.emptyMap();
        }
    }

    @SuppressWarnings("unchecked")
    private static String[] stringArray(final Map<String, Object> values, final String key,
                                        final String[] fallback) {
        final Object value = values.get(key);
        // config.json deserialises an array as a List; OSGi component properties (real or mock,
        // e.g. Sling Mocks' registerInjectActivateService(service, "navPageNames", new String[]{...}))
        // hand over a plain String[] instead - both are accepted.
        if (value instanceof String[]) {
            return ((String[]) value).clone();
        }
        if (!(value instanceof List)) {
            return fallback;
        }
        return ((List<Object>) value).stream()
                .filter(String.class::isInstance)
                .map(String.class::cast)
                .toArray(String[]::new);
    }

    private static String stringValue(final Map<String, Object> values, final String key) {
        final Object value = values.get(key);
        return value instanceof String ? (String) value : null;
    }

    private static int intValue(final Map<String, Object> values, final String key, final int fallback) {
        final Object value = values.get(key);
        return value instanceof Number ? ((Number) value).intValue() : fallback;
    }

    private static boolean boolValue(final Map<String, Object> values, final String key,
                                      final boolean fallback) {
        final Object value = values.get(key);
        return value instanceof Boolean ? (Boolean) value : fallback;
    }
}

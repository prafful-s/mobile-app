package com.edsMobileApp.core.services.impl;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import org.apache.commons.lang3.StringUtils;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.edsMobileApp.core.services.FormSchemaService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Loads form schemas from JSON bundled under {@code /contentbridge/forms}.
 *
 * <p>One file per form, named after the form, plus an {@code index.json} naming them all. Everything
 * is read once at activation and held immutable: the files ship inside the bundle, so they cannot
 * change without a redeploy and re-reading them per request would be wasted work.</p>
 *
 * <p>The index exists because a bundle cannot reliably list its own contents - {@code findEntries} is
 * an OSGi framework call that mocks and plain classloaders do not implement. Reading a manifest of
 * names and then loading each by path uses nothing but the classloader, so it behaves the same in
 * OSGi, in tests and in a plain JVM. {@code FormSchemaServiceTest} fails if the index and the
 * directory ever disagree, so the extra file cannot silently drift.</p>
 */
@Component(service = FormSchemaService.class, immediate = true)
public class FormSchemaServiceImpl implements FormSchemaService {

    private static final Logger LOG = LoggerFactory.getLogger(FormSchemaServiceImpl.class);

    static final String SCHEMA_PATH = "/contentbridge/forms";
    static final String INDEX = SCHEMA_PATH + "/index.json";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private Map<String, List<Map<String, Object>>> schemas = Collections.emptyMap();

    @Activate
    protected void activate() {
        final List<String> formNames = readIndex();
        // Sorted so the JSON serialises in a stable order regardless of index ordering.
        final Map<String, List<Map<String, Object>>> loaded = new TreeMap<>();

        for (final String formName : formNames) {
            if (StringUtils.isBlank(formName)) {
                continue;
            }
            try {
                loaded.put(formName.trim(), readSchema(formName.trim()));
            } catch (final IOException e) {
                // One malformed or missing file must not cost us the other seventeen.
                LOG.error("Form schema '{}' could not be read - skipping it", formName, e);
            }
        }

        this.schemas = Collections.unmodifiableMap(loaded);
        LOG.info("Loaded {} form schemas: {}", schemas.size(), schemas.keySet());
    }

    private List<String> readIndex() {
        try (InputStream in = getClass().getResourceAsStream(INDEX)) {
            if (in == null) {
                LOG.error("{} is missing - no form schemas will be available", INDEX);
                return Collections.emptyList();
            }
            return MAPPER.readValue(new String(in.readAllBytes(), StandardCharsets.UTF_8),
                    new TypeReference<List<String>>() { });
        } catch (final IOException e) {
            LOG.error("{} could not be read - no form schemas will be available", INDEX, e);
            return Collections.emptyList();
        }
    }

    private List<Map<String, Object>> readSchema(final String formName) throws IOException {
        final String path = SCHEMA_PATH + "/" + formName + ".json";
        try (InputStream in = getClass().getResourceAsStream(path)) {
            if (in == null) {
                throw new IOException(path + " is listed in the index but not present in the bundle");
            }
            final String json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            return Collections.unmodifiableList(MAPPER.readValue(json,
                    new TypeReference<ArrayList<Map<String, Object>>>() { }));
        }
    }

    @Override
    public List<Map<String, Object>> getSchema(final String formName) {
        if (StringUtils.isBlank(formName)) {
            return Collections.emptyList();
        }
        final List<Map<String, Object>> schema = schemas.get(formName.trim());
        if (schema != null) {
            return schema;
        }
        LOG.debug("No form schema named '{}' - known forms are {}", formName, schemas.keySet());
        return Collections.emptyList();
    }

    @Override
    public Map<String, List<Map<String, Object>>> getAllSchemas() {
        return schemas;
    }

    @Override
    public Set<String> getFormNames() {
        return schemas.keySet();
    }
}

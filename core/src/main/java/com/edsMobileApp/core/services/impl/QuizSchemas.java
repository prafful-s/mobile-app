package com.edsMobileApp.core.services.impl;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Loads quiz step definitions from JSON bundled under {@code /contentbridge/quizzes}.
 *
 * <p>A quiz block (e.g. Frescopa's {@code coffee-quiz-frescopa}) authors nothing but its own
 * behaviour settings - completion URL, event names, a progress toggle. The questions, options and
 * submit button it renders are fixed in the block's own front-end code, not authored in AEM at all,
 * so there is no child resource for {@link ComponentTreeWalker} to walk. This is that fixed content,
 * moved here instead: one step per array entry, each already the exact {@code component}/{@code
 * content} shape the export emits, so {@link ComponentTreeWalker} only has to stamp an id and an
 * order onto each one and add it to the flat component list.</p>
 *
 * <p>Deliberately not {@link com.edsMobileApp.core.services.FormSchemaService}: that service's own
 * test suite locks every schema it loads to the form-field vocabulary ({@code text}/{@code select}/
 * {@code radio}/...), which a quiz step's {@code title}/{@code dropdown}/{@code formSubmitButton}/
 * {@code divider}/{@code image} shape does not - and is not trying to. Same loading discipline
 * (classloader-only, index-manifest, one bad file costs only itself), separate directory, separate
 * contract.</p>
 */
final class QuizSchemas {

    private static final Logger LOG = LoggerFactory.getLogger(QuizSchemas.class);

    static final String QUIZZES_PATH = "/contentbridge/quizzes";
    static final String INDEX = QUIZZES_PATH + "/index.json";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Used when the quiz files are missing or unreadable: no block is recognised as a quiz. */
    static final QuizSchemas EMPTY = new QuizSchemas(Collections.emptyMap());

    private final Map<String, List<Map<String, Object>>> quizzes;

    private QuizSchemas(final Map<String, List<Map<String, Object>>> quizzes) {
        this.quizzes = quizzes;
    }

    /**
     * Reads every quiz named in the index. A malformed file costs its own quiz and nothing else -
     * failing activation would take the whole export down over one bad step definition.
     */
    static QuizSchemas load() {
        final Map<String, List<Map<String, Object>>> loaded = new TreeMap<>();

        for (final String name : readIndex()) {
            if (StringUtils.isBlank(name)) {
                continue;
            }
            try {
                loaded.put(name.trim(), readSteps(name.trim()));
            } catch (final IOException | RuntimeException e) {
                LOG.error("Quiz '{}' could not be read - skipping it", name, e);
            }
        }

        LOG.info("Loaded {} quizzes: {}", loaded.size(), loaded.keySet());
        return new QuizSchemas(Collections.unmodifiableMap(loaded));
    }

    private static List<String> readIndex() {
        try (InputStream in = QuizSchemas.class.getResourceAsStream(INDEX)) {
            if (in == null) {
                LOG.error("{} is missing - no quizzes will be recognised", INDEX);
                return Collections.emptyList();
            }
            return MAPPER.readValue(new String(in.readAllBytes(), StandardCharsets.UTF_8),
                    new TypeReference<List<String>>() { });
        } catch (final IOException e) {
            LOG.error("{} could not be read - no quizzes will be recognised", INDEX, e);
            return Collections.emptyList();
        }
    }

    private static List<Map<String, Object>> readSteps(final String name) throws IOException {
        final String path = QUIZZES_PATH + "/" + name + ".json";
        try (InputStream in = QuizSchemas.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IOException(path + " is listed in the index but not present in the bundle");
            }
            final String json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            return Collections.unmodifiableList(MAPPER.readValue(json,
                    new TypeReference<ArrayList<Map<String, Object>>>() { }));
        }
    }

    /**
     * @param blockName the authored component name (its {@code model} property)
     * @return that quiz's steps in render order, or an empty list when {@code blockName} names no
     *         quiz - the caller's cue to fall through to the normal per-component walk
     */
    List<Map<String, Object>> getSteps(final String blockName) {
        if (StringUtils.isBlank(blockName)) {
            return Collections.emptyList();
        }
        return quizzes.getOrDefault(blockName.trim(), Collections.emptyList());
    }
}

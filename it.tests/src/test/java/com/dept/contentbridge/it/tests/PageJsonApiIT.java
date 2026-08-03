package com.dept.contentbridge.it.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Smoke tests against a running AEM author instance.
 *
 * <pre>mvn verify -PintegrationTests</pre>
 */
class PageJsonApiIT {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String BASE = System.getProperty("it.author.url", "http://localhost:5402");
    private static final String USER = System.getProperty("it.author.user", "admin");
    private static final String PASSWORD = System.getProperty("it.author.password", "admin");
    private static final String APP_ROOT = "/content/contentbridge/us/en";

    private HttpResponse<String> get(final String query) throws Exception {
        final String credentials = Base64.getEncoder()
                .encodeToString((USER + ":" + PASSWORD).getBytes(StandardCharsets.UTF_8));
        final HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(BASE + "/bin/contentbridge/pages?" + query))
                .header("Authorization", "Basic " + credentials)
                .GET()
                .build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    }

    private Map<String, Object> json(final HttpResponse<String> response) throws Exception {
        return MAPPER.readValue(response.body(), new TypeReference<Map<String, Object>>() { });
    }

    @Test
    @DisplayName("GET with a valid path returns the app envelope")
    void exportsTheSampleApp() throws Exception {
        final HttpResponse<String> response = get("path=" + APP_ROOT);

        assertEquals(200, response.statusCode(), response.body());
        assertTrue(response.headers().firstValue("Content-Type").orElse("").contains("application/json"));

        final Map<String, Object> body = json(response);
        assertNotNull(body.get("id"));
        assertNotNull(body.get("meta"));
        assertNotNull(body.get("screens"));
        assertNotNull(body.get("branding"));
    }

    @Test
    @DisplayName("GET without a path returns 400")
    void rejectsMissingPath() throws Exception {
        assertEquals(400, get("").statusCode());
    }

    @Test
    @DisplayName("GET outside the allowed roots returns 403")
    void rejectsForeignPaths() throws Exception {
        assertEquals(403, get("path=/content/we-retail").statusCode());
    }
}

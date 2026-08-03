package com.dept.contentbridge.core.servlets;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.servlet.Servlet;

import org.apache.commons.lang3.StringUtils;
import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.SlingHttpServletResponse;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.servlets.SlingSafeMethodsServlet;
import org.apache.sling.servlets.annotations.SlingServletPaths;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.dept.contentbridge.core.services.PageJsonExporter;
import com.day.cq.wcm.api.Page;
import com.day.cq.wcm.api.PageManager;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

/**
 * The ContentBridge read API.
 *
 * <pre>
 * GET /bin/contentbridge/pages?path=/content/contentbridge/us/en
 * GET /bin/contentbridge/pages?path=/content/contentbridge/us/en/subpage-1&amp;pretty=true
 * </pre>
 *
 * <p>Responses are {@code application/json;charset=UTF-8}. Errors are returned as a JSON body with
 * an {@code error} key so clients never have to parse an HTML error page.</p>
 */
@Component(service = Servlet.class,
           property = {"service.description=ContentBridge - Page JSON API"})
@SlingServletPaths(value = PageJsonServlet.SERVLET_PATH)
public class PageJsonServlet extends SlingSafeMethodsServlet {

    static final String SERVLET_PATH = "/bin/contentbridge/pages";

    private static final long serialVersionUID = 1L;
    private static final Logger LOG = LoggerFactory.getLogger(PageJsonServlet.class);

    private static final String PARAM_PATH = "path";
    private static final String PARAM_PRETTY = "pretty";
    private static final String CONTENT_TYPE = "application/json";

    private final transient ObjectMapper mapper = new ObjectMapper();

    @Reference
    private transient PageJsonExporter exporter;

    @Override
    protected void doGet(final SlingHttpServletRequest request, final SlingHttpServletResponse response)
            throws IOException {

        response.setContentType(CONTENT_TYPE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());

        final String path = StringUtils.trimToEmpty(request.getParameter(PARAM_PATH));

        if (StringUtils.isEmpty(path)) {
            writeError(response, SlingHttpServletResponse.SC_BAD_REQUEST,
                    "Missing required query parameter 'path'.");
            return;
        }
        if (!exporter.isAllowed(path)) {
            LOG.warn("Rejected ContentBridge export for path outside the allowed roots: {}", path);
            writeError(response, SlingHttpServletResponse.SC_FORBIDDEN,
                    "Path is not exposed by this API.");
            return;
        }

        final ResourceResolver resolver = request.getResourceResolver();
        final PageManager pageManager = resolver.adaptTo(PageManager.class);
        final Page page = pageManager == null ? null : pageManager.getPage(path);

        if (page == null) {
            writeError(response, SlingHttpServletResponse.SC_NOT_FOUND,
                    "No page exists at '" + path + "'.");
            return;
        }

        try {
            final Map<String, Object> payload = exporter.export(page);
            final int maxAge = exporter.getCacheMaxAge();
            if (maxAge > 0) {
                response.setHeader("Cache-Control", "max-age=" + maxAge);
            } else {
                response.setHeader("Cache-Control", "no-store");
            }
            response.setStatus(SlingHttpServletResponse.SC_OK);
            writer(request).writeValue(response.getWriter(), payload);
        } catch (final IOException e) {
            LOG.error("Failed to serialise ContentBridge payload for {}", path, e);
            writeError(response, SlingHttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                    "Could not serialise the page.");
        }
    }

    private com.fasterxml.jackson.databind.ObjectWriter writer(final SlingHttpServletRequest request) {
        final boolean pretty = Boolean.parseBoolean(request.getParameter(PARAM_PRETTY));
        return pretty
                ? mapper.writer().with(SerializationFeature.INDENT_OUTPUT)
                : mapper.writer();
    }

    private void writeError(final SlingHttpServletResponse response, final int status, final String message)
            throws IOException {
        response.setStatus(status);
        response.setHeader("Cache-Control", "no-store");
        final Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", status);
        body.put("error", message);
        mapper.writeValue(response.getWriter(), body);
    }
}

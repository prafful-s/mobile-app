package com.dept.contentbridge.core.servlets;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Scanner;

import javax.servlet.Servlet;

import org.apache.commons.lang3.StringUtils;
import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.SlingHttpServletResponse;
import org.apache.sling.api.resource.LoginException;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceResolverFactory;
import org.apache.sling.api.servlets.HttpConstants;
import org.apache.sling.api.servlets.SlingSafeMethodsServlet;
import org.apache.sling.servlets.annotations.SlingServletResourceTypes;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.dept.contentbridge.core.services.ExportOptions;
import com.dept.contentbridge.core.services.PageJsonExporter;
import com.day.cq.wcm.api.Page;
import com.day.cq.wcm.api.PageManager;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

/**
 * The ContentBridge read API.
 *
 * <pre>
 * GET /apps/contentbridge/api.json?path=/content/contentbridge/us/en
 * GET /apps/contentbridge/api.json?path=/content/contentbridge/us/en/subpage-1&amp;pretty=true
 * </pre>
 *
 * <p>Bound by <strong>resource type</strong> rather than by path. Adobe's guidance for AEM as a
 * Cloud Service is to avoid {@code @SlingServletPaths}: path-bound servlets bypass Sling's resource
 * resolution, so the repository ACLs on the requested resource are never consulted, they cannot be
 * cached by the dispatcher against a real resource, and they sit outside the standard
 * resource/selector/extension contract. Binding to {@code contentbridge/api} means the request first
 * resolves a real node, which the requesting user must be allowed to read.</p>
 *
 * <p>Content is read through the {@code contentbridge-reader} system user, which repoinit grants
 * {@code jcr:read} on {@code /content}. That is what lets one endpoint serve every site on the
 * instance, including projects that know nothing about ContentBridge, and it means the response does
 * <em>not</em> reflect the requesting user's permissions - {@code allowedRoots} is the access
 * boundary, so keep it meaningful.</p>
 *
 * <p>If the service user cannot be obtained or cannot see the page, the request falls back to the
 * caller's own resolver and logs a warning. That keeps a misconfigured service user from taking the
 * endpoint down on author, where the caller can usually read the content anyway - but it is a
 * warning, not a solution: on publish the caller is anonymous and there is nothing to fall back to.</p>
 *
 * <p>Responses are {@code application/json;charset=UTF-8}. Errors are returned as a JSON body with
 * an {@code error} key so clients never have to parse an HTML error page.</p>
 */
@Component(service = Servlet.class,
           property = {"service.description=ContentBridge - Page JSON API"})
@SlingServletResourceTypes(
        resourceTypes = PageJsonServlet.RESOURCE_TYPE,
        methods = HttpConstants.METHOD_GET,
        extensions = PageJsonServlet.EXTENSION)
public class PageJsonServlet extends SlingSafeMethodsServlet {

    /** Resource type the endpoint node carries; the node itself lives at /apps/contentbridge/api. */
    public static final String RESOURCE_TYPE = "contentbridge/api";

    static final String EXTENSION = "json";

    private static final long serialVersionUID = 1L;
    private static final Logger LOG = LoggerFactory.getLogger(PageJsonServlet.class);

    private static final String PARAM_PATH = "path";
    private static final String PARAM_PRETTY = "pretty";

    private static final String CONTENT_TYPE = "application/json";

    /**
     * Sub-service name. Mapped to the {@code contentbridge-reader} system user, which repoinit
     * grants {@code jcr:read} on {@code /content}.
     */
    private static final String SUBSERVICE = "contentbridge-reader";

    private final transient ObjectMapper mapper = new ObjectMapper();

    @Reference
    private transient PageJsonExporter exporter;

    @Reference
    private transient ResourceResolverFactory resolverFactory;

    /**
     * Classpath resource served verbatim in place of the exporter output. Temporary bridge so the
     * mobile-app team can wire against a stable JSON shape while the real content authoring lands
     * in AEM. Delete this constant and the short-circuit below to restore the exporter path.
     */
    private static final String MOCK_RESOURCE = "/contentbridge/mock/luma-mobile.json";

    @Override
    protected void doGet(final SlingHttpServletRequest request, final SlingHttpServletResponse response)
            throws IOException {

        response.setContentType(CONTENT_TYPE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());

        final String path = StringUtils.trimToEmpty(request.getParameter(PARAM_PATH));
        logConsumer(request, path);

        // ponytail: temporary hardcoded response. Servlet returns the mock JSON on every request
        // for every path the exporter would otherwise handle. Remove this block to reinstate the
        // real export path.
        final String mock = readMock();
        if (mock != null) {
            response.setHeader("Cache-Control", "no-store");
            response.setStatus(SlingHttpServletResponse.SC_OK);
            response.getWriter().write(mock);
            LOG.info("ContentBridge served MOCK path={} bytes={} body={}", path, mock.length(), mock);
            return;
        }

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

        // Prefer the contentbridge-reader system user so the API can serve every site under /content
        // regardless of who is asking. 'allowedRoots' is the access boundary, not the caller's ACLs.
        try (ResourceResolver serviceResolver = openServiceResolver()) {

            Page page = findPage(serviceResolver, path);

            if (page == null) {
                // The service user could not see it. That is either a genuine 404 or a
                // misconfigured grant, and the two are indistinguishable from here - so fall back to
                // the caller, who on author is usually an author or admin and can read it fine.
                final Page viaRequest = findPage(request.getResourceResolver(), path);
                if (viaRequest != null) {
                    LOG.warn("Page {} was not readable by service user '{}' but is readable by the "
                            + "requesting user - serving it. Check that repoinit granted jcr:read on "
                            + "/content and that the user sits under system/cq:services.",
                            path, SUBSERVICE);
                    page = viaRequest;
                }
            }

            if (page == null) {
                writeError(response, SlingHttpServletResponse.SC_NOT_FOUND,
                        "No page exists at '" + path + "'.");
                return;
            }

            // Screen content is reduced to the enabled component set (banner/card/htmlText/form).
            final Map<String, Object> payload = exporter.export(page, ExportOptions.of(true));
            final int maxAge = exporter.getCacheMaxAge();
            if (maxAge > 0) {
                response.setHeader("Cache-Control", "max-age=" + maxAge);
            } else {
                response.setHeader("Cache-Control", "no-store");
            }
            response.setStatus(SlingHttpServletResponse.SC_OK);
            // Serialise once to a string so the same bytes go to both the response and the
            // "excessive" access log below - avoids Jackson streaming the payload twice.
            final String body = writer(request).writeValueAsString(payload);
            response.getWriter().write(body);
            LOG.info("ContentBridge served path={} bytes={} body={}", path, body.length(), body);

        } catch (final IOException e) {
            LOG.error("Failed to serialise ContentBridge payload for {}", path, e);
            writeError(response, SlingHttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                    "Could not serialise the page.");
        } catch (final RuntimeException e) {
            // Without this, a bug in any component model escapes to Sling's default handler and the
            // client receives an HTML error page - breaking the promise that errors are always JSON,
            // for exactly the clients least able to cope with HTML.
            LOG.error("Unexpected failure exporting {}", path, e);
            writeError(response, SlingHttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                    "Could not export the page.");
        }
    }

    /**
     * Opens the service-user resolver, or null when it cannot be obtained. A missing or misconfigured
     * service user degrades to the requesting user rather than taking the endpoint down.
     */
    /**
     * Reads the mock JSON off the bundle classpath. Returns null when the resource is absent so
     * a redeploy without the file quietly falls through to the real exporter. Read on every
     * request so a `mvn -PautoInstallBundle` refresh reloads the file without an OSGi restart.
     */
    private static String readMock() {
        try (InputStream in = PageJsonServlet.class.getResourceAsStream(MOCK_RESOURCE)) {
            if (in == null) {
                return null;
            }
            try (Scanner scanner = new Scanner(in, StandardCharsets.UTF_8.name()).useDelimiter("\\A")) {
                return scanner.hasNext() ? scanner.next() : "";
            }
        } catch (final IOException e) {
            LOG.warn("Could not read mock resource {}", MOCK_RESOURCE, e);
            return null;
        }
    }

    /**
     * Excessive access log: who called the API and with what. Emitted at INFO so it shows up
     * without a debug config change. Reads only headers the servlet API already exposes -
     * request line, remote address, User-Agent, Referer, authenticated user - and includes the
     * decoded query string so a consumer can be traced back to what they asked for.
     */
    private static void logConsumer(final SlingHttpServletRequest request, final String path) {
        LOG.info("ContentBridge request path={} remote={} user={} ua={} referer={} query={}",
                path,
                StringUtils.defaultIfEmpty(request.getRemoteAddr(), "-"),
                StringUtils.defaultIfEmpty(request.getRemoteUser(), "-"),
                StringUtils.defaultIfEmpty(request.getHeader("User-Agent"), "-"),
                StringUtils.defaultIfEmpty(request.getHeader("Referer"), "-"),
                StringUtils.defaultIfEmpty(request.getQueryString(), "-"));
    }

    private ResourceResolver openServiceResolver() {
        try {
            return resolverFactory.getServiceResourceResolver(
                    Collections.singletonMap(ResourceResolverFactory.SUBSERVICE, SUBSERVICE));
        } catch (final LoginException e) {
            LOG.warn("Could not open a resolver for sub-service '{}' - falling back to the requesting "
                    + "user. Check the repoinit service user and the ServiceUserMapper amendment.",
                    SUBSERVICE, e);
            return null;
        }
    }

    private Page findPage(final ResourceResolver resolver, final String path) {
        if (resolver == null) {
            return null;
        }
        final PageManager pageManager = resolver.adaptTo(PageManager.class);
        return pageManager == null ? null : pageManager.getPage(path);
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

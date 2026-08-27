package com.edsMobileApp.core.servlets;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.servlet.Servlet;

import org.apache.commons.lang3.StringUtils;
import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.SlingHttpServletResponse;
import org.apache.sling.api.resource.LoginException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceResolverFactory;
import org.apache.sling.api.servlets.HttpConstants;
import org.apache.sling.api.servlets.SlingSafeMethodsServlet;
import org.apache.sling.servlets.annotations.SlingServletResourceTypes;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.edsMobileApp.core.services.ExportOptions;
import com.edsMobileApp.core.services.PageJsonExporter;
import com.day.cq.wcm.api.Page;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * The ContentBridge read API.
 *
 * <pre>
 * GET /apps/contentbridge/api.json?path=/content/contentbridge/us/en
 * GET /apps/contentbridge/api.json?path=/content/contentbridge/us/en/subpage-1
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
 * <em>not</em> reflect the requesting user's permissions - the service user's own {@code jcr:read}
 * grant is the access boundary, so keep it meaningful.</p>
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
        // Prefer the contentbridge-reader system user so the API can serve every site under /content
        // regardless of who is asking.
        try (ResourceResolver serviceResolver = openServiceResolver()) {

            Resource resource = findResource(serviceResolver, path);

            if (resource == null) {
                // The service user could not see it. That is either a genuine 404 or a
                // misconfigured grant, and the two are indistinguishable from here - so fall back to
                // the caller, who on author is usually an author or admin and can read it fine.
                final Resource viaRequest = findResource(request.getResourceResolver(), path);
                if (viaRequest != null) {
                    LOG.warn("Path {} was not readable by service user '{}' but is readable by the "
                            + "requesting user - serving it. Check that repoinit granted jcr:read on "
                            + "/content and that the user sits under system/cq:services.",
                            path, SUBSERVICE);
                    resource = viaRequest;
                }
            }

            if (resource == null) {
                writeError(response, SlingHttpServletResponse.SC_NOT_FOUND,
                        "No resource exists at '" + path + "'.");
                return;
            }

            // Local-iteration override: a hand-crafted JSON payload dropped on the node is served
            // verbatim, so a response can be shaped at any /content path without a rebuild.
            if (exporter.isOverrideEnabled()) {
                final String raw = rawJsonOverride(resource, exporter.getOverridePropertyName());
                if (raw != null) {
                    response.setHeader("Cache-Control", "no-store");
                    response.setStatus(SlingHttpServletResponse.SC_OK);
                    response.getWriter().write(raw);
                    return;
                }
            }

            final Page page = resource.adaptTo(Page.class);
            if (page == null) {
                writeError(response, SlingHttpServletResponse.SC_NOT_FOUND,
                        "No page exists at '" + path + "'.");
                return;
            }

            // Screen content is reduced to the enabled component set (banner/card/htmlText/form).
            final Map<String, Object> payload = exporter.export(page, ExportOptions.of(true));
            response.setHeader("Cache-Control", "no-store");
            response.setStatus(SlingHttpServletResponse.SC_OK);
            // Serialise once to a string so the same bytes go to both the response and the
            // "excessive" access log below - avoids Jackson streaming the payload twice.
            final String body = mapper.writeValueAsString(payload);
            response.getWriter().write(body);
            LOG.debug("ContentBridge served path={} bytes={} body={}", path, body.length(), body);

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

    private Resource findResource(final ResourceResolver resolver, final String path) {
        if (resolver == null) {
            return null;
        }
        return resolver.getResource(path);
    }

    /**
     * Returns a raw JSON payload authored on the resource, or null when there is none.
     *
     * <p>Checked, in order: the override String property on the node, the same property on its
     * {@code jcr:content} child, and a {@code .json} file at the path. The payload is served exactly
     * as stored, so it is the author's responsibility that it is valid JSON.</p>
     */
    private String rawJsonOverride(final Resource resource, final String propertyName) {
        final String onNode = resource.getValueMap().get(propertyName, String.class);
        if (StringUtils.isNotBlank(onNode)) {
            return onNode;
        }
        final Resource content = resource.getChild("jcr:content");
        if (content != null) {
            final String onContent = content.getValueMap().get(propertyName, String.class);
            if (StringUtils.isNotBlank(onContent)) {
                return onContent;
            }
        }
        return jsonFile(resource);
    }

    /**
     * Streams a {@code .json} file at the resource as text, or null when the resource is not one.
     * Guards on name and mime type so pointing the endpoint at a non-JSON binary falls through to
     * the normal export rather than emitting bytes as a JSON body.
     */
    private String jsonFile(final Resource resource) {
        final Resource content = resource.getChild("jcr:content");
        final String mime = content == null ? null
                : content.getValueMap().get("jcr:mimeType", String.class);
        final boolean looksJson = StringUtils.endsWithIgnoreCase(resource.getName(), ".json")
                || StringUtils.equalsIgnoreCase(mime, CONTENT_TYPE);
        if (!looksJson) {
            return null;
        }
        try (java.io.InputStream in = resource.adaptTo(java.io.InputStream.class)) {
            if (in == null) {
                return null;
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (final IOException e) {
            LOG.warn("Could not read JSON override file at {}", resource.getPath(), e);
            return null;
        }
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

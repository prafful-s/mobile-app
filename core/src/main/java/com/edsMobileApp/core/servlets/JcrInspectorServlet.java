package com.edsMobileApp.core.servlets;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import javax.servlet.Servlet;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.math.NumberUtils;
import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.SlingHttpServletResponse;
import org.apache.sling.api.resource.LoginException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceResolverFactory;
import org.apache.sling.api.resource.ValueMap;
import org.apache.sling.api.servlets.HttpConstants;
import org.apache.sling.api.servlets.SlingSafeMethodsServlet;
import org.apache.sling.servlets.annotations.SlingServletResourceTypes;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Local dev tool: dumps the raw JCR content at an arbitrary {@code path} as JSON, alongside the set
 * of unique Edge Delivery/Franklin blocks authored anywhere underneath it.
 *
 * <pre>
 * GET /apps/edsMobileApp/jcrapi.json?path=/content/wknd-fly/language-masters/en
 * GET /apps/edsMobileApp/jcrapi.json?path=/content/wknd-fly/language-masters/en/home&amp;depth=4
 * </pre>
 *
 * <p>Unrelated to the mobile export ({@link PageJsonServlet}): this is a raw JCR viewer for
 * inspecting the sites being migrated to the da.live platform, not a shaped mobile payload. Bound by
 * resource type for the same reason as {@link PageJsonServlet} - see its javadoc.</p>
 *
 * <p>The {@code content} tree is capped by {@code depth} (default {@value #DEFAULT_DEPTH}, hard
 * ceiling {@value #MAX_DEPTH}) so pointing this at a whole site does not attempt to inline every page
 * of markup in one response. Block discovery is a separate, much cheaper walk (resource type and one
 * property per node) that always covers the full subtree regardless of {@code depth} - so aiming this
 * at a site root, e.g. a language master, yields every block used anywhere on that site.</p>
 *
 * <p>Franklin does not give each block its own resource type: every authored block shares
 * {@code core/franklin/components/block/v1/block}, and its actual identity lives in its own
 * {@code model} property (see {@code SchemaConstants} on {@link PageJsonServlet}'s exporter) - this
 * is also the exact folder name a block lives under in the target boilerplate repo (e.g.
 * {@code model: "flight-search"} &#8596; {@code blocks/flight-search}), which is what makes it the key
 * to match against when migrating a block to da.live.</p>
 *
 * <p>Not every block authors a {@code model} - a Cards group, for one, sets only {@code name} and
 * {@code filter}. Falling back to the JCR node name in that case is wrong: Universal Editor names
 * nodes off an authoring timestamp, so unrelated Cards instances across pages built at the same
 * moment (e.g. via a live copy) collide on the same node name and get miscounted as one "block". The
 * fallback here instead slugifies {@code name} (e.g. {@code "Cards"} &#8594; {@code cards}), which is
 * how those blocks' folder names are actually derived.</p>
 *
 * <p>A block's children can themselves be {@code core/franklin/components/block/v1/block/item} nodes
 * - e.g. each {@code card} inside a {@code cards} block. An item is not a standalone block (it has no
 * folder of its own in the target repo; it is markup the owning block's own code renders), so it is
 * never counted alongside real blocks - it is nested under its nearest enclosing block's own
 * {@code blockItems}, grouped and named the same way a block is.</p>
 *
 * <p>Not every block is authored through the generic {@code model} mechanism. A handful of built-in
 * Franklin components - {@code title}, {@code text}, {@code image}, {@code button}, {@code columns} -
 * each carry their own dedicated resource type instead (see {@link #NATIVE_BLOCK_TYPES}) and author no
 * {@code model} property at all; some (plain {@code text}) author no {@code name} either, so their
 * resource type's own last path segment is the only reliable identity they have. These are still
 * counted as blocks, keyed by that fixed name. A custom block referencing a shared page fragment
 * (e.g. {@code model: "fragment"}) needs no special handling here - the target repo's
 * {@code component-definition.json} plugs it in exactly like {@code hero} or {@code cards}, through
 * the same generic {@code model} mechanism.</p>
 *
 * <p>Every occurrence's {@code modelFields} property - the field names the block/component declares as
 * authorable - is unioned per block key, so the response also shows the full set of fields ever
 * authored on a given block across the scanned subtree.</p>
 */
@Component(service = Servlet.class,
           property = {"service.description=EdsMobileApp - JCR Inspector API"})
@SlingServletResourceTypes(
        resourceTypes = JcrInspectorServlet.RESOURCE_TYPE,
        methods = HttpConstants.METHOD_GET,
        extensions = JcrInspectorServlet.EXTENSION)
public class JcrInspectorServlet extends SlingSafeMethodsServlet {

    /** Resource type the endpoint node carries; the node itself can live anywhere, e.g.
     *  {@code /content/contentbridge/api/getJcrContent}. */
    public static final String RESOURCE_TYPE = "edsMobileApp/jcrapi";

    static final String EXTENSION = "json";

    private static final long serialVersionUID = 1L;
    private static final Logger LOG = LoggerFactory.getLogger(JcrInspectorServlet.class);

    private static final String PARAM_PATH = "path";
    private static final String PARAM_DEPTH = "depth";

    private static final String CONTENT_TYPE = "application/json";

    /** How many levels of children the raw {@code content} tree descends by default. */
    private static final int DEFAULT_DEPTH = 10;

    /** Hard ceiling on {@code depth}, regardless of what the caller asks for. */
    private static final int MAX_DEPTH = 25;

    /** Block discovery ignores {@code depth} but is still bounded, against a pathological tree. */
    private static final int BLOCK_SCAN_MAX_DEPTH = 60;

    /** Every Franklin block, whatever it renders, shares this resource type - see class javadoc. */
    private static final String RT_FRANKLIN_BLOCK = "core/franklin/components/block/v1/block";

    /** A block's own sub-item (e.g. a {@code card} inside {@code cards}) - see class javadoc. */
    private static final String RT_FRANKLIN_BLOCK_ITEM = "core/franklin/components/block/v1/block/item";

    /**
     * Built-in Franklin components that carry their own resource type instead of going through the
     * generic {@link #RT_FRANKLIN_BLOCK} + {@code model} mechanism, mapped to the fixed key each is
     * reported under - see class javadoc. {@code root} and {@code section} are deliberately excluded:
     * pure layout, never content, on every one of these sites.
     */
    private static final Map<String, String> NATIVE_BLOCK_TYPES = Map.of(
            "core/franklin/components/title/v1/title", "title",
            "core/franklin/components/text/v1/text", "text",
            "core/franklin/components/image/v1/image", "image",
            "core/franklin/components/button/v1/button", "button",
            "core/franklin/components/columns/v1/columns", "columns");

    /** Names the block on a {@link #RT_FRANKLIN_BLOCK} node - the target repo's blocks/ folder name. */
    private static final String PN_MODEL = "model";

    /** Human-readable label, e.g. "Cards" - the only identity a Cards group authors (see class javadoc). */
    private static final String PN_NAME = "name";

    /** The block/component's own declared list of authorable field names - see class javadoc. */
    private static final String PN_MODEL_FIELDS = "modelFields";

    /** Never worth inlining: large, and irrelevant to a block/content inspection. */
    private static final String PN_JCR_DATA = "jcr:data";

    /** Same sub-service {@link PageJsonServlet} uses - see its javadoc for the repoinit grant. */
    private static final String SUBSERVICE = "edsMobileApp-reader";

    private final transient ObjectMapper mapper = new ObjectMapper();

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

        final int depth = resolveDepth(request.getParameter(PARAM_DEPTH));

        try (ResourceResolver serviceResolver = openServiceResolver()) {

            Resource resource = findResource(serviceResolver, path);
            if (resource == null) {
                // Same author-vs-service-user fallback as PageJsonServlet - see its javadoc.
                final Resource viaRequest = findResource(request.getResourceResolver(), path);
                if (viaRequest != null) {
                    LOG.warn("Path {} was not readable by service user '{}' but is readable by the "
                            + "requesting user - serving it.", path, SUBSERVICE);
                    resource = viaRequest;
                }
            }

            if (resource == null) {
                writeError(response, SlingHttpServletResponse.SC_NOT_FOUND,
                        "No resource exists at '" + path + "'.");
                return;
            }

            final Map<String, Object> content = toJson(resource, 0, depth);

            final Map<String, BlockInfo> blocksByKey = new TreeMap<>();
            collectBlocks(resource, 0, blocksByKey, null);

            final List<Map<String, Object>> blockList = new ArrayList<>();
            for (final BlockInfo info : blocksByKey.values()) {
                blockList.add(blockInfoToJson(info));
            }
            final Map<String, Object> blocks = new LinkedHashMap<>();
            blocks.put("count", blockList.size());
            blocks.put("items", blockList);

            final Map<String, Object> payload = new LinkedHashMap<>();
            payload.put(PARAM_PATH, resource.getPath());
            payload.put("resourceType", resource.getResourceType());
            payload.put(PARAM_DEPTH, depth);
            payload.put("content", content);
            payload.put("blocks", blocks);

            response.setHeader("Cache-Control", "no-store");
            response.setStatus(SlingHttpServletResponse.SC_OK);
            mapper.writeValue(response.getWriter(), payload);

        } catch (final IOException e) {
            LOG.error("Failed to serialise JCR inspection payload for {}", path, e);
            writeError(response, SlingHttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                    "Could not serialise the resource.");
        } catch (final RuntimeException e) {
            LOG.error("Unexpected failure inspecting {}", path, e);
            writeError(response, SlingHttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                    "Could not inspect the resource.");
        }
    }

    /** Clamps the caller's requested depth into {@code [0, MAX_DEPTH]}, defaulting when absent/invalid. */
    private static int resolveDepth(final String rawDepth) {
        if (StringUtils.isBlank(rawDepth) || !NumberUtils.isDigits(rawDepth)) {
            return DEFAULT_DEPTH;
        }
        return Math.min(NumberUtils.toInt(rawDepth, DEFAULT_DEPTH), MAX_DEPTH);
    }

    private ResourceResolver openServiceResolver() {
        try {
            return resolverFactory.getServiceResourceResolver(
                    Collections.singletonMap(ResourceResolverFactory.SUBSERVICE, SUBSERVICE));
        } catch (final LoginException e) {
            LOG.warn("Could not open a resolver for sub-service '{}' - falling back to the requesting "
                    + "user.", SUBSERVICE, e);
            return null;
        }
    }

    private Resource findResource(final ResourceResolver resolver, final String path) {
        return resolver == null ? null : resolver.getResource(path);
    }

    /**
     * Recursively projects {@code resource} and its children into a JSON-friendly tree: every
     * property of the node, plus one entry per child keyed by child name. Stops descending past
     * {@code maxDepth}, marking a node that still had children with {@code _truncated}.
     */
    private Map<String, Object> toJson(final Resource resource, final int currentDepth, final int maxDepth) {
        final Map<String, Object> node = new LinkedHashMap<>();
        final ValueMap valueMap = resource.getValueMap();
        for (final Map.Entry<String, Object> property : valueMap.entrySet()) {
            if (PN_JCR_DATA.equals(property.getKey())) {
                continue;
            }
            node.put(property.getKey(), convertValue(property.getValue()));
        }

        if (currentDepth >= maxDepth) {
            if (resource.hasChildren()) {
                node.put("_truncated", true);
            }
            return node;
        }

        for (final Resource child : resource.getChildren()) {
            if (isSkippedChild(child)) {
                continue;
            }
            node.put(child.getName(), toJson(child, currentDepth + 1, maxDepth));
        }
        return node;
    }

    /** Skips a DAM asset's renditions - large binary bookkeeping, never a block or authored content. */
    private static boolean isSkippedChild(final Resource child) {
        return "renditions".equals(child.getName());
    }

    /** Converts a raw {@link ValueMap} value into something Jackson renders sensibly. */
    private Object convertValue(final Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof java.util.Calendar) {
            final java.util.Calendar calendar = (java.util.Calendar) value;
            return DateTimeFormatter.ISO_OFFSET_DATE_TIME
                    .format(calendar.toInstant().atZone(ZoneId.of(calendar.getTimeZone().getID())));
        }
        if (value instanceof Object[]) {
            final Object[] values = (Object[]) value;
            final List<Object> converted = new ArrayList<>(values.length);
            for (final Object item : values) {
                converted.add(convertValue(item));
            }
            return converted;
        }
        if (value instanceof byte[] || value instanceof InputStream) {
            return "<binary>";
        }
        if (value instanceof String || value instanceof Number || value instanceof Boolean) {
            return value;
        }
        return String.valueOf(value);
    }

    /**
     * Walks the full subtree under {@code resource} - ignoring {@code depth} - grouping every
     * Franklin block found by its {@link #blockKey}, and every block <em>item</em> found underneath
     * {@code parentBlock}'s own {@code blockItemsByKey}. Bounded by {@link #BLOCK_SCAN_MAX_DEPTH}
     * rather than the caller's depth, so aiming this at a whole site still finds every block on it.
     *
     * @param parentBlock the nearest enclosing {@link #RT_FRANKLIN_BLOCK} above {@code resource}, or
     *                    {@code null} above the first one - an item found with no enclosing block
     *                    (should not happen, but the tree is author-controlled) is simply not counted
     */
    private void collectBlocks(final Resource resource, final int currentDepth,
                               final Map<String, BlockInfo> blocksByKey, final BlockInfo parentBlock) {
        if (currentDepth > BLOCK_SCAN_MAX_DEPTH) {
            LOG.warn("Block scan below {} exceeds {} levels - not descending further",
                    resource.getPath(), BLOCK_SCAN_MAX_DEPTH);
            return;
        }
        final String resourceType = resource.getResourceType();
        BlockInfo nextParent = parentBlock;
        final String nativeKey = NATIVE_BLOCK_TYPES.get(resourceType);
        if (RT_FRANKLIN_BLOCK.equals(resourceType)) {
            nextParent = recordBlock(resource, blocksByKey, null);
        } else if (RT_FRANKLIN_BLOCK_ITEM.equals(resourceType) && parentBlock != null) {
            // An item does not host blocks/items of its own in any of these sites today, so it is not
            // promoted to the new nearest-enclosing-block - it stays attributed to parentBlock.
            recordBlock(resource, parentBlock.blockItemsByKey, null);
        } else if (nativeKey != null) {
            // A built-in component (title/text/image/button/columns) is always its own top-level
            // block, even when it sits inside a columns layout or another block's own markup - it
            // does not nest under the enclosing block the way a block/item does.
            recordBlock(resource, blocksByKey, nativeKey);
        }
        for (final Resource child : resource.getChildren()) {
            if (isSkippedChild(child)) {
                continue;
            }
            collectBlocks(child, currentDepth + 1, blocksByKey, nextParent);
        }
    }

    /**
     * Resolves {@code resource}'s {@link #blockKey} and records one more occurrence of it in
     * {@code sink} - shared by top-level blocks, a block's own {@code blockItemsByKey}, and the
     * built-in {@link #NATIVE_BLOCK_TYPES}, since all three are grouped the same way and all three
     * want the same {@code modelFields} union merged in.
     *
     * @param fixedKey the key to group under for a {@link #NATIVE_BLOCK_TYPES} component, which has no
     *                 {@code model} of its own to derive one from; {@code null} to derive it normally
     */
    private BlockInfo recordBlock(final Resource resource, final Map<String, BlockInfo> sink,
                                  final String fixedKey) {
        final ValueMap valueMap = resource.getValueMap();
        final String model = StringUtils.trimToNull(valueMap.get(PN_MODEL, String.class));
        final String name = StringUtils.trimToNull(valueMap.get(PN_NAME, String.class));
        final String key = fixedKey != null ? fixedKey : blockKey(model, name, resource.getName());
        final BlockInfo info = sink.computeIfAbsent(key, k -> new BlockInfo(k, model, name));
        info.count++;
        if (info.model == null) {
            info.model = model;
        }
        if (info.name == null) {
            info.name = name;
        }
        final String[] modelFields = valueMap.get(PN_MODEL_FIELDS, String[].class);
        if (modelFields != null) {
            for (final String field : modelFields) {
                if (StringUtils.isNotBlank(field)) {
                    info.modelFields.add(field);
                }
            }
        }
        return info;
    }

    /**
     * The identity to group a block instance under - the same key the target boilerplate repo's
     * {@code blocks/} folder is named after. {@code model} wins when authored; otherwise a slug of
     * {@code name} (the shape a Cards group takes); the raw node name is a last resort for a block
     * that authored neither, so it never silently drops a block, only rarely mis-labels it.
     */
    private static String blockKey(final String model, final String name, final String nodeName) {
        if (model != null) {
            return model;
        }
        if (name != null) {
            return slugify(name);
        }
        return nodeName;
    }

    /** Lowercases and hyphenates {@code value}, e.g. {@code "Cards"} -&gt; {@code cards}. */
    private static String slugify(final String value) {
        final String slug = value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-");
        return StringUtils.strip(slug, "-");
    }

    /**
     * Converts a {@link BlockInfo} - and, recursively, its {@code blockItemsByKey} - into the shape
     * served in the response.
     */
    private Map<String, Object> blockInfoToJson(final BlockInfo info) {
        final Map<String, Object> item = new LinkedHashMap<>();
        item.put("key", info.key);
        item.put("model", info.model);
        item.put("name", info.name);
        item.put("count", info.count);
        item.put("modelFields", new ArrayList<>(info.modelFields));
        final List<Map<String, Object>> blockItems = new ArrayList<>();
        for (final BlockInfo nested : info.blockItemsByKey.values()) {
            blockItems.add(blockInfoToJson(nested));
        }
        item.put("blockItems", blockItems);
        return item;
    }

    /**
     * One unique block (or block item) identity discovered by {@link #collectBlocks}: how often it
     * recurs, the union of every {@code modelFields} entry authored on any occurrence of it, and -
     * for a top-level block - the items authored inside it, by the same identity rules.
     */
    private static final class BlockInfo {
        private final String key;
        private String model;
        private String name;
        private int count;
        private final Set<String> modelFields = new LinkedHashSet<>();
        private final Map<String, BlockInfo> blockItemsByKey = new TreeMap<>();

        BlockInfo(final String key, final String model, final String name) {
            this.key = key;
            this.model = model;
            this.name = name;
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

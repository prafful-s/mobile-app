package com.dept.contentbridge.core.services.impl;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Arrays;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.math.NumberUtils;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ValueMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.day.cq.wcm.api.Page;

/**
 * Reads a site's products from Content Fragments and projects them onto one fixed key set.
 *
 * <p>Every site models its catalogue slightly differently - one calls the field {@code sku}, the next
 * {@code productSku}, a third {@code code} - but clients need a single stable product shape. So the
 * mapping is by <em>alias</em>: each output key lists the element names that may supply it, first
 * match wins, and the alias table is OSGi-configurable so a new model needs no code change.</p>
 *
 * <p>All keys are always present. A missing string becomes {@code ""} and a missing list becomes
 * {@code []}, so consumers can index into the shape without null checks. {@code price} is the
 * exception: absent means unknown, and emitting {@code 0} there would read as "free".</p>
 */
final class ProductExporter {

    private static final Logger LOG = LoggerFactory.getLogger(ProductExporter.class);

    private static final String DAM_ROOT = "/content/dam/";

    /** Output keys, in the order they appear in the response. */
    static final List<String> KEYS = List.of(
            "id", "sku", "name", "image", "price", "type_id", "category", "thumbnail",
            "description", "category_ids", "importedFrom", "category_uids", "short_description");

    /** Keys whose value is a list of integers. */
    private static final Set<String> INTEGER_LIST_KEYS = Set.of("category_ids");

    /** Keys whose value is a list of strings. */
    private static final Set<String> STRING_LIST_KEYS = Set.of("category_uids");

    private static final String KEY_PRICE = "price";

    /** Keys that fall back to the resource or item name rather than being left empty. */
    private static final Set<String> IDENTITY_KEYS = Set.of("id", "sku", "name");

    /** Placeholder entry and page property naming the catalogue folder. */
    private static final String PN_PRODUCTS_PATH = "productsPath";

    /** Folder visit budget for the catalogue search. See {@code searchForFolder}. */
    private static final int MAX_FOLDERS_SCANNED = 5000;

    /**
     * Default element-name candidates per output key. The output key itself is always tried first.
     */
    private static final Map<String, List<String>> DEFAULT_ALIASES;

    static {
        final Map<String, List<String>> defaults = new LinkedHashMap<>();
        defaults.put("id", List.of("id", "productId", "identifier", "sku"));
        defaults.put("sku", List.of("sku", "productSku", "code", "id"));
        defaults.put("name", List.of("name", "productName", "title", "jcr:title"));
        defaults.put("image", List.of("image", "mainImage", "imageUrl", "fileReference", "asset"));
        defaults.put(KEY_PRICE, List.of("price", "amount", "listPrice"));
        defaults.put("type_id", List.of("type_id", "typeId", "type", "productType"));
        defaults.put("category", List.of("category", "categoryName", "categoryPath"));
        defaults.put("thumbnail", List.of("thumbnail", "thumbnailUrl", "image"));
        defaults.put("description", List.of("description", "longDescription", "body", "text"));
        defaults.put("category_ids", List.of("category_ids", "categoryIds"));
        defaults.put("importedFrom", List.of("importedFrom", "source", "origin"));
        defaults.put("category_uids", List.of("category_uids", "categoryUids"));
        defaults.put("short_description",
                List.of("short_description", "shortDescription", "summary", "subtitle"));
        // Published immutable: this map is shared by every request and merged, never mutated.
        DEFAULT_ALIASES = Collections.unmodifiableMap(defaults);
    }

    private final String folderName;
    private final int maxProducts;
    private final Map<String, List<String>> aliases;

    ProductExporter(final String folderName, final int maxProducts, final String[] aliasOverrides) {
        this.folderName = StringUtils.defaultIfBlank(folderName, "products");
        this.maxProducts = maxProducts;
        this.aliases = mergeAliases(aliasOverrides);
    }

    /**
     * @param page      any page in the site
     * @param assetHost publish host to prefix DAM references with, may be blank
     * @return the site's products, or an empty list when it has no products folder
     */
    List<Map<String, Object>> export(final Page page, final String assetHost,
                                     final String requestedPath) {
        final Resource folder = findFolder(page, requestedPath);
        if (folder == null) {
            return new ArrayList<>();
        }

        final List<Map<String, Object>> products = new ArrayList<>();
        collect(folder, products, assetHost);

        if (products.size() >= maxProducts) {
            LOG.warn("Product folder {} hit the {} product cap - the response is truncated",
                    folder.getPath(), maxProducts);
        }
        return products;
    }

    /** Depth-first walk so fragments organised into sub-folders are still picked up. */
    private void collect(final Resource folder, final List<Map<String, Object>> sink,
                         final String assetHost) {
        for (final Resource child : folder.getChildren()) {
            if (sink.size() >= maxProducts) {
                return;
            }
            final Map<String, String> elements = readElements(child);
            if (elements != null) {
                sink.add(toProduct(child, elements, assetHost));
            } else if (child.hasChildren()) {
                collect(child, sink, assetHost);
            }
        }
    }

    /**
     * Reads a fragment's master variation as name/value pairs.
     *
     * <p>Delegates to {@link ContentFragmentReader}, shared with the component-mapping engine's
     * single-fragment dereference so both read a Content Fragment the exact same way.</p>
     *
     * @return the element values, or null when the resource is not a content fragment at all
     */
    private Map<String, String> readElements(final Resource resource) {
        return ContentFragmentReader.readElements(resource);
    }

    private Map<String, Object> toProduct(final Resource resource, final Map<String, String> elements,
                                          final String assetHost) {
        final Map<String, Object> product = new LinkedHashMap<>();
        for (final String key : KEYS) {
            product.put(key, coerce(key, firstMatch(elements, key, resource), assetHost));
        }
        return product;
    }

    /**
     * Maps an already-flattened set of field values onto the product schema.
     *
     * <p>Shared with the GraphQL source so the alias table, the key order and the type coercion are
     * written once. A product read from a persisted query and one read from a Content Fragment are
     * indistinguishable in the response, which is the whole point of having two sources.</p>
     *
     * @param values     field name to value, already flattened out of whatever the source returned
     * @param fallbackId used for id, sku and name when the source supplied none - a product with no
     *                   identity is useless to a client
     * @param assetHost  publish host for any repository path still left relative
     */
    Map<String, Object> productFrom(final Map<String, String> values, final String fallbackId,
                                    final String assetHost) {
        final Map<String, Object> product = new LinkedHashMap<>();
        for (final String key : KEYS) {
            String raw = firstAlias(values, key);
            if (StringUtils.isBlank(raw) && IDENTITY_KEYS.contains(key)) {
                raw = fallbackId;
            }
            product.put(key, coerce(key, raw, assetHost));
        }
        return product;
    }

    /**
     * @return the first element value matching one of the key's aliases, or null
     */
    private String firstMatch(final Map<String, String> elements, final String key,
                              final Resource resource) {
        final String value = firstAlias(elements, key);
        if (StringUtils.isNotBlank(value)) {
            return value;
        }
        // Identity always resolves to something: a product with no id is useless to a client.
        if ("name".equals(key)) {
            final Resource content = resource.getChild("jcr:content");
            final String title = content == null
                    ? null : content.getValueMap().get("jcr:title", String.class);
            return StringUtils.defaultIfBlank(title, resource.getName());
        }
        if ("id".equals(key) || "sku".equals(key)) {
            return resource.getName();
        }
        return null;
    }

    /** @return the first value matching one of the key's aliases, or null */
    private String firstAlias(final Map<String, String> values, final String key) {
        for (final String alias : aliases.getOrDefault(key, Arrays.asList(key))) {
            final String value = values.get(alias);
            if (StringUtils.isNotBlank(value)) {
                return value;
            }
        }
        return null;
    }

    private Object coerce(final String key, final String raw, final String assetHost) {
        if (INTEGER_LIST_KEYS.contains(key)) {
            return toIntegerList(raw);
        }
        if (STRING_LIST_KEYS.contains(key)) {
            return toStringList(raw);
        }
        if (KEY_PRICE.equals(key)) {
            // Absent price means unknown. Emitting 0 here would read as 'free'.
            return StringUtils.isBlank(raw) ? null : toNumber(raw);
        }
        if (StringUtils.isBlank(raw)) {
            return StringUtils.EMPTY;
        }
        return raw.startsWith(DAM_ROOT) && StringUtils.isNotBlank(assetHost) ? (assetHost + raw) : raw;
    }

    private List<Integer> toIntegerList(final String raw) {
        final List<Integer> values = new ArrayList<>();
        for (final String part : split(raw)) {
            if (NumberUtils.isParsable(part)) {
                values.add(Integer.valueOf(part.trim()));
            }
        }
        return values;
    }

    private List<String> toStringList(final String raw) {
        return new ArrayList<>(split(raw));
    }

    private List<String> split(final String raw) {
        if (StringUtils.isBlank(raw)) {
            return new ArrayList<>();
        }
        final List<String> parts = new ArrayList<>();
        for (final String part : StringUtils.split(raw, ',')) {
            final String trimmed = part.trim();
            if (StringUtils.isNotEmpty(trimmed)) {
                parts.add(trimmed);
            }
        }
        return parts;
    }

    private Number toNumber(final String raw) {
        final String cleaned = raw.trim();
        try {
            if (cleaned.indexOf('.') >= 0) {
                return Double.valueOf(cleaned);
            }
            return Long.valueOf(cleaned);
        } catch (final NumberFormatException e) {
            LOG.debug("Price '{}' is not numeric - emitting it as text", raw);
            return null;
        }
    }

    /* ------------------------------------------------------------- discovery */

    /**
     * Resolves the catalogue folder, most explicit source first.
     *
     * <ol>
     *   <li>the {@code productsPath} request parameter - lets a caller point at any catalogue
     *       without touching content, which is what makes the endpoint testable;</li>
     *   <li>a {@code productsPath} entry on the site's placeholders page - the authored,
     *       per-site answer, alongside {@code hostname};</li>
     *   <li>a {@code productsPath} page property on the site root;</li>
     *   <li>a search of the site's DAM tree for a folder with the configured name.</li>
     * </ol>
     *
     * <p>Each step falls through to the next only when it yields nothing, so a configured value that
     * points somewhere non-existent is reported rather than silently replaced by a search result.</p>
     */
    private Resource findFolder(final Page page, final String requestedPath) {
        final Resource contentResource = page.getContentResource();
        if (contentResource == null) {
            return null;
        }
        final org.apache.sling.api.resource.ResourceResolver resolver =
                contentResource.getResourceResolver();

        final Resource fromRequest = resolveExplicit(resolver, requestedPath, "request parameter");
        if (fromRequest != null) {
            return fromRequest;
        }

        final String fromPlaceholders = SiteHostResolver.placeholder(page, PN_PRODUCTS_PATH);
        final Resource placeholderFolder =
                resolveExplicit(resolver, fromPlaceholders, "placeholders page");
        if (placeholderFolder != null) {
            return placeholderFolder;
        }

        final ValueMap properties = page.getProperties();
        final Resource fromProperty = resolveExplicit(
                resolver, properties.get(PN_PRODUCTS_PATH, String.class), "page property");
        if (fromProperty != null) {
            return fromProperty;
        }

        final String siteRoot = SiteHostResolver.siteRoot(page.getPath());
        if (siteRoot == null) {
            return null;
        }
        final String site = StringUtils.substringAfterLast(siteRoot, "/");
        final Resource damRoot = resolver.getResource(DAM_ROOT + site);
        if (damRoot == null) {
            LOG.debug("No DAM tree at {}{} - site has no products", DAM_ROOT, site);
            return null;
        }
        final Resource found = searchForFolder(damRoot);
        if (found == null) {
            LOG.info("No '{}' folder found under {}{} - set a 'productsPath' property on the site root "
                    + "if the catalogue lives elsewhere", folderName, DAM_ROOT, site);
        }
        return found;
    }

    /**
     * Breadth-first search for the products folder.
     *
     * <p>The visit budget is generous because DAM trees are wide: a media library with hundreds of
     * sub-folders sits between the site root and the fragments folder, and a tight cap would exhaust
     * itself on images before ever reaching the catalogue. Assets are skipped outright, so the budget
     * is spent on folders only.</p>
     */
    private Resource searchForFolder(final Resource damRoot) {
        final Deque<Resource> queue = new ArrayDeque<>();
        queue.add(damRoot);
        int visited = 0;

        while (!queue.isEmpty() && visited++ < MAX_FOLDERS_SCANNED) {
            final Resource current = queue.poll();
            if (folderName.equals(current.getName())) {
                return current;
            }
            for (final Resource child : current.getChildren()) {
                if (isTraversableFolder(child)) {
                    queue.add(child);
                }
            }
        }
        if (visited >= MAX_FOLDERS_SCANNED) {
            LOG.warn("Gave up looking for '{}' under {} after {} folders - set 'productsPath' on the "
                    + "site root to skip the search", folderName, damRoot.getPath(), MAX_FOLDERS_SCANNED);
        }
        return null;
    }

    /**
     * @return the folder at {@code path}, or null when the path is blank; a configured path that
     *         does not resolve is logged rather than passed over quietly
     */
    private Resource resolveExplicit(final org.apache.sling.api.resource.ResourceResolver resolver,
                                     final String path, final String source) {
        if (StringUtils.isBlank(path)) {
            return null;
        }
        final Resource folder = resolver.getResource(path.trim());
        if (folder == null) {
            LOG.warn("productsPath from the {} points at '{}', which does not exist", source, path);
        }
        return folder;
    }

    /** Folders only: never descend into an asset, its renditions or its metadata. */
    private boolean isTraversableFolder(final Resource resource) {
        final String name = resource.getName();
        if (name.startsWith("jcr:") || name.startsWith("rep:")) {
            return false;
        }
        return resource.getChild("jcr:content/metadata") == null
                && resource.getChild("jcr:content/renditions") == null;
    }

    /* ----------------------------------------------------------------- config */

    private Map<String, List<String>> mergeAliases(final String[] overrides) {
        final Map<String, List<String>> merged = new LinkedHashMap<>(DEFAULT_ALIASES);
        if (overrides == null) {
            return merged;
        }
        for (final String override : overrides) {
            final String key = StringUtils.trimToEmpty(StringUtils.substringBefore(override, "="));
            final String value = StringUtils.substringAfter(override, "=");
            if (StringUtils.isEmpty(key) || StringUtils.isBlank(value)) {
                continue;
            }
            final List<String> candidates = new ArrayList<>();
            for (final String alias : StringUtils.split(value, ',')) {
                final String trimmed = alias.trim();
                if (StringUtils.isNotEmpty(trimmed)) {
                    candidates.add(trimmed);
                }
            }
            merged.put(key, candidates);
        }
        return merged;
    }
}

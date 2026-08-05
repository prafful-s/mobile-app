package com.dept.contentbridge.core.services.impl;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.models.factory.ModelFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.dept.contentbridge.core.models.impl.GenericComponent;
import com.dept.contentbridge.core.schema.ContentBridgeComponent;
import com.dept.contentbridge.core.schema.SchemaConstants;
import com.dept.contentbridge.core.services.FormSchemaService;
import com.dept.contentbridge.core.services.UrlService;
import com.day.cq.wcm.api.Page;

/**
 * Turns a page's authored component tree into the flat {@code content[]} array.
 *
 * <p>Extracted from the exporter because this is the one genuinely intricate part of the export and
 * it has nothing to do with envelopes, navigation or products: it decides what counts as a
 * component, what is merely layout, and how deep to go. Keeping it separate means the rules can be
 * read - and tested - without the surrounding orchestration.</p>
 *
 * <p>Stateless and safe to share: all per-request data is passed in.</p>
 */
final class ComponentTreeWalker {

    private static final Logger LOG = LoggerFactory.getLogger(ComponentTreeWalker.class);

    /** Guard against a cycle or a pathological tree while walking deeply nested content. */
    private static final int MAX_COMPONENT_DEPTH = 12;

    /** Values starting with this are asset references and get the site's publish host prefixed. */
    private static final String DAM_PREFIX = "/content/dam/";

    /** Component container locations, most specific first. See {@code resolveContainer}. */
    private static final List<String> CONTAINER_CANDIDATES = List.of(
            SchemaConstants.NN_CONTAINER,   // editable templates: root/container
            "root",                         // Edge Delivery, and simpler editable templates
            "par",                          // classic static templates
            "content/par");

    private final ModelFactory modelFactory;
    private final UrlService urlService;
    private final FormSchemaService formSchemaService;

    ComponentTreeWalker(final ModelFactory modelFactory, final UrlService urlService,
                        final FormSchemaService formSchemaService) {
        this.modelFactory = modelFactory;
        this.urlService = urlService;
        this.formSchemaService = formSchemaService;
    }

    List<Map<String, Object>> componentsOf(final Page page, final String assetHost) {
        final List<Map<String, Object>> components = new ArrayList<>();
        final Resource container = resolveContainer(page);
        if (container != null) {
            collectComponents(container, components, 0);
        }
        if (StringUtils.isNotBlank(assetHost)) {
            for (final Map<String, Object> component : components) {
                final Object content = component.get(SchemaConstants.KEY_CONTENT);
                if (content instanceof Map) {
                    @SuppressWarnings("unchecked")
                    final Map<String, Object> payload = (Map<String, Object>) content;
                    prefixAssets(payload, assetHost);
                }
            }
        }
        return components;
    }

    /**
     * Prefixes DAM references with the site's publish host, in place.
     *
     * <p>Applied here rather than inside each model so that curated models and the generic fallback
     * behave identically, and so the host is resolved once per export instead of once per component.
     * Keyed on the value, not the property name, because projects use {@code image},
     * {@code imageRef}, {@code fileReference} and others interchangeably.</p>
     */
    private void prefixAssets(final Map<String, Object> payload, final String assetHost) {
        for (final Map.Entry<String, Object> entry : payload.entrySet()) {
            final Object value = entry.getValue();
            if (value instanceof String) {
                entry.setValue(prefixAsset((String) value, assetHost));
            } else if (value instanceof List) {
                final List<?> source = (List<?>) value;
                final List<Object> rewritten = new ArrayList<>(source.size());
                for (final Object item : source) {
                    rewritten.add(item instanceof String ? prefixAsset((String) item, assetHost) : item);
                }
                entry.setValue(rewritten);
            }
        }
    }

    private String prefixAsset(final String value, final String assetHost) {
        if (StringUtils.isBlank(assetHost) || !StringUtils.startsWith(value, DAM_PREFIX)) {
            return value;
        }
        return assetHost + value;
    }

    /**
     * Walks the component tree depth-first, in authoring order, flattening every structural node
     * into a single ordered list.
     *
     * <p>Hand-built ContentBridge pages are one level deep, but Edge Delivery content nests
     * {@code root > section > columns > row > col > block > item}. Structural nodes carry layout,
     * not content, so they are descended into rather than exported - which keeps the flat
     * {@code content[]} contract identical for both authoring models.</p>
     */
    private void collectComponents(final Resource parent, final List<Map<String, Object>> sink,
                                   final int depth) {
        if (depth > MAX_COMPONENT_DEPTH) {
            LOG.warn("Component nesting below {} exceeds {} levels - not descending further",
                    parent.getPath(), MAX_COMPONENT_DEPTH);
            return;
        }
        for (final Resource child : parent.getChildren()) {
            if (isSystemNode(child)) {
                continue;
            }
            final String resourceType = StringUtils.trimToEmpty(child.getResourceType());
            final boolean hasChildren = child.hasChildren();

            // Known layout containers, and the untyped row/col wrappers, never produce a component.
            if (isKnownContainerType(resourceType) || (hasChildren && isUntyped(resourceType))) {
                collectComponents(child, sink, depth + 1);
                continue;
            }

            // A registered model wins so curated components keep their exact shape; everything
            // else is projected generically, which is what lets the API serve an arbitrary site.
            ContentBridgeComponent model = adaptStrictly(child);
            if (model == null) {
                model = new GenericComponent(child, urlService);
            }
            final Map<String, Object> content = model.getContent();

            // Children but nothing of its own to say: a wrapper such as an Edge Delivery cards or
            // accordion block. Exporting it would emit an empty component and hide its items.
            // Evaluated from the content already built, so properties are read only once.
            if (hasChildren && content.isEmpty()) {
                collectComponents(child, sink, depth + 1);
                continue;
            }

            final Map<String, Object> entry = new LinkedHashMap<>();
            final Map<String, Object> meta = new LinkedHashMap<>();
            meta.put(SchemaConstants.KEY_ID, model.getId());
            meta.put(SchemaConstants.KEY_COMPONENT, model.getComponentName());
            entry.put(SchemaConstants.KEY_META, meta);
            entry.put(SchemaConstants.KEY_CONTENT, content);

            // Form blocks carry their field definitions alongside the authored content, so a client
            // can render the form without a second call or a hard-coded copy of the schema.
            final Map<String, Object> schema = resolveFormSchema(model.getComponentName(), content);
            if (!schema.isEmpty()) {
                entry.put(SchemaConstants.KEY_SCHEMA, schema);
            }
            sink.add(entry);

            // Components that also nest (carousels, tabs, blocks with items) contribute their
            // children to the same flat list.
            if (hasChildren) {
                collectComponents(child, sink, depth + 1);
            }
        }
    }

    /**
     * Finds the form schema a block should carry, if any.
     *
     * <p>An explicit {@code formName} or {@code form} property wins, but the usual case needs
     * neither: Edge Delivery block models are already named after the form they render
     * ({@code sign-in}, {@code create-account}, {@code checkout}), so the component name is matched
     * against the known schemas directly. A block whose name is not a known form gets nothing, which
     * is how every non-form component stays untouched.</p>
     *
     * @return the field definitions, or an empty map when this block is not a form
     */
    private Map<String, Object> resolveFormSchema(final String componentName,
                                                  final Map<String, Object> content) {
        final String[] candidates = {
            asString(content.get(SchemaConstants.PN_FORM_NAME)),
            asString(content.get(SchemaConstants.PN_FORM)),
            componentName
        };
        for (final String candidate : candidates) {
            if (StringUtils.isBlank(candidate)) {
                continue;
            }
            final Map<String, Object> schema = formSchemaService.getSchema(candidate);
            if (!schema.isEmpty()) {
                LOG.debug("Attached form schema '{}' to a {} block", candidate, componentName);
                return schema;
            }
        }
        return Collections.emptyMap();
    }

    private static String asString(final Object value) {
        return value instanceof String ? (String) value : null;
    }

    /**
     * Machinery rather than content: {@code cq:responsive} breakpoints, live-copy bookkeeping and
     * the ContentBridge configuration node itself. Relevant mainly when a page keeps its components
     * directly under {@code jcr:content} and the walk starts there.
     */
    private static boolean isSystemNode(final Resource resource) {
        final String name = resource.getName();
        return name.startsWith("cq:")
                || name.startsWith("jcr:")
                || name.startsWith("rep:")
                || name.startsWith("oak:")
                || "contentbridge".equals(name);
    }

    private static boolean isKnownContainerType(final String resourceType) {
        if (StringUtils.isEmpty(resourceType)) {
            return false;
        }
        if (SchemaConstants.RT_FRANKLIN_ROOT.equals(resourceType)
                || SchemaConstants.RT_FRANKLIN_SECTION.equals(resourceType)
                || SchemaConstants.RT_FRANKLIN_COLUMNS.equals(resourceType)) {
            return true;
        }
        final String name = StringUtils.substringAfterLast(resourceType, "/");
        return "responsivegrid".equals(name)
                || "container".equals(name)
                || "parsys".equals(name)
                || "iparsys".equals(name)
                || "root".equals(name)
                || "section".equals(name);
    }

    /** @return true when no sling:resourceType is set and Sling fell back to the JCR primary type */
    private static boolean isUntyped(final String resourceType) {
        return StringUtils.isEmpty(resourceType)
                || "nt:unstructured".equals(resourceType)
                || "sling:Folder".equals(resourceType)
                || "sling:OrderedFolder".equals(resourceType)
                || "nt:folder".equals(resourceType);
    }

    /**
     * Adapts a resource to its ContentBridge model <em>only</em> when a model is registered for that
     * exact {@code sling:resourceType}.
     *
     * <p>A plain {@code adaptTo(ContentBridgeComponent.class)} is not safe here. When no
     * implementation matches the resource type, Sling falls back to
     * {@code FirstImplementationPicker}, which returns whichever implementation sorts first by class
     * name - so an unmapped resource would be silently exported as a {@code banner}. Resolving
     * through {@link ModelFactory} keys strictly on the resource type instead.</p>
     *
     * @return the model, or {@code null} when this resource is not part of the JSON contract
     */
    private ContentBridgeComponent adaptStrictly(final Resource resource) {
        try {
            if (!modelFactory.isModelAvailableForResource(resource)) {
                LOG.debug("No ContentBridge model registered for {} ({}) - skipping",
                        resource.getPath(), resource.getResourceType());
                return null;
            }
            final Object model = modelFactory.getModelFromResource(resource);
            if (model instanceof ContentBridgeComponent) {
                return (ContentBridgeComponent) model;
            }
            LOG.debug("Model for {} ({}) is not a ContentBridgeComponent - skipping",
                    resource.getPath(), resource.getResourceType());
        } catch (final RuntimeException e) {
            LOG.warn("Could not build a ContentBridge model for {} - skipping", resource.getPath(), e);
        }
        return null;
    }

    /**
     * Finds the node holding the authored components, trying the layouts AEM projects actually use
     * before falling back to {@code jcr:content} itself.
     *
     * <p>Editable templates put components under {@code root/container}, Edge Delivery under
     * {@code root}, and classic static templates under {@code par} or {@code content/par}. The
     * fallback is safe because the traversal walks through containers anyway - it mainly decides how
     * much page-level furniture (branding nodes, {@code cq:} subtrees) the walk has to step over.</p>
     */
    private Resource resolveContainer(final Page page) {
        final Resource content = page.getContentResource();
        if (content == null) {
            return null;
        }
        for (final String candidate : CONTAINER_CANDIDATES) {
            final Resource container = content.getChild(candidate);
            if (container != null) {
                return container;
            }
        }
        final Resource root = content.getChild("root");
        return root != null ? root : content;
    }
}

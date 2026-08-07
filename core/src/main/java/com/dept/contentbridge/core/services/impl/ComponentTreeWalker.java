package com.dept.contentbridge.core.services.impl;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.commons.lang3.StringUtils;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.models.factory.ModelFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.dept.contentbridge.core.models.impl.AbstractComponentModel;
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

    /** Component container locations, most specific first. See {@code resolveContainer}. */
    private static final List<String> CONTAINER_CANDIDATES = List.of(
            SchemaConstants.NN_CONTAINER,   // editable templates: root/container
            "root",                         // Edge Delivery, and simpler editable templates
            "par",                          // classic static templates
            "content/par");

    private final ModelFactory modelFactory;
    private final UrlService urlService;
    private final FormSchemaService formSchemaService;
    private final StyleResolver styleResolver;
    private final ComponentMappings mappings;
    private final FormBlockBuilder formBuilder;

    ComponentTreeWalker(final ModelFactory modelFactory, final UrlService urlService,
                        final FormSchemaService formSchemaService, final StyleResolver styleResolver,
                        final ComponentMappings mappings) {
        this.modelFactory = modelFactory;
        this.urlService = urlService;
        this.formSchemaService = formSchemaService;
        this.styleResolver = styleResolver;
        this.mappings = mappings == null ? ComponentMappings.EMPTY : mappings;
        this.formBuilder = new FormBlockBuilder(formSchemaService);
    }

    List<Map<String, Object>> componentsOf(final Page page, final String assetHost) {
        return componentsOf(page, assetHost, false);
    }

    /**
     * @param hierarchical when true, layout containers become {@code panel} components whose
     *                     {@code content} is an array of children, instead of being flattened away
     */
    List<Map<String, Object>> componentsOf(final Page page, final String assetHost,
                                           final boolean hierarchical) {
        final List<Map<String, Object>> components = new ArrayList<>();
        final Resource container = resolveContainer(page);
        if (container != null) {
            collectComponents(container, components, 0, hierarchical);
        }
        prefixAssetsDeep(components, assetHost);
        return components;
    }

    /**
     * Walks the finished tree applying the asset host. Recursive because a nested panel's children
     * are just as likely to hold DAM references as a top-level component's.
     */
    private void prefixAssetsDeep(final List<Map<String, Object>> components, final String assetHost) {
        if (StringUtils.isBlank(assetHost)) {
            return;
        }
        for (final Map<String, Object> component : components) {
            final Object content = component.get(SchemaConstants.KEY_CONTENT);
            if (content instanceof Map) {
                @SuppressWarnings("unchecked")
                final Map<String, Object> payload = (Map<String, Object>) content;
                prefixAssets(payload, assetHost);
            } else if (content instanceof List) {
                @SuppressWarnings("unchecked")
                final List<Map<String, Object>> children = (List<Map<String, Object>>) content;
                prefixAssetsDeep(children, assetHost);
            }
        }
    }

    /**
     * Prefixes repository paths with the site's delivery host, in place.
     *
     * <p>Applied here rather than inside each model so that curated models and the generic fallback
     * behave identically, and so the host is resolved once per export instead of once per component.
     * Keyed on the value, not the property name, because projects use {@code image},
     * {@code imageRef}, {@code fileReference}, {@code link} and others interchangeably.</p>
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
        return ContentPaths.prefix(value, assetHost);
    }

    /**
     * Appends every authored property the rule did not consume, in authoring order, after the
     * schema keys the mapping already wrote.
     *
     * <p>The source here is the model's own content projection, not the raw ValueMap - so JCR,
     * Sling and CQ bookkeeping and the Edge Delivery {@code model}/{@code modelFields} markers
     * are already filtered out, and DAM references are already externalised. All that is left
     * to skip is what the rule consumed (its fields' source properties) and any key the schema
     * already owns.</p>
     */
    private static void appendUnconsumedExtras(final Map<String, Object> target,
                                               final Map<String, Object> authored,
                                               final Set<String> consumed) {
        for (final Map.Entry<String, Object> entry : authored.entrySet()) {
            final String key = entry.getKey();
            if (consumed.contains(key) || target.containsKey(key)) {
                continue;
            }
            target.put(key, entry.getValue());
        }
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
                                   final int depth, final boolean hierarchical) {
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

            // An untyped node with no children is an empty layout cell - a column an author left
            // blank. It has no resource type to name it, so exporting it produced a component called
            // 'nt:unstructured' with empty content: a JCR implementation detail presented to the
            // client as though it were content.
            if (!hasChildren && isUntyped(resourceType)) {
                continue;
            }

            // Known layout containers, and the untyped row/col wrappers, never produce a component.
            if (isKnownContainerType(resourceType) || (hasChildren && isUntyped(resourceType))) {
                if (hierarchical) {
                    // A section carries the spacing and alignment the target's panel wants, so it is
                    // a component in its own right rather than scaffolding to step over.
                    emitPanel(child, sink, depth, resourceType);
                } else {
                    collectComponents(child, sink, depth + 1, false);
                }
                continue;
            }

            // A registered model wins so curated components keep their exact shape; everything
            // else is projected generically, which is what lets the API serve an arbitrary site.
            ContentBridgeComponent model = adaptStrictly(child);
            if (model == null) {
                model = new GenericComponent(child, urlService);
            }

            // Does this model already speak the app's vocabulary? The models extending
            // AbstractComponentModel do - they were written against the target contract and emit
            // 'title', 'subtitle', 'linkTo' directly. FranklinComponentModel and GenericComponent do
            // not: they project whatever the author set, under the names the author used.
            //
            // The distinction matters because rule names and target names overlap. The 'card' rule
            // matches the name 'card', so without this guard CardModel's output would be projected a
            // second time - hunting for a rich-text blob that is not there and discarding the title
            // it had already resolved.
            final boolean speaksTargetVocabulary = model instanceof AbstractComponentModel;
            final Map<String, Object> authored = model.getContent();

            // Children but nothing of its own to say: a wrapper such as an Edge Delivery cards or
            // accordion block. Exporting it would emit an empty component and hide its items.
            // Evaluated from the authored content, before any mapping, so that a rule cannot turn a
            // wrapper into a component by supplying constants.
            if (hasChildren && authored.isEmpty()) {
                collectComponents(child, sink, depth + 1, hierarchical);
                continue;
            }

            final String authoredName = model.getComponentName();
            String componentName = authoredName;
            Map<String, Object> content = authored;
            Map<String, Object> styles = Collections.emptyMap();

            // v2 speaks the app's vocabulary, so an authored 'hero' becomes a 'banner' carrying the
            // fields the client expects. v1 is left exactly as it was: the two schemas are each a
            // published contract, and a mapping rule must not silently change the older one.
            final ComponentMapping mapping =
                    hierarchical && !speaksTargetVocabulary ? mappings.find(authoredName) : null;
            if (mapping != null) {
                final Map<String, Object> mapped = mapping.apply(authored);
                if (mapped.isEmpty()) {
                    // The rule matched a component with none of its source fields authored. An empty
                    // banner tells a client less than the authored shape does, so this one component
                    // is exported unmapped rather than emptied.
                    LOG.debug("Rule for '{}' produced nothing at {} - exporting it unmapped",
                            authoredName, child.getPath());
                } else {
                    componentName = mapping.getComponent();
                    content = new LinkedHashMap<>(mapped);
                    styles = mapping.applyStyles(authored);
                    if (mapping.isSkeleton()) {
                        appendUnconsumedExtras(content, authored, mapping.consumedProperties());
                    }
                }
            }

            // A form is a container of fields, the way a panel is a container of components, so it
            // is built rather than described: its settings move into meta and its content becomes
            // the ordered field list. Only in v2 - v1 keeps the flat block with a schema beside it,
            // because that contract is already published.
            if (hierarchical && isFormBlock(child, authoredName, authored)) {
                sink.add(formBuilder.build(child, formNameOf(authoredName, authored),
                        model.getId(), sink.size(), authored));
                continue;
            }

            final Map<String, Object> entry = new LinkedHashMap<>();
            final Map<String, Object> meta = new LinkedHashMap<>();
            meta.put(SchemaConstants.KEY_ID, model.getId());
            if (hierarchical) {
                meta.put(SchemaConstants.KEY_ORDER, sink.size());
            }
            meta.put(SchemaConstants.KEY_COMPONENT, componentName);
            entry.put(SchemaConstants.KEY_META, meta);
            if (!styles.isEmpty()) {
                entry.put(SchemaConstants.KEY_STYLES, styles);
            }
            entry.put(SchemaConstants.KEY_CONTENT, content);

            // v1 only: the field definitions travel alongside the authored content, so a client can
            // render the form without a second call or a hard-coded copy of the schema.
            final List<Map<String, Object>> schema = resolveFormSchema(authoredName, authored);
            if (!schema.isEmpty()) {
                entry.put(SchemaConstants.KEY_SCHEMA, schema);
            }
            sink.add(entry);

            // Components that also nest (carousels, tabs, blocks with items) contribute their
            // children to the same flat list.
            if (hasChildren) {
                collectComponents(child, sink, depth + 1, hierarchical);
            }
        }
    }

    /**
     * Emits a layout container as a {@code panel} whose {@code content} is its children.
     *
     * <p>A panel with nothing in it is dropped rather than emitted empty - an authored section that
     * ended up with no content is noise to a client, not a layout instruction.</p>
     */
    private void emitPanel(final Resource resource, final List<Map<String, Object>> sink,
                           final int depth, final String resourceType) {
        final List<Map<String, Object>> children = new ArrayList<>();
        collectComponents(resource, children, depth + 1, true);

        if (children.isEmpty()) {
            return;
        }

        final Map<String, Object> meta = new LinkedHashMap<>();
        meta.put(SchemaConstants.KEY_ID, AbstractComponentModel.generateId(resource.getPath()));
        meta.put(SchemaConstants.KEY_ORDER, sink.size());
        meta.put(SchemaConstants.KEY_LAYOUT, layoutOf(resourceType));
        meta.put(SchemaConstants.KEY_COMPONENT, SchemaConstants.COMPONENT_PANEL);
        // Reference contract carries showBorder on every panel meta. AEM has no equivalent
        // property, so emit the target's own default rather than omit the key.
        meta.put("showBorder", resource.getValueMap().get("showBorder", Boolean.FALSE));

        final Map<String, Object> entry = new LinkedHashMap<>();
        entry.put(SchemaConstants.KEY_META, meta);

        final Map<String, Object> styles = styleResolver.stylesOf(resource);
        if (!styles.isEmpty()) {
            entry.put(SchemaConstants.KEY_STYLES, styles);
        }
        entry.put(SchemaConstants.KEY_CONTENT, children);
        sink.add(entry);
    }

    /** Columns lay their children out side by side; a section stacks them. */
    private static String layoutOf(final String resourceType) {
        return SchemaConstants.RT_FRANKLIN_COLUMNS.equals(resourceType)
                || StringUtils.endsWith(resourceType, "/columns")
                ? "horizontal" : "vertical";
    }

    /**
     * Is this block a form?
     *
     * <p>Three ways to be one, because forms reach the repository by three routes. An AEM Forms
     * component says so on itself with {@code fieldType=form}. An Edge Delivery block says so by
     * being named after a known schema, or by naming one in {@code formName}. A block that is none
     * of these is not treated as a form, however form-like its properties look - guessing here would
     * turn an unrelated block into an empty form.</p>
     */
    private boolean isFormBlock(final Resource resource, final String componentName,
                                final Map<String, Object> authored) {
        if (FormBlockBuilder.COMPONENT_FORM.equals(
                resource.getValueMap().get("fieldType", String.class))) {
            return true;
        }
        return StringUtils.isNotBlank(formNameOf(componentName, authored));
    }

    /**
     * @return the schema this block should use, or null when it names none - an authored AEM Forms
     *         component is a form without naming a schema, and takes its fields from its children
     */
    private String formNameOf(final String componentName, final Map<String, Object> authored) {
        final String[] candidates = {
            asString(authored.get(SchemaConstants.PN_FORM_NAME)),
            asString(authored.get(SchemaConstants.PN_FORM)),
            componentName,
        };
        for (final String candidate : candidates) {
            if (StringUtils.isNotBlank(candidate)
                    && !formSchemaService.getSchema(candidate).isEmpty()) {
                return candidate;
            }
        }
        return null;
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
     * @return the fields in render order, or an empty list when this block is not a form
     */
    private List<Map<String, Object>> resolveFormSchema(final String componentName,
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
            final List<Map<String, Object>> schema = formSchemaService.getSchema(candidate);
            if (!schema.isEmpty()) {
                LOG.debug("Attached form schema '{}' to a {} block", candidate, componentName);
                return schema;
            }
        }
        return Collections.emptyList();
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

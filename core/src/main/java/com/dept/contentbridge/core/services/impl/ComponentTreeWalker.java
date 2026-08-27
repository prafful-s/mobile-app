package com.dept.contentbridge.core.services.impl;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.commons.lang3.StringUtils;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ValueMap;
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

    /** Forwarded to the internal {@link FormBlockBuilder} so form headings can honour per-site overrides. */
    void setSiteDefaults(final SiteDefaults siteDefaults) {
        this.formBuilder.setSiteDefaults(siteDefaults);
    }

    /**
     * Components with no reference-contract counterpart that ship on this repo's sites but that
     * a downstream client will not render - kept out of screens.content[] so the payload stays
     * small and predictable. Applied only to the top-level screen walk; the nav-page walk that
     * feeds {@code meta.rightIcon} still sees {@code search} (excluding it there would drop the
     * header icon).
     *
     * <p>{@code fileUpload} is deliberately NOT in this list even though the sf audit flagged it
     * as ref-unmapped: every emitted {@code fileUpload} is a field inside a form's
     * {@code content[]}, and dropping it removes real file-input widgets from forms
     * (name-change-request's {@code identityDocument} etc.). Dropped only if a truly orphan
     * top-level {@code fileUpload} ever gets authored.</p>
     *
     * <p>Temporary shipping cleanup while these blocks either grow a mapping rule or an
     * equivalent lands in the reference contract; see decisions.md.</p>
     */
    private static final Set<String> SCREEN_EXCLUDED_COMPONENTS =
            Set.of("iframe", "loan-calculator", "search");

    List<Map<String, Object>> componentsOf(final Page page, final String assetHost) {
        return componentsOf(page, assetHost, false, false);
    }

    /**
     * @param hierarchical when true, layout containers become {@code panel} components whose
     *                     {@code content} is an array of children, instead of being flattened away
     */
    List<Map<String, Object>> componentsOf(final Page page, final String assetHost,
                                           final boolean hierarchical) {
        return componentsOf(page, assetHost, hierarchical, false);
    }

    /**
     * @param applyScreenExclusions when true, {@link #SCREEN_EXCLUDED_COMPONENTS} are filtered
     *                              out of the returned tree - set by the screen walk, not the
     *                              nav-page walk
     */
    List<Map<String, Object>> componentsOf(final Page page, final String assetHost,
                                           final boolean hierarchical,
                                           final boolean applyScreenExclusions) {
        final List<Map<String, Object>> components = new ArrayList<>();
        final Resource container = resolveContainer(page);
        if (container != null) {
            collectComponents(container, components, 0, hierarchical, null);
        }
        if (applyScreenExclusions) {
            dropExcludedDeep(components);
        }
        prefixAssetsDeep(components, assetHost);
        return components;
    }

    /**
     * The form schema names a page's own content carries, if any.
     *
     * <p>Lets navigation resolve which screen is the login/registration screen by looking for the
     * page that actually holds a {@code sign-in} or {@code create-account} form, rather than
     * hard-coding a page name. Scans the page's own component tree only - not its child pages - so
     * an aggregating container that holds no form of its own yields an empty set.</p>
     */
    Set<String> formSchemasOn(final Page page) {
        final Set<String> names = new LinkedHashSet<>();
        final Resource container = resolveContainer(page);
        if (container != null) {
            collectFormSchemas(container, names, 0);
        }
        return names;
    }

    private void collectFormSchemas(final Resource parent, final Set<String> sink, final int depth) {
        if (depth > MAX_COMPONENT_DEPTH) {
            return;
        }
        for (final Resource child : parent.getChildren()) {
            if (isSystemNode(child)) {
                continue;
            }
            ContentBridgeComponent model = adaptStrictly(child);
            if (model == null) {
                model = new GenericComponent(child, urlService);
            }
            final String componentName = model.getComponentName();
            final Map<String, Object> authored = model.getContent();
            if (isFormBlock(child, componentName, authored)) {
                final String formName = formNameOf(componentName, authored);
                if (StringUtils.isNotBlank(formName)) {
                    sink.add(formName);
                }
            }
            if (child.hasChildren()) {
                collectFormSchemas(child, sink, depth + 1);
            }
        }
    }

    /**
     * Removes screen-excluded components from the tree in place. Recursive because a panel's
     * children may hold them just as often as a top-level component.
     */
    private static void dropExcludedDeep(final List<Map<String, Object>> components) {
        final Iterator<Map<String, Object>> iterator = components.iterator();
        while (iterator.hasNext()) {
            final Map<String, Object> component = iterator.next();
            final Object meta = component.get(SchemaConstants.KEY_META);
            if (meta instanceof Map) {
                final Object name = ((Map<?, ?>) meta).get(SchemaConstants.KEY_COMPONENT);
                if (SCREEN_EXCLUDED_COMPONENTS.contains(name)) {
                    iterator.remove();
                    continue;
                }
            }
            final Object content = component.get(SchemaConstants.KEY_CONTENT);
            if (content instanceof List) {
                @SuppressWarnings("unchecked")
                final List<Map<String, Object>> children = (List<Map<String, Object>>) content;
                dropExcludedDeep(children);
            }
        }
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
     * Output keys never rewritten with the delivery host, even though their value is a
     * {@code /content/...} repository path.
     *
     * <p>A {@code link}/{@code linkTo} is a navigation target: the client already knows its own
     * host and resolves the path itself (in-app routing, or its own environment's domain). Baking
     * a specific environment's host into it would send every client to that one environment
     * regardless of where the JSON was actually served from - unlike an image or Content Fragment
     * reference, which names a resource that must be fetched from exactly this delivery host.</p>
     */
    private static final Set<String> NEVER_PREFIXED_KEYS = Set.of("link", "linkTo");

    /**
     * Prefixes repository paths with the site's delivery host, in place.
     *
     * <p>Applied here rather than inside each model so that curated models and the generic fallback
     * behave identically, and so the host is resolved once per export instead of once per component.
     * Keyed on the value, not the property name, because projects use {@code image},
     * {@code imageRef}, {@code fileReference} and others interchangeably - except
     * {@link #NEVER_PREFIXED_KEYS}, which are excluded by name because a link and an asset
     * reference need opposite treatment even when both hold a bare {@code /content/...} path.</p>
     */
    private void prefixAssets(final Map<String, Object> payload, final String assetHost) {
        for (final Map.Entry<String, Object> entry : payload.entrySet()) {
            if (NEVER_PREFIXED_KEYS.contains(entry.getKey())) {
                continue;
            }
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
        // Wildcard opt-out: a rule declaring ignoreExtras: ["*"] freezes its output to exactly what
        // the rule wrote - a hardcoded reference shape must not gain keys from whatever the author
        // happened to leave on the resource.
        if (consumed.contains("*")) {
            return;
        }
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
     *
     * @param ancestorSection the nearest enclosing section resource, or {@code null} above the
     *                       first one - carried down so a field declared {@code fromParent} can
     *                       read a section-level property such as {@code sec-alignment} regardless
     *                       of how many columns/row/col wrappers sit between the section and the
     *                       component. {@code null} until a section is actually entered; a
     *                       {@code fromParent} field then just resolves to nothing, same as any
     *                       other unset source.
     */
    private void collectComponents(final Resource parent, final List<Map<String, Object>> sink,
                                   final int depth, final boolean hierarchical,
                                   final Resource ancestorSection) {
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
                // A section becomes the new nearest ancestor for anything beneath it; a columns/
                // row/col wrapper is not a section itself, so it just carries the existing one down.
                final Resource nextSection = isSectionType(resourceType) ? child : ancestorSection;
                if (hierarchical) {
                    // A section carries the spacing and alignment the target's panel wants, so it is
                    // a component in its own right rather than scaffolding to step over.
                    emitPanel(child, sink, depth, resourceType, nextSection);
                } else {
                    collectComponents(child, sink, depth + 1, false, nextSection);
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
                collectComponents(child, sink, depth + 1, hierarchical, ancestorSection);
                continue;
            }

            final String authoredName = model.getComponentName();
            String componentName = authoredName;
            String subtype = null;
            Map<String, Object> content = authored;
            Map<String, Object> styles = Collections.emptyMap();
            final Map<String, Object> sectionProps = ancestorSection == null
                    ? Collections.emptyMap() : ancestorSection.getValueMap();

            // v2 speaks the app's vocabulary, so an authored 'hero' becomes a 'banner' carrying the
            // fields the client expects. v1 is left exactly as it was: the two schemas are each a
            // published contract, and a mapping rule must not silently change the older one.
            final ComponentMapping matched =
                    hierarchical && !speaksTargetVocabulary ? mappings.find(authoredName) : null;
            // A rule may redirect to a different rule based on an authored value (a hero laid out
            // image-right is really a card), then switch to its own fallback based on what was
            // authored (a card with no image is a rich-text block) - resolve both before applying.
            ComponentMapping base = matched;
            if (matched != null) {
                final String variantRule = matched.variantRuleFor(authored);
                final ComponentMapping redirected = variantRule == null ? null : mappings.find(variantRule);
                if (redirected != null) {
                    base = redirected;
                }
            }
            final ComponentMapping mapping = base == null ? null : base.effectiveFor(authored);
            if (mapping != null) {
                final Map<String, Object> ruleSource =
                        withContentFragmentFolderRef(mapping, withContentFragmentRef(mapping, authored, child), child);
                final Map<String, Object> mapped = mapping.apply(ruleSource, sectionProps);
                if (mapped.isEmpty()) {
                    // The rule matched a component with none of its source fields authored. An empty
                    // banner tells a client less than the authored shape does, so this one component
                    // is exported unmapped rather than emptied.
                    LOG.debug("Rule for '{}' produced nothing at {} - exporting it unmapped",
                            authoredName, child.getPath());
                } else {
                    componentName = mapping.getComponent();
                    subtype = mapping.getSubtype();
                    content = new LinkedHashMap<>(mapped);
                    styles = mapping.applyStyles(ruleSource, sectionProps);
                    if (mapping.isSkeleton()) {
                        // Extras come from the component's own properties only - a referenced
                        // fragment's fields that no rule targeted stay internal, not leaked as extras.
                        appendUnconsumedExtras(content, authored, mapping.consumedProperties());
                    }
                }
            }

            // A form is a container of fields, the way a panel is a container of components, so it
            // is built rather than described: its settings move into meta and its content becomes
            // the ordered field list. Only in v2 - v1 keeps the flat block with a schema beside it,
            // because that contract is already published.
            if (hierarchical && isFormBlock(child, authoredName, authored)) {
                final String formName = formNameOf(authoredName, authored);
                final Map<String, Object> resolvedAuthored = formBuilder.withDefaultVariant(formName, authored);
                final Map<String, Object> heading = formBuilder.heading(formName, resolvedAuthored,
                        AbstractComponentModel.generateId(child.getPath() + "/heading"), sink.size(),
                        child.getPath());
                if (heading != null) {
                    sink.add(heading);
                }
                sink.add(formBuilder.build(child, formName, model.getId(), sink.size(), resolvedAuthored));
                continue;
            }

            final Map<String, Object> entry = new LinkedHashMap<>();
            final Map<String, Object> meta = new LinkedHashMap<>();
            meta.put(SchemaConstants.KEY_ID, model.getId());
            if (hierarchical) {
                meta.put(SchemaConstants.KEY_ORDER, sink.size());
            }
            meta.put(SchemaConstants.KEY_COMPONENT, componentName);
            if (subtype != null) {
                meta.put(SchemaConstants.KEY_SUBTYPE, subtype);
            }
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
                collectComponents(child, sink, depth + 1, hierarchical, ancestorSection);
            }
        }
    }

    /**
     * Merges a referenced Content Fragment's fields into the source a rule resolves against, for
     * rules that declare {@code contentFragmentRef} - a block such as {@code product-card} carries
     * little more than a reference; the title, image and description a client needs live on the
     * fragment, not the component.
     *
     * <p>The fragment's fields go in first, the component's own authored properties are layered on
     * top - an authored value always wins over whatever the fragment says, the same precedence used
     * everywhere else a shipped default and an authored override can both apply.</p>
     *
     * @return {@code authored} unchanged when the rule declares no {@code contentFragmentRef}, the
     *         reference is blank, or it does not resolve to a readable fragment
     */
    private Map<String, Object> withContentFragmentRef(final ComponentMapping mapping,
                                                        final Map<String, Object> authored,
                                                        final Resource component) {
        final String refProperty = mapping.getContentFragmentRef();
        if (refProperty == null) {
            return authored;
        }
        final Object refValue = authored.get(refProperty);
        if (!(refValue instanceof String) || StringUtils.isBlank((String) refValue)) {
            return authored;
        }
        final Resource fragment = component.getResourceResolver().getResource(((String) refValue).trim());
        if (fragment == null) {
            LOG.warn("{} on {} points at '{}', which does not resolve - exporting the component "
                    + "without its fragment data", refProperty, component.getPath(), refValue);
            return authored;
        }
        final Map<String, String> elements = ContentFragmentReader.read(fragment);
        if (elements.isEmpty()) {
            return authored;
        }
        final Map<String, Object> merged = new LinkedHashMap<>(elements);
        merged.putAll(authored);
        return merged;
    }

    /**
     * Enumerates a folder of Content Fragments referenced by an authored property, and injects an
     * array of one specified field per child fragment into the source map. Companion to
     * {@link #withContentFragmentRef} for the collection case (a
     * {@code category-products-lister} references a folder of product fragments, and the target
     * component wants a {@code items: [sku1, sku2, ...]} list rather than merged fields).
     *
     * <p>The authored value may be a full URL (as EDS blocks often store it) - the scheme and host
     * are stripped so it resolves as a JCR path. Values that do not resolve, that point at a
     * non-folder, or that yield no child fragments leave {@code authored} unchanged.</p>
     */
    private Map<String, Object> withContentFragmentFolderRef(final ComponentMapping mapping,
                                                              final Map<String, Object> authored,
                                                              final Resource component) {
        final ComponentMapping.ContentFragmentFolderRef ref = mapping.getContentFragmentFolderRef();
        if (ref == null) {
            return authored;
        }
        final Object raw = authored.get(ref.getProperty());
        if (!(raw instanceof String) || StringUtils.isBlank((String) raw)) {
            return authored;
        }
        final String path = stripHost(((String) raw).trim());
        final Resource folder = component.getResourceResolver().getResource(path);
        if (folder == null) {
            LOG.warn("{} on {} points at '{}', which does not resolve - exporting the component "
                    + "without its folder data", ref.getProperty(), component.getPath(), raw);
            return authored;
        }
        // One walk of the folder feeds every collect entry, so a rule that needs both product SKUs
        // and their category tags reads the tree once instead of N times. A subfolder that is not
        // itself a Content Fragment is recursed into (bounded depth) so a category folder holding
        // per-subcategory folders of fragments (e.g. .../women/tops, .../women/bottoms) still yields
        // its fragments to the caller.
        final Map<String, List<String>> collected = new LinkedHashMap<>();
        for (final ComponentMapping.ContentFragmentFolderRef.Collect c : ref.getCollects()) {
            collected.put(c.getInjectAs(), new ArrayList<>());
        }
        collectFragments(folder, ref, authored, collected, 0);
        boolean anyPopulated = false;
        for (final List<String> values : collected.values()) {
            if (!values.isEmpty()) {
                anyPopulated = true;
                break;
            }
        }
        if (!anyPopulated) {
            return authored;
        }
        final Map<String, Object> merged = new LinkedHashMap<>(authored);
        for (final Map.Entry<String, List<String>> entry : collected.entrySet()) {
            if (!entry.getValue().isEmpty()) {
                merged.put(entry.getKey(), entry.getValue());
            }
        }
        return merged;
    }

    /**
     * Walks {@code folder} and every descendant folder, adding each Content Fragment child's
     * collected fields to {@code sink}. Recursion is bounded so a malformed DAM tree cannot loop.
     */
    private static final int FRAGMENT_FOLDER_MAX_DEPTH = 5;

    /** Marks a DAM asset's node - has jcr:content/renditions we must NOT walk into. */
    private static final String DAM_ASSET = "dam:Asset";

    private void collectFragments(final Resource folder,
                                  final ComponentMapping.ContentFragmentFolderRef ref,
                                  final Map<String, Object> authored,
                                  final Map<String, List<String>> sink,
                                  final int depth) {
        for (final Resource child : folder.getChildren()) {
            final Map<String, String> elements = ContentFragmentReader.readElements(child);
            if (elements == null) {
                // Not a Content Fragment. Recurse only into real folders - a dam:Asset's
                // jcr:content/renditions subtree carries hundreds of metadata sub-nodes per file
                // and stalls the walk against a page-load budget without ever finding a fragment.
                if (depth < FRAGMENT_FOLDER_MAX_DEPTH && isFolderContainer(child) && child.hasChildren()) {
                    collectFragments(child, ref, authored, sink, depth + 1);
                }
                continue;
            }
            for (final ComponentMapping.ContentFragmentFolderRef.Collect c : ref.getCollects()) {
                if (!matchesFilter(c, elements, authored)) {
                    continue;
                }
                String value = null;
                for (final String field : c.getFields()) {
                    final String candidate = elements.get(field);
                    if (StringUtils.isNotBlank(candidate)) {
                        value = candidate;
                        break;
                    }
                }
                if (value != null) {
                    sink.get(c.getInjectAs()).add(value);
                } else if (c.keepBlank()) {
                    // Preserve item count/order alignment with the folder even when a fragment
                    // carries none of the requested fields; client can still show a placeholder.
                    sink.get(c.getInjectAs()).add("");
                }
            }
        }
    }

    /**
     * True when {@code child} is a container the folder walk should descend into. Everything
     * except a plain {@code dam:Asset} node counts (folders under any primary type, and
     * {@code nt:unstructured} mock nodes used in tests).
     */
    private static boolean isFolderContainer(final Resource child) {
        return !DAM_ASSET.equals(child.getValueMap().get("jcr:primaryType", String.class));
    }

    /**
     * True when the collect applies to this fragment. Without match constraints, always true. With
     * them, the CF's {@code matchField} value must appear in the authored list at
     * {@code matchAgainst} - the block hardcoded a set of SKUs and only their fragments count.
     */
    private static boolean matchesFilter(final ComponentMapping.ContentFragmentFolderRef.Collect c,
                                          final Map<String, String> elements,
                                          final Map<String, Object> authored) {
        final String matchField = c.getMatchField();
        final String matchAgainst = c.getMatchAgainst();
        if (matchField == null || matchAgainst == null) {
            return true;
        }
        final String value = elements.get(matchField);
        if (StringUtils.isBlank(value)) {
            return false;
        }
        final Object allowed = authored.get(matchAgainst);
        if (allowed instanceof Object[]) {
            for (final Object item : (Object[]) allowed) {
                if (item != null && value.equals(String.valueOf(item))) {
                    return true;
                }
            }
            return false;
        }
        if (allowed instanceof Iterable) {
            for (final Object item : (Iterable<?>) allowed) {
                if (item != null && value.equals(String.valueOf(item))) {
                    return true;
                }
            }
            return false;
        }
        return allowed != null && value.equals(String.valueOf(allowed));
    }

    /** Strips scheme+host off an absolute http(s) URL, keeping only path. Idempotent on paths. */
    private static String stripHost(final String value) {
        if (!StringUtils.startsWithAny(value, "http://", "https://")) {
            return value;
        }
        try {
            return java.net.URI.create(value).getPath();
        } catch (final IllegalArgumentException e) {
            return value; // malformed - keep as-is, the getResource below will just return null
        }
    }

    /**
     * Emits a layout container as a {@code panel} whose {@code content} is its children.
     *
     * <p>A panel with nothing in it is dropped rather than emitted empty - an authored section that
     * ended up with no content is noise to a client, not a layout instruction.</p>
     */
    private void emitPanel(final Resource resource, final List<Map<String, Object>> sink,
                           final int depth, final String resourceType,
                           final Resource ancestorSection) {
        final List<Map<String, Object>> children = new ArrayList<>();
        collectComponents(resource, children, depth + 1, true, ancestorSection);

        if (children.isEmpty()) {
            return;
        }

        // A section whose only child is a banner must render full-bleed, so it is emitted as a
        // direct sibling rather than boxed in a panel that would inset and constrain it.
        if (children.size() == 1 && isBanner(children.get(0))) {
            final Map<String, Object> banner = children.get(0);
            asMeta(banner.get(SchemaConstants.KEY_META)).put(SchemaConstants.KEY_ORDER, sink.size());
            sink.add(banner);
            return;
        }

        final Map<String, Object> meta = new LinkedHashMap<>();
        meta.put(SchemaConstants.KEY_ID, AbstractComponentModel.generateId(resource.getPath()));
        meta.put(SchemaConstants.KEY_ORDER, sink.size());
        meta.put(SchemaConstants.KEY_LAYOUT, layoutOf(resourceType));
        meta.put(SchemaConstants.KEY_COMPONENT, SchemaConstants.COMPONENT_PANEL);
        // Reference contract carries showBorder/itemsFillWidth/wrapOverflowing on every panel
        // meta. AEM has no equivalent property, so emit the target's own defaults rather than omit
        // them - itemsFillWidth true is what stretches cards to the panel width.
        final ValueMap panelProps = resource.getValueMap();
        meta.put("showBorder", panelProps.get("showBorder", Boolean.FALSE));
        meta.put("itemsFillWidth", panelProps.get("itemsFillWidth", Boolean.TRUE));
        meta.put("wrapOverflowing", panelProps.get("wrapOverflowing", Boolean.FALSE));

        final boolean vertical = "vertical".equals(meta.get(SchemaConstants.KEY_LAYOUT));

        final Map<String, Object> entry = new LinkedHashMap<>();
        entry.put(SchemaConstants.KEY_META, meta);
        entry.put(SchemaConstants.KEY_STYLES, panelStyles(resource, vertical));
        entry.put(SchemaConstants.KEY_CONTENT, children);
        sink.add(entry);
    }

    private static boolean isBanner(final Map<String, Object> entry) {
        return SchemaConstants.COMPONENT_BANNER.equals(asMeta(entry.get(SchemaConstants.KEY_META))
                .get(SchemaConstants.KEY_COMPONENT));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMeta(final Object meta) {
        return meta instanceof Map ? (Map<String, Object>) meta : Collections.emptyMap();
    }

    /**
     * Reference contract's panel styles keyset, in field order. Every key is always emitted so
     * the shape is fixed; missing properties fall back to an empty string (a client rendering
     * with these as CSS then simply skips them). Values authored on the panel resource win in
     * their raw form - a gap can be a number or a CSS length string, and coercing here would
     * lose the author's intent.
     */
    private static final String[] PANEL_STYLE_KEYS = {
        "gap", "margin", "padding", "alignItems", "justifyContent",
        "borderRadius", "backgroundColor", "height", "marginTop",
    };

    /**
     * Spacing the reference contract carries on every content panel. AEM sections rarely author
     * these, and without them the panel manages no spacing - cards touch and the client cannot
     * size them, so images crop. Applied last, after authored values and style tokens, so both
     * still win.
     */
    private static final Map<String, Object> PANEL_SPACING_DEFAULTS = Map.of(
            "gap", 20, "margin", 40, "padding", 0);

    private Map<String, Object> panelStyles(final Resource resource, final boolean vertical) {
        final Map<String, Object> styles = new LinkedHashMap<>();
        final ValueMap props = resource.getValueMap();
        for (final String key : PANEL_STYLE_KEYS) {
            final Object value = props.get(key);
            styles.put(key, value == null ? "" : value);
        }
        // The style resolver adds anything the panel picked up from its section's style tokens
        // (a design-system class mapping); we let those overlay the raw keys so an author's
        // token overrides the fallback but not their explicit property.
        for (final Map.Entry<String, Object> tokenStyle : styleResolver.stylesOf(resource).entrySet()) {
            if ("".equals(styles.get(tokenStyle.getKey()))) {
                styles.put(tokenStyle.getKey(), tokenStyle.getValue());
            } else if (!styles.containsKey(tokenStyle.getKey())) {
                styles.put(tokenStyle.getKey(), tokenStyle.getValue());
            }
        }
        // The panel is the sole owner of card spacing, so fill any gap/margin/padding the author
        // and tokens both left blank with the reference contract's values.
        for (final Map.Entry<String, Object> spacing : PANEL_SPACING_DEFAULTS.entrySet()) {
            if ("".equals(styles.get(spacing.getKey()))) {
                styles.put(spacing.getKey(), spacing.getValue());
            }
        }
        // Vertical panels stack cards, and the reference contract distributes them with
        // space-between; fill it in when neither the author nor a style token set it.
        if (vertical && "".equals(styles.get("justifyContent"))) {
            styles.put("justifyContent", "space-between");
        }
        return styles;
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

    /**
     * True for a section container specifically, as opposed to a columns/row/col wrapper - so the
     * walker knows when to update the "nearest enclosing section" it carries down for
     * {@code fromParent} field resolution.
     */
    private static boolean isSectionType(final String resourceType) {
        if (SchemaConstants.RT_FRANKLIN_SECTION.equals(resourceType)) {
            return true;
        }
        return "section".equals(StringUtils.substringAfterLast(resourceType, "/"));
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

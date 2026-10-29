package com.edsMobileApp.core.services.impl;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.lang3.StringUtils;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ValueMap;
import org.apache.sling.models.factory.ModelFactory;
import org.jsoup.Jsoup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.edsMobileApp.core.models.impl.AbstractComponentModel;
import com.edsMobileApp.core.models.impl.GenericComponent;
import com.edsMobileApp.core.schema.EdsMobileAppComponent;
import com.edsMobileApp.core.schema.SchemaConstants;
import com.edsMobileApp.core.services.FormSchemaService;
import com.edsMobileApp.core.services.UrlService;
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
        private static final List<String> PRODUCT_DETAIL_COMPONENTS = List.of(
            "productImage", "productTitle", "productPrice", "productAddToCartButton", "productDescription");

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
    private SiteDefaults siteDefaults;

    /** The per-site defaults this walker reads for its own site lookups - the inline-link extraction
     * switch and the screen list - forwarded to the internal {@link FormBlockBuilder} so form headings
     * can honour per-site overrides. Set once, on activation, by the exporter. */
    void setSiteDefaults(final SiteDefaults siteDefaults) {
        this.siteDefaults = siteDefaults;
        this.formBuilder.setSiteDefaults(siteDefaults);
    }

    /**
     * The {@code loginScreen}/{@code profileScreen}/{@code registrationScreen} keys this export's own
     * {@code navigation} already resolved to - set once per {@code export()} call (see
     * {@code PageJsonExporterImpl#export}), since it varies per export and not, like
     * {@link #setSiteDefaults}, only per bundle activation. Used to point any form's schema-declared
     * {@link FormBlockBuilder#secondaryLink} at the same screen the app's own nav sends a user to for
     * that role.
     */
    private Map<String, String> navScreensByRole = Collections.emptyMap();

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

    /** See {@link #navScreensByRole}. */
    void setNavScreensByRole(final Map<String, String> navScreensByRole) {
        this.navScreensByRole = navScreensByRole == null ? Collections.emptyMap() : navScreensByRole;
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
        return componentsOf(page, assetHost, hierarchical, applyScreenExclusions, Collections.emptySet());
    }

    /**
     * @param knownScreenKeys every screen key the app will expose, used to decide whether a
     *                        {@code Cards} block's items all resolve to real screens - see
     *                        {@link #cardsBlockAsList}
     */
    List<Map<String, Object>> componentsOf(final Page page, final String assetHost,
                                           final boolean hierarchical,
                                           final boolean applyScreenExclusions,
                                           final Set<String> knownScreenKeys) {
        return componentsOf(page, assetHost, hierarchical, applyScreenExclusions, knownScreenKeys, null);
    }

    List<Map<String, Object>> componentsOf(final Page page, final String assetHost,
                                           final boolean hierarchical,
                                           final boolean applyScreenExclusions,
                                           final Set<String> knownScreenKeys,
                                           final Map<String, String> screenThumbnailOverrides) {
        final List<Map<String, Object>> components = new ArrayList<>();
        final Resource container = resolveContainer(page);
        if (container != null) {
            collectComponents(container, components, 0, hierarchical, null,
                    knownScreenKeys == null ? Collections.emptySet() : knownScreenKeys,
                    screenThumbnailOverrides);
        }
        if (applyScreenExclusions) {
            dropExcludedDeep(components);
        }
        prefixAssetsDeep(components, assetHost, page);
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
            if (isSystemNode(child) || (isSectionType(child.getResourceType())
                    && Boolean.TRUE.equals(child.getValueMap().get("hide-in-mobile", Boolean.class)))) {
                continue;
            }
            EdsMobileAppComponent model = adaptStrictly(child);
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
    private void prefixAssetsDeep(final List<Map<String, Object>> components, final String assetHost,
                                  final Page page) {
        if (StringUtils.isBlank(assetHost)) {
            return;
        }
        boolean inserted = false;
        for (int i = 0; i < components.size(); i++) {
            final Map<String, Object> component = components.get(i);
            final Object styles = component.get(SchemaConstants.KEY_STYLES);
            if (styles instanceof Map) {
                @SuppressWarnings("unchecked")
                final Map<String, Object> styleProps = (Map<String, Object>) styles;
                prefixAssets(styleProps, assetHost);
            }
            final Object content = component.get(SchemaConstants.KEY_CONTENT);
            if (content instanceof Map) {
                @SuppressWarnings("unchecked")
                final Map<String, Object> payload = (Map<String, Object>) content;
                prefixAssets(payload, assetHost);
                if (isHtmlText(component)) {
                    rewriteHtmlTextLinks(payload);
                    // Always consumed, whatever it says: it is an authoring instruction, not content.
                    final LinkRender render = linkRenderOf(payload);
                    if (render == LinkRender.SPLIT) {
                        final List<Map<String, Object>> parts = splitAtInlineLinks(component, payload);
                        if (parts != null) {
                            components.remove(i);
                            components.addAll(i, parts);
                            i += parts.size() - 1;
                            inserted = true;
                        }
                    } else if (render == LinkRender.EXTRACT) {
                        for (final Map<String, Object> button : extractInlineLinkButtons(payload)) {
                            components.add(++i, button);
                            inserted = true;
                        }
                    }
                }
            } else if (content instanceof List) {
                @SuppressWarnings("unchecked")
                final List<Map<String, Object>> children = (List<Map<String, Object>>) content;
                prefixAssetsDeep(children, assetHost, page);
            }
        }
        if (inserted) {
            reindexOrder(components);
        }
    }

    /** How one htmlText block's links are rendered - see {@link #linkRenderOf}. */
    private enum LinkRender {
        /** Anchors stay exactly where they were authored; the app navigates them itself. */
        INLINE,
        /** Each navigable anchor becomes a button, all of them after the whole text. */
        EXTRACT,
        /** The text is split so each navigable anchor's button sits after the block that held it. */
        SPLIT
    }

    /** An author's per-block override of the site's own link rendering - see {@link LinkRender}. */
    private static final String LINK_STYLE_KEY = "mobilelinkstyle";

    /**
     * How this block's links render: the authored {@code mobilelinkstyle} when it names a mode,
     * otherwise split - every site, with no per-site setting.
     *
     * <p>The key is removed from {@code payload} either way. It is an instruction to this exporter,
     * not a piece of content, and {@code appendUnconsumedExtras} would otherwise pass it through to
     * the client as a stray field - no mapping rule consumes it.</p>
     *
     * @param payload the block's content, with {@code mobilelinkstyle} removed on return
     */
    private static LinkRender linkRenderOf(final Map<String, Object> payload) {
        final Object authored = payload.remove(LINK_STYLE_KEY);
        final String style = authored == null ? "" : String.valueOf(authored).trim();
        if ("link".equalsIgnoreCase(style)) {
            return LinkRender.INLINE;
        }
        if ("button".equalsIgnoreCase(style)) {
            return LinkRender.EXTRACT;
        }
        if ("split".equalsIgnoreCase(style)) {
            return LinkRender.SPLIT;
        }
        return LinkRender.SPLIT;
    }

    /** Renumbers every component's {@code meta.order} to match its actual position in the list. */
    private static void reindexOrder(final List<Map<String, Object>> components) {
        for (int i = 0; i < components.size(); i++) {
            asMeta(components.get(i).get(SchemaConstants.KEY_META)).put(SchemaConstants.KEY_ORDER, i);
        }
    }

    private static boolean isHtmlText(final Map<String, Object> component) {
        final Object meta = component.get(SchemaConstants.KEY_META);
        return meta instanceof Map && "htmlText".equals(((Map<?, ?>) meta).get(SchemaConstants.KEY_COMPONENT));
    }

    /**
     * The closing tag of the block a link sits in - where the text is split to place its button.
     *
     * <p>A list closes at {@code </ul>}, not at the {@code </li>} of the item holding the link: a
     * list is one block to the reader, and splitting inside it tore the list in half, leaving an
     * unclosed {@code <ul>} on one part and an orphan {@code </ul>} opening the next. A service
     * directory of {@code <h3>} plus a list of links now keeps each heading with its own buttons,
     * which is the grouping the author wrote.</p>
     */
    private static final Pattern BLOCK_END = Pattern.compile(
            "</(p|ul|ol|h[1-6]|div|blockquote)\\s*>", Pattern.CASE_INSENSITIVE);

    /** A paragraph left with nothing in it once its link was taken out. */
    private static final Pattern EMPTY_PARAGRAPH = Pattern.compile(
            "<p[^>]*>\\s*(<br\\s*/?>)?\\s*</p>", Pattern.CASE_INSENSITIVE);

    /** Matches one {@code <a href="...">label</a>} - label captured raw, inner markup and all. */
    private static final Pattern INLINE_LINK = Pattern.compile(
            "<a\\s+[^>]*href\\s*=\\s*([\"'])([^\"']*)\\1[^>]*>(.*?)</a>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    /**
     * Extracts every navigable {@code <a>} out of an htmlText block's {@code text} into its own
     * sibling {@code button} component (inserted right after it by the caller), removing the
     * anchor - label and all - from the text rather than just de-linking it, so the label doesn't
     * appear twice ("Book Trip" as the button, not also sitting there as dead plain text beside
     * it). An anchor whose href isn't a real navigation target ({@code #}, {@code mailto:},
     * {@code tel:}, {@code javascript:}, blank) has no button to stand in for it, so it is
     * de-linked in place instead - its label kept as plain text - rather than silently dropped.
     *
     * <p>A link embedded in rich text is never tappable in the app regardless of what its href says
     * (confirmed against every reference app export available - none embeds a tappable link inside
     * rich text) - a dedicated {@code button} is the shape the app actually routes through.</p>
     *
     * @return the buttons to insert, in the order their anchors appeared; empty when the text had
     *         no navigable link (the text is left untouched in that case)
     */
    private static List<Map<String, Object>> extractInlineLinkButtons(final Map<String, Object> payload) {
        final Object value = payload.get("text");
        if (!(value instanceof String) || !((String) value).contains("href")) {
            return Collections.emptyList();
        }
        final String text = (String) value;
        final Matcher matcher = INLINE_LINK.matcher(text);
        final StringBuffer delinked = new StringBuffer(text.length());
        final List<Map<String, Object>> buttons = new ArrayList<>();
        while (matcher.find()) {
            final String href = matcher.group(2).trim();
            // jsoup rather than a tag-stripping regex: the label is plain text on the button, so a
            // character reference in the authored markup ("Accounting &amp; Reporting") has to be
            // decoded here or the app renders the entity itself.
            final String label = Jsoup.parse(matcher.group(3)).text().trim();
            final String linkTo = isExternalUrl(href) ? href : navigableScreenKey(href);
            final boolean hasButton = linkTo != null && !label.isEmpty();
            matcher.appendReplacement(delinked, hasButton ? "" : Matcher.quoteReplacement(label));
            if (hasButton) {
                buttons.add(inlineLinkButton(label, linkTo));
            }
        }
        if (buttons.isEmpty()) {
            return Collections.emptyList();
        }
        matcher.appendTail(delinked);
        payload.put("text", dropEmptiedWrappers(delinked.toString()));
        return buttons;
    }

    /**
     * An element left with nothing inside it once its anchor was lifted out. Removing the anchor's
     * label along with the anchor is deliberate - it lives on the button now - but the markup that
     * wrapped it stays behind, and a service directory authored as a list came out as
     * {@code <ul><li></li><li></li></ul>}: a column of empty bullets above the buttons.
     *
     * <p>Only wrappers are listed. Anything not here keeps whatever the author left in it.</p>
     */
    private static final Pattern EMPTIED_WRAPPER = Pattern.compile(
            "<(p|li|ul|ol|span|strong|em|h[1-6])(?:\\s[^>]*)?>\\s*</\\1\\s*>",
            Pattern.CASE_INSENSITIVE);

    /**
     * Drops every {@link #EMPTIED_WRAPPER}, repeatedly: emptying the {@code <li>} children of a list
     * is what empties the {@code <ul>} around them, so one pass is not enough.
     *
     * @return {@code text} with the emptied wrappers gone, the rest of the markup untouched
     */
    private static String dropEmptiedWrappers(final String text) {
        String current = text;
        // ponytail: five passes, not until-stable - five levels of wrapper nesting around one link
        // is already pathological, and a bound beats trusting the regex to always shrink.
        for (int pass = 0; pass < 5; pass++) {
            final String next = EMPTIED_WRAPPER.matcher(current).replaceAll("");
            if (next.equals(current)) {
                break;
            }
            current = next;
        }
        return current;
    }

    /**
     * The screen key {@code href} navigates to - the last path segment of a {@code /content/...}
     * path (or a full URL ending in one), trailing {@code .html} dropped, e.g.
     * {@code /content/wknd-fly/.../destinations/mexico.html} becomes {@code mexico}. An href with no
     * path to strip (already a bare screen key) is used as-is.
     *
     * @return the screen key, or {@code null} when {@code href} names no screen at all ({@code #},
     *         {@code mailto:}, {@code tel:}, {@code javascript:}, blank)
     */
    private static String navigableScreenKey(final String href) {
        if (StringUtils.isBlank(href) || href.startsWith("#")
                || href.regionMatches(true, 0, "mailto:", 0, 7)
                || href.regionMatches(true, 0, "tel:", 0, 4)
                || href.regionMatches(true, 0, "javascript:", 0, 11)) {
            return null;
        }
        final String leaf = screenKeyOf(href);
        return leaf != null ? leaf : href;
    }

    /**
     * An {@code http(s)} link into somebody else's site, as opposed to one typed against an AEM
     * publish host for a page of this one. {@link #screenKeyOf} takes the last path segment of
     * whatever it is handed, which for an external URL yields a plausible-looking key naming no
     * screen at all ({@code https://www.adobe.com/legal/terms.html} becomes {@code terms}), so an
     * external link has to be recognised before the leaf is taken rather than after.
     *
     * <p>A protocol-relative link ({@code //example.com/x}) and one typed without a scheme
     * ({@code www.example.com}) are external too: neither names a screen of this app.</p>
     *
     * @return true when {@code href} is absolute and its path is not an AEM {@code /content/} path
     */
    private static boolean isExternalUrl(final String href) {
        if (href.startsWith("//") || href.regionMatches(true, 0, "www.", 0, 4)) {
            return true;
        }
        if (!href.regionMatches(true, 0, "http://", 0, 7)
                && !href.regionMatches(true, 0, "https://", 0, 8)) {
            return false;
        }
        final String afterHost = StringUtils.substringAfter(StringUtils.substringAfter(href, "://"), "/");
        return !("/" + afterHost).startsWith(ContentPaths.CONTENT_PREFIX);
    }

    /**
     * Matches an htmlText block's {@code <a href="...">} authored as a full URL into a non-asset
     * {@code /content/...} page - e.g. a "Book Trip" CTA typed against one specific AEM
     * environment's publish host, rather than a repository-relative path. Never matched by
     * {@link ContentPaths}'s own rewrite, which only recognises a bare {@code /content/...} href,
     * so a link authored this way passes through completely broken otherwise. Captures the quote
     * character and the repository path; the scheme and host are consumed by the match but dropped
     * from the replacement entirely - a {@code /content/dam/...} link (a downloadable asset, not a
     * screen) is left alone by the {@code (?!dam/)} exclusion.
     */
    private static final Pattern HTML_TEXT_ABSOLUTE_LINK = Pattern.compile(
            "\\bhref\\s*=\\s*([\"'])https?://[^/\"']+(/content/(?!dam/)[^\"']*)\\1");

    /**
     * Rewrites every {@code <a href="...">} inside an htmlText block's {@code text} field matching
     * {@link #HTML_TEXT_ABSOLUTE_LINK} to the in-app screen key that link names - the same key a
     * curated {@code link}/{@code linkTo} field resolves to (see
     * {@code ComponentMapping#screenTarget}). A field with nothing to rewrite, or a match whose
     * leaf can't be derived (already bare), is left exactly as authored.
     */
    private static void rewriteHtmlTextLinks(final Map<String, Object> payload) {
        final Object value = payload.get("text");
        if (!(value instanceof String)) {
            return;
        }
        final String text = (String) value;
        if (text.indexOf('<') < 0 || !text.contains("href") || !text.contains(ContentPaths.CONTENT_PREFIX)) {
            return;
        }
        final Matcher matcher = HTML_TEXT_ABSOLUTE_LINK.matcher(text);
        final StringBuffer rewritten = new StringBuffer(text.length());
        while (matcher.find()) {
            final String screenKey = screenKeyOf(matcher.group(2));
            final String replacement = screenKey == null
                    ? matcher.group(0)
                    : "href=" + matcher.group(1) + screenKey + matcher.group(1);
            matcher.appendReplacement(rewritten, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(rewritten);
        payload.put("text", rewritten.toString());
    }

    /**
     * The screen key a {@code /content/...} page path names - its last path segment, with a
     * trailing {@code .html} dropped - plus whatever query string or fragment it carried.
     *
     * @return the screen key (with any {@code ?}/{@code #} suffix appended), or {@code null} when
     *         {@code path} has no segment to name one
     */
    private static String screenKeyOf(final String path) {
        final String trimmed = path.trim();
        final int cut = StringUtils.indexOfAny(trimmed, '?', '#');
        final String suffix = cut >= 0 ? trimmed.substring(cut) : "";
        final String withoutSuffix = StringUtils.stripEnd(cut >= 0 ? trimmed.substring(0, cut) : trimmed, "/");
        if (withoutSuffix.indexOf('/') < 0) {
            return null;
        }
        final String leaf =
                StringUtils.removeEndIgnoreCase(StringUtils.substringAfterLast(withoutSuffix, "/"), ".html");
        return leaf.isEmpty() ? null : leaf + suffix;
    }

    /**
     * Splits an htmlText block at each navigable link: the text up to the end of the paragraph that
     * held the link (the link itself taken out), then the link's button, then on to the next - so
     * the buttons keep their place in the reading order instead of all following the whole text. Two
     * links in one paragraph give two buttons after it. A link that is not navigable is kept as its
     * plain label; a part with no text left in it (the block opened with a link, say) is dropped.
     *
     * @return the components that replace {@code component}, in order; {@code null} when the text
     *         has no navigable link, so the block is left exactly as authored
     */
    private static List<Map<String, Object>> splitAtInlineLinks(final Map<String, Object> component,
                                                                 final Map<String, Object> payload) {
        final Object value = payload.get("text");
        if (!(value instanceof String) || !((String) value).contains("href")) {
            return null;
        }
        final String text = (String) value;
        final Matcher matcher = INLINE_LINK.matcher(text);
        final List<int[]> spans = new ArrayList<>();
        final List<String> labels = new ArrayList<>();
        final List<String> fallbacks = new ArrayList<>();
        final List<String> targets = new ArrayList<>();
        boolean navigable = false;
        while (matcher.find()) {
            final String href = matcher.group(2).trim();
            // jsoup, not a tag-stripping regex: the label is plain text on the button, so a
            // character reference has to be decoded or the app renders the entity itself.
            final String label = Jsoup.parse(matcher.group(3)).text().trim();
            // An external link is the button's own target, URL and all; screenKeyOf would turn it into
            // the last path segment, naming no screen.
            final String linkTo = isExternalUrl(href) ? href : navigableScreenKey(href);
            spans.add(new int[] {matcher.start(), matcher.end()});
            labels.add(label);
            // Standing in for a link that gets no button: a dead one keeps just its label as plain text.
            fallbacks.add(label);
            final boolean hasButton = linkTo != null && !label.isEmpty();
            targets.add(hasButton ? linkTo : null);
            navigable |= hasButton;
        }
        if (!navigable) {
            return null;
        }
        final List<Map<String, Object>> parts = new ArrayList<>();
        final String originalId = String.valueOf(asMeta(component.get(SchemaConstants.KEY_META))
                .get(SchemaConstants.KEY_ID));
        int cursor = 0;
        int next = 0;
        while (next < spans.size()) {
            final Matcher blockEnd = BLOCK_END.matcher(text);
            final int split = blockEnd.find(spans.get(next)[1]) ? blockEnd.end() : text.length();
            final StringBuilder segment = new StringBuilder();
            final List<Map<String, Object>> buttons = new ArrayList<>();
            int position = cursor;
            while (next < spans.size() && spans.get(next)[0] < split) {
                segment.append(text, position, spans.get(next)[0]);
                if (targets.get(next) != null) {
                    buttons.add(inlineLinkButton(labels.get(next), targets.get(next)));
                } else {
                    segment.append(fallbacks.get(next));
                }
                position = spans.get(next)[1];
                next++;
            }
            segment.append(text, position, split);
            cursor = split;
            addTextPart(parts, component, payload, segment.toString(), originalId);
            parts.addAll(buttons);
        }
        addTextPart(parts, component, payload, text.substring(cursor), originalId);
        return parts;
    }

    /** Adds {@code html} as a copy of {@code component} carrying it, unless it holds no text. */
    private static void addTextPart(final List<Map<String, Object>> parts, final Map<String, Object> component,
                                    final Map<String, Object> payload, final String html,
                                    final String originalId) {
        // EMPTY_PARAGRAPH first for the <p><br></p> an editor leaves behind, then the general
        // sweep: lifting an anchor out of a list item empties the item, which empties the list.
        final String cleaned = dropEmptiedWrappers(EMPTY_PARAGRAPH.matcher(html).replaceAll(""));
        if (StringUtils.isBlank(cleaned.replaceAll("<[^>]*>", "").replace("&nbsp;", " "))) {
            return;
        }
        final Map<String, Object> meta = new LinkedHashMap<>(asMeta(component.get(SchemaConstants.KEY_META)));
        boolean first = true;
        for (final Map<String, Object> existing : parts) {
            if ("htmlText".equals(asMeta(existing.get(SchemaConstants.KEY_META)).get(SchemaConstants.KEY_COMPONENT))) {
                first = false;
                break;
            }
        }
        if (!first) {
            meta.put(SchemaConstants.KEY_ID, AbstractComponentModel.generateId(originalId + "/part" + parts.size()));
        }
        final Map<String, Object> content = new LinkedHashMap<>(payload);
        content.put("text", cleaned.trim());
        final Map<String, Object> part = new LinkedHashMap<>();
        part.put(SchemaConstants.KEY_META, meta);
        final Object styles = component.get(SchemaConstants.KEY_STYLES);
        if (styles instanceof Map) {
            part.put(SchemaConstants.KEY_STYLES, new LinkedHashMap<>((Map<?, ?>) styles));
        }
        part.put(SchemaConstants.KEY_CONTENT, content);
        parts.add(part);
    }

    /**
     * A button carrying one extracted inline link, matching every field
     * {@code edsMobileApp/mappings/button.json} would default it to for an authored button with no
     * {@code linkType} set ({@code variant: "contained"}) - so a synthetically-extracted button is
     * indistinguishable, field for field, from one the mapping produced. Only {@code label},
     * {@code linkTo} and {@code alignment} (left, since this sits inline with a sentence rather
     * than standing alone as a CTA) are set to anything but the mapping's own default.
     */
    private static Map<String, Object> inlineLinkButton(final String label, final String linkTo) {
        final Map<String, Object> meta = new LinkedHashMap<>();
        meta.put(SchemaConstants.KEY_ID, AbstractComponentModel.generateId(linkTo + ':' + label));
        meta.put(SchemaConstants.KEY_COMPONENT, "button");
        final Map<String, Object> content = new LinkedHashMap<>();
        content.put("icon", "");
        content.put("label", label);
        content.put("eventType", "");
        content.put("formId", "");
        content.put("linkTo", linkTo);
        content.put("message", "");
        content.put("variant", "text");
        content.put("alignment", "left");
        content.put("customEvent", "");
        final Map<String, Object> styles = new LinkedHashMap<>();
        styles.put("marginTop", "");
        final Map<String, Object> button = new LinkedHashMap<>();
        button.put(SchemaConstants.KEY_META, meta);
        button.put(SchemaConstants.KEY_STYLES, styles);
        button.put(SchemaConstants.KEY_CONTENT, content);
        return button;
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
            // custommobilestyle is always consumed separately, into styles (see
            // mergeCustomStyles) - never by a rule's own fields, so it would otherwise look
            // unconsumed here and leak a raw duplicate of what styles already carries.
            if (consumed.contains(key) || target.containsKey(key) || CustomMobileStyle.FIELD_NAME.equals(key)) {
                continue;
            }
            target.put(key, entry.getValue());
        }
    }

    /**
     * Walks the component tree depth-first, in authoring order, flattening every structural node
     * into a single ordered list.
     *
     * <p>Hand-built EdsMobileApp pages are one level deep, but Edge Delivery content nests
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
                                   final Resource ancestorSection, final Set<String> knownScreenKeys,
                                   final Map<String, String> screenThumbnailOverrides) {
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
            if (isSectionType(resourceType)
                    && Boolean.TRUE.equals(child.getValueMap().get("hide-in-mobile", Boolean.class))) {
                continue;
            }
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
                    emitPanel(child, sink, depth, resourceType, nextSection, knownScreenKeys,
                            screenThumbnailOverrides);
                } else {
                    collectComponents(child, sink, depth + 1, false, nextSection, knownScreenKeys,
                            screenThumbnailOverrides);
                }
                continue;
            }

            // A columns block's column is a layout cell, not a leaf: emit it as its own panel so its
            // basic elements (text, image, ...) stay grouped per column, with the column's own
            // itemAlignment driving the panel layout. The block/item resource type is shared by every
            // block's items, so a column is told apart by its model rather than its resource type.
            if (isColumnItem(child)) {
                if (hierarchical) {
                    emitPanel(child, sink, depth, resourceType, ancestorSection, knownScreenKeys,
                            screenThumbnailOverrides);
                } else {
                    collectComponents(child, sink, depth + 1, false, ancestorSection, knownScreenKeys,
                            screenThumbnailOverrides);
                }
                continue;
            }

            // A registered model wins so curated components keep their exact shape; everything
            // else is projected generically, which is what lets the API serve an arbitrary site.
            EdsMobileAppComponent model = adaptStrictly(child);
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

            if (hasChildren && hierarchical && isCardsBlock(child)) {
                Map<String, Object> list = null;
                if (rendersCardsAsList(child)) {
                    list = cardsBlockAsList(child, sink.size(), knownScreenKeys, screenThumbnailOverrides);
                    if (list == null) {
                        list = cardsBlockAsProductList(child, sink.size());
                    }
                }
                if (list != null) {
                    sink.add(list);
                } else {
                    collectComponents(child, sink, depth + 1, hierarchical, ancestorSection, knownScreenKeys,
                            screenThumbnailOverrides);
                }
                continue;
            }

            if (hierarchical) {
                final List<Map<String, Object>> schema = formSchemaService.getSchema(model.getComponentName());
                if (isQuizSchema(schema)) {
                    sink.add(formBuilder.build(child, model.getComponentName(), model.getId(),
                            sink.size(), authored));
                    continue;
                }
            }

            // Children but nothing of its own to say: a wrapper such as an Edge Delivery cards or
            // accordion block. Exporting it would emit an empty component and hide its items.
            // Evaluated from the authored content, before any mapping, so that a rule cannot turn a
            // wrapper into a component by supplying constants.
            if (hasChildren && authored.isEmpty()) {
                collectComponents(child, sink, depth + 1, hierarchical, ancestorSection, knownScreenKeys,
                        screenThumbnailOverrides);
                continue;
            }

            final String authoredName = model.getComponentName();
            String componentName = authoredName;
            Map<String, Object> content = authored;
            Map<String, Object> styles = Collections.emptyMap();
            final Map<String, Object> sectionProps = ancestorSection == null
                    ? Collections.emptyMap() : ancestorSection.getValueMap();
            if (hierarchical && "product-detail".equals(authoredName)
                    && child.getValueMap().get("mobileappcustomproductdetails", Boolean.FALSE)) {
                final List<String> enabledComponents = new ArrayList<>();
                for (final String name : PRODUCT_DETAIL_COMPONENTS) {
                    if (child.getValueMap().get("mobileappshow" + name.toLowerCase(Locale.ROOT), Boolean.TRUE)) {
                        enabledComponents.add(name);
                    }
                }
                emitMappedComponents(child, enabledComponents, sectionProps, sink);
                continue;
            }

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
                    content = new LinkedHashMap<>(mapped);
                    styles = mergeCustomStyles(mapping.applyStyles(ruleSource, sectionProps), authored,
                            mapping.customStyleProperty());
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
                if ("sign-in".equals(formName)) {
                    final ComponentMapping loginMapping = mappings.find(formName);
                    if (loginMapping != null) {
                        final Map<String, Object> loginMeta = new LinkedHashMap<>();
                        loginMeta.put(SchemaConstants.KEY_ID, model.getId());
                        loginMeta.put(SchemaConstants.KEY_ORDER, sink.size());
                        loginMeta.put(SchemaConstants.KEY_COMPONENT, loginMapping.getComponent());
                        final Map<String, Object> login = new LinkedHashMap<>();
                        login.put(SchemaConstants.KEY_META, loginMeta);
                        login.put(SchemaConstants.KEY_CONTENT, loginMapping.apply(authored, sectionProps));
                        if (!styles.isEmpty()) {
                            login.put(SchemaConstants.KEY_STYLES, styles);
                        }
                        sink.add(login);
                        continue;
                    }
                }
                final Map<String, Object> resolvedAuthored = formBuilder.withDefaultVariant(formName, authored);
                final Map<String, Object> heading = formBuilder.heading(formName, resolvedAuthored,
                        AbstractComponentModel.generateId(child.getPath() + "/heading"), sink.size(),
                        child.getPath());
                if (heading != null) {
                    sink.add(heading);
                }
                sink.add(formBuilder.build(child, formName, model.getId(), sink.size(), resolvedAuthored));
                final Map<String, Object> secondaryLink = formBuilder.secondaryLink(formName,
                        navScreensByRole, AbstractComponentModel.generateId(child.getPath() + "/secondary-link"),
                        sink.size());
                if (secondaryLink != null) {
                    sink.add(secondaryLink);
                }
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
                collectComponents(child, sink, depth + 1, hierarchical, ancestorSection, knownScreenKeys,
                        screenThumbnailOverrides);
            }
        }
    }

    /**
     * Layers a component's authored {@code custommobilestyle} rows (see {@link CustomMobileStyle})
     * onto the styles its mapping rule already produced, an authored row overriding a same-named
     * rule style since it is the more specific, per-instance choice. Returns {@code mapped}
     * itself, unchanged, when nothing is authored.
     */
    private static Map<String, Object> mergeCustomStyles(final Map<String, Object> mapped,
                                                          final Map<String, Object> authored) {
        return mergeCustomStyles(mapped, authored, CustomMobileStyle.FIELD_NAME);
    }

    /** As above, reading the rows from {@code property} - the one the component's mapping declares. */
    private static Map<String, Object> mergeCustomStyles(final Map<String, Object> mapped,
                                                          final Map<String, Object> authored,
                                                          final String property) {
        final Map<String, Object> custom = CustomMobileStyle.asMap(authored, property);
        if (custom.isEmpty()) {
            return mapped;
        }
        final Map<String, Object> merged = new LinkedHashMap<>(mapped);
        merged.putAll(custom);
        return merged;
    }

    /**
     * True when {@code resource} is an Edge Delivery block explicitly authored as a {@code Cards}
     * group - its own {@code filter} or {@code name} property (stripped from
     * {@link GenericComponent#getContent()} as bookkeeping, so read here straight off the
     * {@link ValueMap}) equals {@code cards}, case-insensitively.
     */
    private static boolean isCardsBlock(final Resource resource) {
        final ValueMap props = resource.getValueMap();
        return "cards".equalsIgnoreCase(props.get(SchemaConstants.PN_FILTER, StringUtils.EMPTY))
                || "cards".equalsIgnoreCase(props.get(SchemaConstants.PN_NAME, StringUtils.EMPTY));
    }

    /**
     * @return the site's {@code defaults.json} {@code screenList.<key>} override for
     *         {@code resource}'s site, or {@code fallback} when the site, the section, or this
     *         specific key is absent or blank - see {@link SiteDefaults#KEY_SCREEN_LIST}.
     */
    /**
     * A screenList presentation value, in precedence order: the block's own Mobile Specific
     * property (one field per value), then the site's {@code screenList} entry in {@code defaults.json}, then the
     * compiled-in default. Blank counts as unset at every level, so a property authored and later
     * cleared falls back rather than emitting empty.
     *
     * <p>The block is the narrowest scope and wins - one row of tiles can be sized differently from
     * another on the same page. The site level stays because a colour is usually a site-wide
     * readability decision: it is what makes wknd-fly's and carvelo's lists legible on their light
     * backgrounds.</p>
     *
     * <p>Every caller passes the value this method replaced as its {@code fallback}, so a block that
     * authors nothing exports exactly what it did before this existed.</p>
     */
    private Object listValue(final Resource block, final String property,
                             final String siteKey, final Object fallback) {
        Object authored = block.getValueMap().get(property);
        if (!isSet(authored)) {
            authored = block.getValueMap().get(property.toLowerCase(Locale.ROOT));
        }
        if (isSet(authored)) {
            return authored;
        }
        if (siteDefaults != null && siteKey != null) {
            final Object site = SiteDefaults.objectMap(
                    siteDefaults.forPath(block.getPath()), SiteDefaults.KEY_SCREEN_LIST).get(siteKey);
            if (isSet(site)) {
                return site;
            }
        }
        return fallback;
    }

    /**
     * As {@link #listValue}, but emitted as a number: an authored text value is parsed, and one that
     * is not a whole number is ignored in favour of {@code fallback} rather than sent as a string.
     */
    private Object listNumber(final Resource block, final String property,
                              final String siteKey, final Integer fallback) {
        final Object value = listValue(block, property, siteKey, fallback);
        if (value instanceof Number || value == null) {
            return value;
        }
        try {
            return Long.valueOf(String.valueOf(value).trim());
        } catch (final NumberFormatException notANumber) {
            return fallback;
        }
    }

    /** Emits the block's authored {@code limit} (0 = show all, so omitted) the same way the
     * {@code category-products-lister} mapping does, so every list caps items through one field. */
    private void putAuthoredLimit(final Resource block, final Map<String, Object> content) {
        final Object limit = listNumber(block, "limit", null, null);
        if (limit instanceof Number && ((Number) limit).longValue() > 0) {
            content.put("limit", limit);
        }
    }

    /** As {@link #listValue}, but an authored value that is not a hex colour is ignored rather than emitted. */
    private Object listColor(final Resource block, final String property,
                             final String siteKey, final String fallback) {
        final Object value = listValue(block, property, siteKey, fallback);
        final String hex = value instanceof String ? FieldRule.asHexColor((String) value) : null;
        return hex != null ? hex : fallback;
    }

    /**
     * The renderers the app's Card component draws. It compares the value against exactly these and
     * renders nothing for any other (a stale {@code below}, say), which blanks the whole list - so
     * anything else falls back to {@code fallback} rather than being passed to the client.
     */
    private static final Set<String> ITEM_RENDERERS =
            Set.of("stacked", "overlay", "partial-overlay", "image-text-one-line", "image");

    /** hideSubtitle is only emitted when authored. */
    private void putHideSubtitle(final Resource block, final Map<String, Object> content) {
        final Object hide = listValue(block, "mobilehidesubtitle", "hideSubtitle", null);
        if (hide != null) {
            content.put("hideSubtitle", Boolean.parseBoolean(String.valueOf(hide)));
        }
    }

    /** Title/subtitle font weights and sizes; a size is only emitted when set. */
    private void putListTextStyles(final Resource block, final Map<String, Object> content) {
        content.put("titleFontWeight", listValue(block, "mobiletitlefontweight", "titleFontWeight", "regular"));
        content.put("subtitleFontWeight",
                listValue(block, "mobilesubtitlefontweight", "subtitleFontWeight", "light"));
        final Object titleSize = listValue(block, "mobiletitlesize", "titleSize", null);
        if (titleSize != null) {
            content.put("titleSize", titleSize);
        }
        final Object subtitleSize = listValue(block, "mobilesubtitlesize", "subtitleSize", null);
        if (subtitleSize != null) {
            content.put("subtitleSize", subtitleSize);
        }
    }

    private static Object knownItemRenderer(final Object value, final String fallback) {
        return value instanceof String && ITEM_RENDERERS.contains(value) ? value : fallback;
    }

    private static boolean isSet(final Object value) {
        return value != null && !(value instanceof String && StringUtils.isBlank((String) value));
    }

    /**
     * Whether a Cards block has opted in to becoming a {@code screenList} on whatever cards it has,
     * via a {@code mobilerenderaslist} boolean authored on the block itself.
     *
    * <p>Absent or false exports individual cards without entering list-specific logic.
    * True enables list conversion and skips cards whose links do not resolve to a screen.
    * A screenList uses the target screens' titles and copy rather than the cards' authored copy.</p>
     *
     * <p>Read straight off the block's {@link ValueMap}, the same way {@link #isCardsBlock} reads
     * {@code filter} and {@code name}, so it works whether the property is authored through a block
     * model or set on the node directly.</p>
     */
    private static boolean rendersCardsAsList(final Resource resource) {
        return resource.getValueMap().get("mobilerenderaslist", Boolean.FALSE);
    }

    private void emitMappedComponents(final Resource resource, final List<String> componentNames,
                                      final Map<String, Object> sectionProps,
                                      final List<Map<String, Object>> sink) {
        final ValueMap props = resource.getValueMap();
        for (final String componentName : componentNames) {
            final ComponentMapping matched = mappings.find(componentName);
            if (matched == null) {
                LOG.warn("No mapping for expanded component '{}' at {} - skipping it", componentName,
                        resource.getPath());
                continue;
            }
            final String variantRule = matched.variantRuleFor(props);
            final ComponentMapping redirected = variantRule == null ? null : mappings.find(variantRule);
            final ComponentMapping mapping = (redirected == null ? matched : redirected).effectiveFor(props);
            final Map<String, Object> meta = new LinkedHashMap<>();
            meta.put(SchemaConstants.KEY_ID,
                    AbstractComponentModel.generateId(resource.getPath() + "/" + componentName));
            meta.put(SchemaConstants.KEY_ORDER, sink.size());
            meta.put(SchemaConstants.KEY_COMPONENT, mapping.getComponent());
            final Map<String, Object> entry = new LinkedHashMap<>();
            entry.put(SchemaConstants.KEY_META, meta);
            final Map<String, Object> styles = CustomMobileStyle.asMap(props, mapping.customStyleProperty());
            if (!styles.isEmpty()) {
                entry.put(SchemaConstants.KEY_STYLES, styles);
            }
            entry.put(SchemaConstants.KEY_CONTENT, new LinkedHashMap<>(mapping.apply(props, sectionProps)));
            sink.add(entry);
        }
    }

    /** Every {@code card}-modelled child, regardless of count. */
    private List<Resource> allCardItemsOf(final Resource resource) {
        final List<Resource> items = new ArrayList<>();
        for (final Resource child : resource.getChildren()) {
            if (isSystemNode(child)) {
                continue;
            }
            if (!"card".equals(new GenericComponent(child, urlService).getComponentName())) {
                return null;
            }
            items.add(child);
        }
        return items.isEmpty() ? null : items;
    }

    /**
      * Converts cards with known screen links into a {@code screenList}, skipping unresolved links.
      * Reuses the {@code card} rule's own {@code link} field (which already reduces an
     * authored {@code <a href="/content/.../beginners-luck">} down to the leaf screen key
     * {@code beginners-luck} - see {@link ComponentMapping#apply}) rather than re-implementing href
     * extraction, so this stays in step with however the card rule resolves a link.
     *
      * @return the {@code list} component, or {@code null} when no card resolves to a known screen
     */
    // package-private so CardsAsListTest can drive it directly.
    Map<String, Object> cardsBlockAsList(final Resource resource, final int order,
                                                  final Set<String> knownScreenKeys) {
        return cardsBlockAsList(resource, order, knownScreenKeys, null);
        }

    Map<String, Object> cardsBlockAsList(final Resource resource, final int order,
                              final Set<String> knownScreenKeys,
                              final Map<String, String> screenThumbnailOverrides) {
        final List<Resource> items = allCardItemsOf(resource);
        final ComponentMapping cardRule = items == null ? null : mappings.find("card");
        if (items == null || cardRule == null) {
            return null;
        }
        final List<String> screenKeys = new ArrayList<>(items.size());
        final Map<String, String> thumbnails = new LinkedHashMap<>();
        for (final Resource item : items) {
            final Map<String, Object> authored = new GenericComponent(item, urlService).getContent();
            final Map<String, Object> mapped =
                    cardRule.effectiveFor(authored).apply(authored, Collections.emptyMap());
            final Object link = mapped.get("link");
            final String key = link instanceof String ? (String) link : null;
            if (StringUtils.isBlank(key) || !knownScreenKeys.contains(key)) {
                continue;
            }
            if (screenKeys.contains(key)) {
                // Several cards can open the same screen (Frescopa's Bagged Coffee / Coffee Pods /
                // Bundles all open "coffee"); a list item is a screen, so it is listed once.
                continue;
            }
            screenKeys.add(key);
            final Object image = mapped.get("image");
            if (image instanceof String && StringUtils.isNotBlank((String) image)) {
                thumbnails.put(key, (String) image);
            }
        }
        if (screenKeys.isEmpty()) {
            return null;
        }
        if (screenThumbnailOverrides != null) {
            screenThumbnailOverrides.putAll(thumbnails);
        }

        final Map<String, Object> meta = new LinkedHashMap<>();
        meta.put(SchemaConstants.KEY_ID, AbstractComponentModel.generateId(resource.getPath()));
        meta.put(SchemaConstants.KEY_ORDER, order);
        meta.put(SchemaConstants.KEY_COMPONENT, "list");

        // Field values match the LUMA MOBILE reference's own 3-card screenList (home screen,
        // "OUR BEST BLOG STORIES") - imageWidth/itemHeight/itemRadius/imageResize size and crop
        // each card's image for the list's overlay items; without them
        // the client has nothing to size a screenList overlay item against. itemRadius remains
        // compiled-in. Every other value reads exactly one property off the Cards block - its
        // Mobile Specific field (mobilelayout, mobilesort, mobileItemsInRow, mobileItemRenderer,
        // mobileImageResize; mobileimagewidth/mobileitemheight via listNumber) - then the site's
        // defaults.json screenList entry, then the compiled-in default here. A colour is emitted
        // only when authored on the block or set at site level - there is no compiled-in colour default.
        final Map<String, Object> content = new LinkedHashMap<>();
        content.put("items", screenKeys);
        putAuthoredLimit(resource, content);
        content.put("layout", listValue(resource, "mobilelayout", "layout", "horizontal"));
        content.put("listType", "screenList");
        final Object textColor = listColor(resource, "mobilelisttextcolor", "textColor", null);
        if (textColor != null) {
            content.put("textColor", textColor);
        }
        content.put("imageWidth", listNumber(resource, "mobileimagewidth", "imageWidth", 120));
        content.put("itemHeight", listNumber(resource, "mobileitemheight", "itemHeight", null));
        content.put("itemRadius", null);
        content.put("itemsInRow", listValue(resource, "mobileItemsInRow", "itemsInRow", 1));
        content.put("itemRenderer", knownItemRenderer(
            listValue(resource, "mobileItemRenderer", "itemRenderer", "overlay"), "overlay"));
        content.put("sort", listValue(resource, "mobilesort", "sort", "none"));
        content.put("filterProperty", "Category");
        content.put("imageResize", listValue(resource, "mobileImageResize", null, "cover"));
        content.put("filterOnlyProductScreens", false);
        putHideSubtitle(resource, content);
        putListTextStyles(resource, content);

        final Map<String, Object> styles = new LinkedHashMap<>();
        styles.put("gap", 12);
        final Object sectionColor = listColor(resource, "mobilelistcolor", "color", null);
        if (sectionColor != null) {
            styles.put("color", sectionColor);
        }
        styles.put("marginBottom", "16");
        styles.put("marginHorizontal", "8");

        final Map<String, Object> list = new LinkedHashMap<>();
        list.put(SchemaConstants.KEY_META, meta);
        // The block's own custommobilestyle - authored on the Cards block itself, not on any one
        // card - is the most specific, per-instance choice, same as everywhere else this field is
        // read (see mergeCustomStyles), so it overrides the computed styles above rather than the
        // other way around.
        list.put(SchemaConstants.KEY_STYLES,
                mergeCustomStyles(styles, new GenericComponent(resource, urlService).getContent()));
        list.put(SchemaConstants.KEY_CONTENT, content);
        return list;
    }

    /**
    * Converts a Cards block of product-linking cards into one {@code list} of
     * {@code listType: productList}, the same collapse {@code category-products-lister} makes from a
     * folder walk - {@code items} the products' SKUs.
     *
     * <p>A card carries its product in the {@code ?productId=<sku>} its resolved {@code link} ends
     * with (a product page link, not an app-screen key - those become a {@code screenList} above).
     * The SKU is read back off that link, using the same {@code card} rule the per-card export uses
     * so this stays in step with link resolution. No {@code filter} is emitted: the list is driven
     * purely by these SKUs, since a category or {@code .*} filter would make the client ignore them
     * and return the whole catalog.</p>
     *
    * @return the {@code list} component, or {@code null} when the block has no cards,
     *         the {@code card} rule is not loaded, or any card's link carries no product SKU - in
     *         which case the block is left to its normal per-card walk
     */
    // package-private so CardsAsListTest can drive it directly.
    Map<String, Object> cardsBlockAsProductList(final Resource resource, final int order) {
        final List<Resource> items = allCardItemsOf(resource);
        final ComponentMapping cardRule = items == null ? null : mappings.find("card");
        if (items == null || cardRule == null) {
            return null;
        }
        final List<String> skus = new ArrayList<>(items.size());
        for (final Resource item : items) {
            final Map<String, Object> authored = new GenericComponent(item, urlService).getContent();
            final Map<String, Object> mapped =
                    cardRule.effectiveFor(authored).apply(authored, Collections.emptyMap());
            final String sku = productIdOf(mapped.get("link"));
            if (sku == null) {
                // A card that does not open a product - this is not a product list. Leave the whole
                // block to the normal per-card walk rather than emit a list missing an item.
                return null;
            }
            skus.add(sku);
        }

        final Map<String, Object> meta = new LinkedHashMap<>();
        meta.put(SchemaConstants.KEY_ID, AbstractComponentModel.generateId(resource.getPath()));
        meta.put(SchemaConstants.KEY_ORDER, order);
        meta.put(SchemaConstants.KEY_COMPONENT, "list");

        // Presentation mirrors the screenList conversion (see cardsBlockAsList) - layout, itemsInRow,
        // sort, itemRenderer and the colours are resolvable per block or per site, everything else is
        // the compiled-in shape a productList item needs.
        final Map<String, Object> content = new LinkedHashMap<>();
        content.put("items", skus);
        putAuthoredLimit(resource, content);
        // No filter: the list is driven purely by these explicit SKUs. A category or ".*" filter
        // would make the client return the whole catalog and ignore items.
        content.put("layout", listValue(resource, "mobilelayout", "layout", "horizontal"));
        content.put("listType", "productList");
        final Object textColor = listColor(resource, "mobilelisttextcolor", "textColor", null);
        if (textColor != null) {
            content.put("textColor", textColor);
        }
        content.put("imageWidth", listNumber(resource, "mobileimagewidth", "imageWidth", 120));
        content.put("itemHeight", listNumber(resource, "mobileitemheight", "itemHeight", null));
        content.put("itemsInRow", listValue(resource, "mobileItemsInRow", "itemsInRow", 1));
        content.put("itemRenderer", knownItemRenderer(
            listValue(resource, "mobileItemRenderer", "itemRenderer", "stacked"), "stacked"));
        content.put("sort", listValue(resource, "mobilesort", "sort", "none"));
        content.put("filterProperty", "Category");
        content.put("imageResize", listValue(resource, "mobileImageResize", null, "cover"));
        content.put("filterOnlyProductScreens", false);
        putHideSubtitle(resource, content);
        putListTextStyles(resource, content);

        final Map<String, Object> styles = new LinkedHashMap<>();
        styles.put("gap", 12);
        final Object sectionColor = listColor(resource, "mobilelistcolor", "color", null);
        if (sectionColor != null) {
            styles.put("color", sectionColor);
        }
        styles.put("marginBottom", "16");
        styles.put("marginHorizontal", "8");

        final Map<String, Object> list = new LinkedHashMap<>();
        list.put(SchemaConstants.KEY_META, meta);
        list.put(SchemaConstants.KEY_STYLES,
                mergeCustomStyles(styles, new GenericComponent(resource, urlService).getContent()));
        list.put(SchemaConstants.KEY_CONTENT, content);
        return list;
    }

    /**
     * The {@code productId} query parameter of a resolved card link ({@code product?productId=SKU}),
     * or {@code null} when the link carries none - the link does not open a product.
     */
    private static String productIdOf(final Object link) {
        if (!(link instanceof String)) {
            return null;
        }
        final int query = ((String) link).indexOf('?');
        if (query < 0) {
            return null;
        }
        for (final String pair : ((String) link).substring(query + 1).split("&")) {
            final int eq = pair.indexOf('=');
            if (eq > 0 && "productId".equals(pair.substring(0, eq))) {
                return StringUtils.trimToNull(pair.substring(eq + 1));
            }
        }
        return null;
    }

    /**
    * True when {@code schema} contains preconfigured quiz components instead of typed fields.
    * These blocks are built as forms before empty-wrapper handling can discard their schema.
     */
    private static boolean isQuizSchema(final List<Map<String, Object>> schema) {
        if (schema.isEmpty()) {
            return false;
        }
        for (final Map<String, Object> field : schema) {
            if (field.get(SchemaConstants.KEY_COMPONENT) == null) {
                return false;
            }
        }
        return true;
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
        final Resource fragment = component.getResourceResolver().getResource(stripHost(((String) refValue).trim()));
        if (fragment == null) {
            // A content problem, not a code problem: fires identically on every export of this
            // component until an author fixes the reference, so DEBUG rather than WARN.
            LOG.debug("{} on {} points at '{}', which does not resolve - exporting the component "
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
            // Same reasoning as withContentFragmentRef above: a content problem that repeats every
            // export until fixed, not an exceptional runtime condition - DEBUG, not WARN.
            LOG.debug("{} on {} points at '{}', which does not resolve - exporting the component "
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
        collectFragments(folder, ref, collected, 0);
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
                                  final Map<String, List<String>> sink,
                                  final int depth) {
        for (final Resource child : folder.getChildren()) {
            final Map<String, String> elements = ContentFragmentReader.readElements(child);
            if (elements == null) {
                // Not a Content Fragment. Recurse only into real folders - a dam:Asset's
                // jcr:content/renditions subtree carries hundreds of metadata sub-nodes per file
                // and stalls the walk against a page-load budget without ever finding a fragment.
                if (depth < FRAGMENT_FOLDER_MAX_DEPTH && isFolderContainer(child) && child.hasChildren()) {
                    collectFragments(child, ref, sink, depth + 1);
                }
                continue;
            }
            for (final ComponentMapping.ContentFragmentFolderRef.Collect c : ref.getCollects()) {
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
                   final Resource ancestorSection, final Set<String> knownScreenKeys,
                   final Map<String, String> screenThumbnailOverrides) {
        final List<Map<String, Object>> children = new ArrayList<>();
        collectComponents(resource, children, depth + 1, true, ancestorSection, knownScreenKeys,
            screenThumbnailOverrides);

        emitPanel(resource, sink, resourceType,
                flattensColumns(resource, resourceType) ? flattenPanels(children) : children,
                resource.getPath());
    }

    private void emitPanel(final Resource resource, final List<Map<String, Object>> sink,
                           final String resourceType, final List<Map<String, Object>> children,
                           final String panelPath) {
        if (children.isEmpty()) {
            return;
        }

        if (children.stream().anyMatch(this::isPageLevelComponent)) {
            final List<Map<String, Object>> panelChildren = new ArrayList<>();
            for (final Map<String, Object> child : children) {
                if (isPageLevelComponent(child)) {
                    emitPanel(resource, sink, resourceType, new ArrayList<>(panelChildren),
                            panelPath + "/mobile-panel-" + sink.size());
                    panelChildren.clear();
                    asMeta(child.get(SchemaConstants.KEY_META)).put(SchemaConstants.KEY_ORDER, sink.size());
                    sink.add(child);
                } else {
                    panelChildren.add(child);
                }
            }
            emitPanel(resource, sink, resourceType, panelChildren,
                    panelPath + "/mobile-panel-" + sink.size());
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
        meta.put(SchemaConstants.KEY_ID, AbstractComponentModel.generateId(panelPath));
        meta.put(SchemaConstants.KEY_ORDER, sink.size());
        meta.put(SchemaConstants.KEY_LAYOUT, panelLayout(resource, resourceType));
        meta.put(SchemaConstants.KEY_COMPONENT, SchemaConstants.COMPONENT_PANEL);
        // Reference contract carries showBorder/itemsFillWidth/wrapOverflowing on every panel
        // meta. AEM has no equivalent property, so emit the target's own defaults rather than omit
        // them - itemsFillWidth true is what stretches cards to the panel width.
        final ValueMap panelProps = resource.getValueMap();
        putAuthoredFlag(meta, panelProps, "fullHeight");
        meta.put("showBorder", panelProps.get("showBorder", Boolean.FALSE));
        meta.put("itemsFillWidth", panelProps.get("itemsFillWidth", Boolean.TRUE));
        putAuthoredFlag(meta, panelProps, "itemsFillHeight");
        meta.put("wrapOverflowing", panelProps.get("wrapOverflowing", Boolean.FALSE));

        final boolean vertical = "vertical".equals(meta.get(SchemaConstants.KEY_LAYOUT));
        if (!vertical) {
            clearFullWidthImages(children);
        }

        final Map<String, Object> entry = new LinkedHashMap<>();
        entry.put(SchemaConstants.KEY_META, meta);
        entry.put(SchemaConstants.KEY_STYLES, panelStyles(resource, vertical));
        entry.put(SchemaConstants.KEY_CONTENT,
                isColumnItem(resource) ? groupColumnImages(children, resource) : children);
        sink.add(entry);
    }

    /** Adds a boolean panel meta flag only when the section authored it. */
    private static void putAuthoredFlag(final Map<String, Object> meta, final ValueMap props, final String key) {
        final Object authored = props.get(key);
        if (isSet(authored)) {
            meta.put(key, Boolean.parseBoolean(String.valueOf(authored)));
        }
    }

    private boolean isPageLevelComponent(final Map<String, Object> component) {
        final Object name = asMeta(component.get(SchemaConstants.KEY_META)).get(SchemaConstants.KEY_COMPONENT);
        return "map".equals(name) || "webView".equals(name);
    }

    /**
     * Within a column, two or more images collapse into one leading horizontal sub-panel - each a
     * fixed 120px wide - so they sit side by side whatever order they were authored in; every other
     * element keeps its own full-width row, in authored order, below them.
     */
    private List<Map<String, Object>> groupColumnImages(final List<Map<String, Object>> children,
                                                        final Resource resource) {
        final List<Map<String, Object>> images = new ArrayList<>();
        final List<Map<String, Object>> rest = new ArrayList<>();
        for (final Map<String, Object> child : children) {
            (isImage(child) ? images : rest).add(child);
        }
        if (images.size() < 2) {
            return children;
        }
        for (int i = 0; i < images.size(); i++) {
            asMeta(images.get(i).get(SchemaConstants.KEY_META)).put(SchemaConstants.KEY_ORDER, i);
            columnImageStyles(images.get(i)).put("width", "120");
        }
        final List<Map<String, Object>> ordered = new ArrayList<>(rest.size() + 1);
        ordered.add(imageRow(images, resource));
        ordered.addAll(rest);
        for (int i = 0; i < ordered.size(); i++) {
            asMeta(ordered.get(i).get(SchemaConstants.KEY_META)).put(SchemaConstants.KEY_ORDER, i);
        }
        return ordered;
    }

    private static Map<String, Object> imageRow(final List<Map<String, Object>> images, final Resource resource) {
        final Map<String, Object> meta = new LinkedHashMap<>();
        meta.put(SchemaConstants.KEY_ID, AbstractComponentModel.generateId(resource.getPath() + "/images"));
        meta.put(SchemaConstants.KEY_ORDER, 0);
        meta.put(SchemaConstants.KEY_LAYOUT, "horizontal");
        meta.put(SchemaConstants.KEY_COMPONENT, SchemaConstants.COMPONENT_PANEL);
        meta.put("showBorder", Boolean.FALSE);
        // The images are a fixed width, not stretched, and wrap once the row runs out of room.
        meta.put("itemsFillWidth", Boolean.FALSE);
        meta.put("wrapOverflowing", Boolean.TRUE);
        final Map<String, Object> entry = new LinkedHashMap<>();
        entry.put(SchemaConstants.KEY_META, meta);
        entry.put(SchemaConstants.KEY_STYLES, new LinkedHashMap<>());
        entry.put(SchemaConstants.KEY_CONTENT, images);
        return entry;
    }

    private static boolean isImage(final Map<String, Object> entry) {
        return SchemaConstants.COMPONENT_IMAGE.equals(
                asMeta(entry.get(SchemaConstants.KEY_META)).get(SchemaConstants.KEY_COMPONENT));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> columnImageStyles(final Map<String, Object> entry) {
        final Object styles = entry.get(SchemaConstants.KEY_STYLES);
        if (styles instanceof Map) {
            return (Map<String, Object>) styles;
        }
        final Map<String, Object> created = new LinkedHashMap<>();
        entry.put(SchemaConstants.KEY_STYLES, created);
        return created;
    }

    /**
     * An image declares {@code fullWidth} so a client stretches it across the screen, and
     * image.json defaults it to true because that is right for the usual case - an image alone in
     * a stack. Beside something else in a row it is not: the image takes the whole width and its
     * siblings get none, which is a blank space rather than a layout.
     *
     * <p>Only direct children are touched. An image inside a cell of its own is still alone in
     * that cell, whatever the block around it is doing, so it keeps its full width.</p>
     */
    @SuppressWarnings("unchecked")
    private static void clearFullWidthImages(final List<Map<String, Object>> children) {
        for (final Map<String, Object> child : children) {
            if (!SchemaConstants.COMPONENT_IMAGE.equals(
                    asMeta(child.get(SchemaConstants.KEY_META)).get(SchemaConstants.KEY_COMPONENT))) {
                continue;
            }
            final Object content = child.get(SchemaConstants.KEY_CONTENT);
            if (content instanceof Map) {
                ((Map<String, Object>) content).put("fullWidth", Boolean.FALSE);
            }
        }
    }

    private static boolean isPanel(final Map<String, Object> entry) {
        return SchemaConstants.COMPONENT_PANEL.equals(asMeta(entry.get(SchemaConstants.KEY_META))
                .get(SchemaConstants.KEY_COMPONENT));
    }

    /**
     * Replaces every nested panel with the components inside it, however deep, and renumbers what
     * is left. The children are collected normally first, so each one keeps the fields, styles and
     * component name its own mapping rule gave it - only the panels wrapping them are dropped.
     *
     * <p>Each nested panel numbered its own content from zero, so the surviving components carry
     * colliding {@code order} values until they are stamped again against their new position.</p>
     */
    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> contentOf(final Map<String, Object> panel) {
        final Object content = panel.get(SchemaConstants.KEY_CONTENT);
        return content instanceof List ? (List<Map<String, Object>>) content : Collections.emptyList();
    }

    private static List<Map<String, Object>> flattenPanels(final List<Map<String, Object>> children) {
        // A lone panel wrapping everything is scaffolding, not layout: it is AEM's untyped row
        // wrapper, and its own vertical layout is exactly what was overriding the block's. Dissolve
        // it however much it holds, then decide cell by cell.
        if (children.size() == 1 && isPanel(children.get(0))) {
            return flattenPanels(contentOf(children.get(0)));
        }

        final List<Map<String, Object>> flat = new ArrayList<>();
        for (final Map<String, Object> child : children) {
            if (!isPanel(child)) {
                flat.add(child);
                continue;
            }
            final List<Map<String, Object>> inner = flattenPanels(contentOf(child));
            if (inner.size() == 1) {
                // A cell holding one component says nothing that the component does not.
                flat.addAll(inner);
            } else if (!inner.isEmpty()) {
                // Several components in one cell were grouped on purpose - an image beside a title,
                // a subtitle and a button - so the cell keeps the panel that groups them. Dropping
                // it would lay all of them out along the block's own axis and push most off screen.
                child.put(SchemaConstants.KEY_CONTENT, inner);
                flat.add(child);
            }
        }
        for (int order = 0; order < flat.size(); order++) {
            asMeta(flat.get(order).get(SchemaConstants.KEY_META))
                    .put(SchemaConstants.KEY_ORDER, order);
        }
        return flat;
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
        "borderRadius", "backgroundColor", "height", "marginTop", "backgroundImage",
    };

    /**
     * Style keys whose authored source property is not the same name as the style key itself.
     * {@code backgroundImage} reads the section's {@code image} property - the same field an EDS
     * author already uses for a section background image - rather than a property literally named
     * {@code backgroundImage}, which nothing authors.
     */
    private static final Map<String, String> PANEL_STYLE_SOURCE_OVERRIDES = Map.of("backgroundImage", "image");

    /** The panel style contract, as a set - a style token may only contribute one of these keys;
     * screen positioning keys such as {@code left}/{@code top} belong to editor.position, not here. */
    private static final Set<String> PANEL_STYLE_KEY_SET = Set.of(PANEL_STYLE_KEYS);

    /**
     * Spacing the reference contract carries on every content panel. AEM sections rarely author
     * these, and without them the panel manages no spacing - cards touch and the client cannot
     * size them, so images crop. Applied last, after authored values and style tokens, so both
     * still win.
     */
    private static final Map<String, Object> PANEL_SPACING_DEFAULTS = Map.of(
            "gap", 0, "margin", 0, "padding", 0);

    /** Vertical panels stack cards tighter than horizontal rows carry them side by side. */
    private static final Map<String, Object> VERTICAL_PANEL_SPACING_DEFAULTS = Map.of(
            "gap", 8, "margin", 8, "padding", 0);

    private Map<String, Object> panelStyles(final Resource resource, final boolean vertical) {
        final Map<String, Object> styles = new LinkedHashMap<>();
        final ValueMap props = resource.getValueMap();
        for (final String key : PANEL_STYLE_KEYS) {
            final String sourceKey = PANEL_STYLE_SOURCE_OVERRIDES.getOrDefault(key, key);
            final Object value = props.get(sourceKey);
            styles.put(key, value == null ? "" : value);
        }
        // The style resolver adds anything the panel picked up from its section's style tokens
        // (a design-system class mapping); we let those overlay the raw keys so an author's
        // token overrides the fallback but not their explicit property. Only keys the panel style
        // contract owns are taken - a token mapping to left/top targets editor.position, not a panel.
        for (final Map.Entry<String, Object> tokenStyle : styleResolver.stylesOf(resource).entrySet()) {
            if (!PANEL_STYLE_KEY_SET.contains(tokenStyle.getKey())) {
                continue;
            }
            if ("".equals(styles.get(tokenStyle.getKey()))) {
                styles.put(tokenStyle.getKey(), tokenStyle.getValue());
            } else if (!styles.containsKey(tokenStyle.getKey())) {
                styles.put(tokenStyle.getKey(), tokenStyle.getValue());
            }
        }
        // The panel is the sole owner of card spacing, so fill any gap/margin/padding the author
        // and tokens both left blank with the reference contract's values.
        final Map<String, Object> spacingDefaults =
                vertical ? VERTICAL_PANEL_SPACING_DEFAULTS : PANEL_SPACING_DEFAULTS;
        for (final Map.Entry<String, Object> spacing : spacingDefaults.entrySet()) {
            if ("".equals(styles.get(spacing.getKey()))) {
                styles.put(spacing.getKey(), spacing.getValue());
            }
        }
        // Vertical panels stack cards, and the reference contract distributes them with
        // space-between; fill it in when neither the author nor a style token set it.
        if (vertical && "".equals(styles.get("justifyContent"))) {
            styles.put("justifyContent", "space-between");
        }
        // custommobilestyle is the most specific, per-instance choice, so it overrides everything
        // above it - the authored panel properties, style tokens and spacing defaults alike.
        styles.putAll(CustomMobileStyle.asMap(props));
        return styles;
    }

    /** A columns block, by its franklin resource type or by the last segment of a custom one. */
    private static boolean isColumnsBlock(final String resourceType) {
        return SchemaConstants.RT_FRANKLIN_COLUMNS.equals(resourceType)
                || StringUtils.endsWith(resourceType, "/columns");
    }

    /** Columns lay their children out side by side; a section stacks them. */
    private static String layoutOf(final String resourceType) {
        return isColumnsBlock(resourceType) ? "horizontal" : "vertical";
    }

    /**
     * The panel layout an author set on this node, or {@code null}. Only the two values the panel
     * contract knows count: a block may carry a {@code mobilelayout} in its own vocabulary - a
     * cards block's "vertical/grid" - which is meaningful there but is not a panel layout.
     */
    private static String authoredPanelLayout(final Resource resource) {
        final String authored = resource.getValueMap().get("mobilelayout", String.class);
        return "vertical".equals(authored) || "horizontal".equals(authored) ? authored : null;
    }

    /**
     * True when this columns block should emit one flat panel: its cells' components become the
     * panel's own content, with the untyped row wrapper and the per-cell panels left out.
     *
     * <p>Without the flattening an authored layout on a columns block is inert. AEM puts an
     * untyped row wrapper between the block and its cells, and that wrapper emits a panel of its
     * own whose layout comes from its (absent) resource type - vertical. So the block says
     * horizontal, the row below it says vertical, and the cells stack either way.</p>
     *
     * <p>Gated on the author having set the field, so a columns block nobody has touched keeps the
     * nesting it has always had rather than reflowing on a deploy.</p>
     */
    private static boolean flattensColumns(final Resource resource, final String resourceType) {
        return isColumnsBlock(resourceType) && authoredPanelLayout(resource) != null;
    }

    /**
     * A panel's own authored Mobile Specific layout wins, being the most specific choice there is -
     * today that is a columns block or one of its cells. A column's own itemAlignment decides
     * whether its contents stack or sit side by side; every other panel takes its layout from the
     * container's resource type.
     *
     * <p>Only the two panel layout values are honoured: a block carrying a {@code mobilelayout} in
     * a different vocabulary - a cards block's "vertical/grid", say - must not reach a panel. An
     * unauthored container keeps the layout its resource type has always given it, so adding the
     * field does not reflow containers that nobody has touched. The read is deliberately not
     * restricted to the blocks that offer the field: a section carrying the property would be
     * honoured too, which is what keeps already-authored content working if the dropdown is ever
     * offered on sections as well.
     */
    private static String panelLayout(final Resource resource, final String resourceType) {
        final String authored = authoredPanelLayout(resource);
        if (authored != null) {
            return authored;
        }
        if (isColumnItem(resource)) {
            return "horizontal".equals(resource.getValueMap().get("itemAlignment", String.class))
                    ? "horizontal" : "vertical";
        }
        return layoutOf(resourceType);
    }

    /** A columns block's column: the block/item resource type shared by every block, told apart by its model. */
    private static boolean isColumnItem(final Resource resource) {
        return SchemaConstants.RT_FRANKLIN_BLOCK_ITEM.equals(StringUtils.trimToEmpty(resource.getResourceType()))
                && "column".equals(resource.getValueMap().get("model", String.class));
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

    private static boolean isSystemNode(final Resource resource) {
        final String name = resource.getName();
        return name.startsWith("cq:")
                || name.startsWith("jcr:")
                || name.startsWith("rep:")
                || name.startsWith("oak:")
                || "edsMobileApp".equals(name);
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
     * Adapts a resource to its EdsMobileApp model <em>only</em> when a model is registered for that
     * exact {@code sling:resourceType}.
     *
     * <p>A plain {@code adaptTo(EdsMobileAppComponent.class)} is not safe here. When no
     * implementation matches the resource type, Sling falls back to
     * {@code FirstImplementationPicker}, which returns whichever implementation sorts first by class
     * name - so an unmapped resource would be silently exported as a {@code banner}. Resolving
     * through {@link ModelFactory} keys strictly on the resource type instead.</p>
     *
     * @return the model, or {@code null} when this resource is not part of the JSON contract
     */
    private EdsMobileAppComponent adaptStrictly(final Resource resource) {
        try {
            if (!modelFactory.isModelAvailableForResource(resource)) {
                LOG.debug("No EdsMobileApp model registered for {} ({}) - skipping",
                        resource.getPath(), resource.getResourceType());
                return null;
            }
            final Object model = modelFactory.getModelFromResource(resource);
            if (model instanceof EdsMobileAppComponent) {
                return (EdsMobileAppComponent) model;
            }
            LOG.debug("Model for {} ({}) is not a EdsMobileAppComponent - skipping",
                    resource.getPath(), resource.getResourceType());
        } catch (final RuntimeException e) {
            LOG.warn("Could not build a EdsMobileApp model for {} - skipping", resource.getPath(), e);
        } catch (final LinkageError e) {
            // Not a RuntimeException, so it used to escape this method, the walk, and the servlet's
            // own handlers, answering the whole request with an HTML 500. Adapting a component from
            // a bundle this one cannot see raises it, and one such component should cost itself
            // rather than every screen on the site. Deliberately narrow: LinkageError only, so a
            // genuinely fatal Error such as OutOfMemoryError still propagates.
            LOG.error("Could not load the model class for {} ({}) - skipping this component",
                    resource.getPath(), resource.getResourceType(), e);
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

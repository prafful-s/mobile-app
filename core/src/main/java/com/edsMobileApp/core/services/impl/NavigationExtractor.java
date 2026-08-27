package com.edsMobileApp.core.services.impl;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.commons.lang3.StringUtils;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.day.cq.wcm.api.Page;
import com.day.cq.wcm.api.PageManager;

/**
 * Reads the site menu from an authored navigation page rather than by listing child pages.
 *
 * <p>Listing children gives an accidental menu: every page in the tree, in JCR order, including
 * fragments like {@code nav}, {@code footer} and {@code search} that are not screens at all. The
 * navigation page is what an author actually curates, so it is the honest source.</p>
 *
 * <p>The extractor is deliberately structure-agnostic. It does not look for a particular component
 * or a particular node name - it walks whatever the nav page contains, collects every anchor found
 * in any HTML-bearing property, and resolves each one to a page. That is what makes it work on any
 * site whose navigation is an authored link list, regardless of which component holds it.</p>
 */
final class NavigationExtractor {

    private static final Logger LOG = LoggerFactory.getLogger(NavigationExtractor.class);

    /** Anything shorter cannot contain an anchor, so it is not worth handing to the parser. */
    private static final int MIN_HTML_LENGTH = "<a href=x>y</a>".length();

    private final PageManager pageManager;

    NavigationExtractor(final PageManager pageManager) {
        this.pageManager = pageManager;
    }

    /**
     * @param navPage    the authored navigation page
     * @param components the nav page's components, already flattened by the exporter
     * @return the pages the navigation links to, in authoring order, without duplicates
     */
    List<Page> extract(final Page navPage, final List<Map<String, Object>> components) {
        final Set<String> seenPaths = new LinkedHashSet<>();
        final List<Page> pages = new ArrayList<>();
        final List<String> unresolved = new ArrayList<>();

        for (final String href : collectHrefs(components)) {
            final Page page = resolve(href, navPage);
            if (page == null) {
                // External URLs, anchors and broken links are not screens. Debug rather than warn:
                // a nav legitimately contains outbound links on most sites.
                LOG.debug("Nav link '{}' does not resolve to a page - not a screen", href);
                unresolved.add(href);
                continue;
            }
            if (seenPaths.add(page.getPath())) {
                pages.add(page);
            }
        }

        // Every link failing usually means the nav was authored against a different site root, so
        // name the offenders - otherwise the only symptom is a silent fall back to child listing.
        if (pages.isEmpty() && !unresolved.isEmpty()) {
            LOG.warn("No link on nav page {} resolved to a page. First few: {}",
                    navPage.getPath(), unresolved.subList(0, Math.min(3, unresolved.size())));
        }
        return pages;
    }

    /**
     * Collects anchor targets from every HTML-bearing property, in component order and in document
     * order within each component.
     */
    private List<String> collectHrefs(final List<Map<String, Object>> components) {
        final List<String> hrefs = new ArrayList<>();

        for (final Map<String, Object> component : components) {
            final Object content = component.get("content");
            if (!(content instanceof Map)) {
                continue;
            }
            @SuppressWarnings("unchecked")
            final Map<String, Object> payload = (Map<String, Object>) content;

            for (final Object value : payload.values()) {
                if (!(value instanceof String)) {
                    continue;
                }
                final String text = (String) value;
                // Cheap pre-filter only - '<a' rather than '<a ' because authored markup wraps
                // attributes onto the next line. False positives such as '<article' cost nothing:
                // jsoup simply finds no anchors and the loop moves on.
                if (text.length() < MIN_HTML_LENGTH || !StringUtils.containsIgnoreCase(text, "<a")) {
                    continue;
                }
                for (final Element anchor : Jsoup.parseBodyFragment(text).select("a[href]")) {
                    final String href = StringUtils.trimToEmpty(anchor.attr("href"));
                    if (StringUtils.isNotEmpty(href)) {
                        hrefs.add(href);
                    }
                }
            }
        }
        return hrefs;
    }

    /**
     * Maps an authored href onto a page.
     *
     * <p>Handles the forms authors actually produce: {@code /content/site/en/news.html},
     * {@code /content/site/en/news}, and site-relative {@code news.html}. Anything absolute,
     * protocol-relative or fragment-only is treated as external and returns null.</p>
     */
    private Page resolve(final String href, final Page navPage) {
        if (StringUtils.startsWithAny(href, "http://", "https://", "//", "#", "mailto:", "tel:")) {
            return null;
        }
        String path = StringUtils.substringBefore(StringUtils.substringBefore(href, "?"), "#");
        path = StringUtils.removeEnd(path, ".html");
        if (StringUtils.isEmpty(path)) {
            return null;
        }
        final Page siteRoot = navPage.getParent();
        final String rootPath = siteRoot == null ? navPage.getPath() : siteRoot.getPath();

        if (!path.startsWith("/")) {
            path = rootPath + "/" + path;
        }

        final Page direct = pageManager.getPage(path);
        if (direct != null) {
            return direct;
        }

        // The nav may have been authored against a different site root - content copied between
        // sites, or a package built at one path and installed at another - which leaves every href
        // pointing somewhere that does not exist here. Re-base the leaf onto this site's root.
        final String leaf = StringUtils.substringAfterLast(path, "/");
        if (StringUtils.isNotEmpty(leaf) && !path.equals(rootPath + "/" + leaf)) {
            final Page rebased = pageManager.getPage(rootPath + "/" + leaf);
            if (rebased != null) {
                LOG.debug("Nav link '{}' did not resolve; matched '{}' under the current site root",
                        href, rebased.getPath());
                return rebased;
            }
        }
        return null;
    }
}

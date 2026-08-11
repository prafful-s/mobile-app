package com.dept.contentbridge.core.services.impl;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.lang3.StringUtils;

/**
 * Prefixes repository paths with the site's delivery host.
 *
 * <p>Everything under {@code /content} is rewritten, not just {@code /content/dam}: a client
 * consuming this JSON has no AEM to resolve {@code /content/we-healthcare/…/plan-selection} against,
 * so a page link is exactly as unusable as a bare asset path. The one exception is the {@code link}/
 * {@code linkTo} output field, which the caller ({@link ComponentTreeWalker}) excludes before this
 * ever runs - see {@code NEVER_PREFIXED_KEYS} there.</p>
 *
 * <p>Links also hide inside authored rich text, so {@code href} and {@code src} attributes are
 * rewritten in place. Deliberately done with a regex rather than an HTML parser: parsing and
 * re-serialising authored markup reformats it and re-encodes entities, which changes content the
 * author wrote. A targeted substitution leaves every other byte untouched.</p>
 */
final class ContentPaths {

    /** Repository root whose paths need a host to be usable outside AEM. */
    static final String CONTENT_PREFIX = "/content/";

    /** Matches the attributes that carry a link, capturing the repository path. */
    private static final Pattern MARKUP_LINK =
            Pattern.compile("(\\b(?:href|src)\\s*=\\s*[\"'])(/content/[^\"']*)([\"'])");

    private ContentPaths() {
        // static helpers only
    }

    /**
     * @param value     a property value, which may be a path, arbitrary text, or authored HTML
     * @param assetHost the site's delivery host; blank leaves the value untouched
     * @return the value with repository paths made absolute
     */
    static String prefix(final String value, final String assetHost) {
        if (StringUtils.isBlank(assetHost) || StringUtils.isEmpty(value)) {
            return value;
        }
        if (value.startsWith(CONTENT_PREFIX)) {
            return assetHost + value;
        }
        return containsMarkupLink(value) ? prefixMarkupLinks(value, assetHost) : value;
    }

    private static boolean containsMarkupLink(final String value) {
        // Cheap guard so the regex only runs on values that could possibly match.
        return value.indexOf('<') >= 0 && value.contains(CONTENT_PREFIX);
    }

    private static String prefixMarkupLinks(final String value, final String assetHost) {
        final Matcher matcher = MARKUP_LINK.matcher(value);
        final StringBuffer rewritten = new StringBuffer(value.length() + 64);

        while (matcher.find()) {
            final String replacement = matcher.group(1) + assetHost + matcher.group(2) + matcher.group(3);
            matcher.appendReplacement(rewritten, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(rewritten);
        return rewritten.toString();
    }
}

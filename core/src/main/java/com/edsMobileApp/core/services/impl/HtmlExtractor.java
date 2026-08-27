package com.edsMobileApp.core.services.impl;

import java.util.ArrayList;
import java.util.List;

import org.apache.commons.lang3.StringUtils;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Pulls individual fields out of a single authored rich-text blob.
 *
 * <p>This exists because Edge Delivery authoring collapses what the target schema treats as separate
 * fields into one editor. A hero's eyebrow, headline and call to action are all typed into the same
 * {@code text} property:</p>
 *
 * <pre>
 * &lt;p&gt;&lt;strong&gt;Providing affordable&lt;/strong&gt;&lt;/p&gt;
 * &lt;h1&gt;Medicare options for over 20 years&lt;/h1&gt;
 * &lt;p&gt;&lt;a href="/content/…/plan-selection"&gt;Select your plan&lt;/a&gt;&lt;/p&gt;
 * </pre>
 *
 * <p>A client expecting {@code title}, {@code subtitle}, {@code buttonLabel} and {@code linkTo}
 * cannot use that, so the mapping rules name a CSS selector per field and this class runs it.</p>
 *
 * <p>Reading is safe where rewriting would not be: {@link ContentPaths} deliberately avoids a parser
 * because re-serialising authored markup reformats it. Here nothing is written back - the blob is
 * parsed, a value is read out, and the original is left alone. The one exception is
 * {@code as: "html"}, which returns a re-serialised fragment; use it only where inner markup matters
 * more than byte-fidelity.</p>
 */
final class HtmlExtractor {

    private static final Logger LOG = LoggerFactory.getLogger(HtmlExtractor.class);

    /** Read the element's visible text, entities decoded. The usual choice. */
    static final String AS_TEXT = "text";

    /** Read the element's inner markup. Re-serialised by the parser - see the class note. */
    static final String AS_HTML = "html";

    /** Read one attribute, typically {@code href} or {@code src}. */
    static final String AS_ATTRIBUTE = "attr";

    private HtmlExtractor() {
        // static helpers only
    }

    /**
     * @param html      the authored blob; may be plain text or empty
     * @param selector  a CSS selector, e.g. {@code h1}, {@code p:not(:has(a))}, {@code a}
     * @param as        one of {@link #AS_TEXT}, {@link #AS_HTML}, {@link #AS_ATTRIBUTE}
     * @param attribute the attribute to read when {@code as} is {@code attr}
     * @param all       join every match rather than taking the first
     * @return the extracted value, or {@code null} when nothing matched
     */
    static String extract(final String html, final String selector, final String as,
                          final String attribute, final boolean all) {
        if (StringUtils.isBlank(html) || StringUtils.isBlank(selector)) {
            return null;
        }
        try {
            // parseBodyFragment: authored values are fragments, not documents. Parsing them as a
            // document would wrap them in html/head/body and shift the selectors' meaning.
            final Elements matches = Jsoup.parseBodyFragment(html).body().select(selector);
            if (matches.isEmpty()) {
                return null;
            }
            if (!all) {
                return StringUtils.trimToNull(valueOf(matches.first(), as, attribute));
            }
            final List<String> values = new ArrayList<>(matches.size());
            for (final Element match : matches) {
                final String value = StringUtils.trimToNull(valueOf(match, as, attribute));
                if (value != null) {
                    values.add(value);
                }
            }
            return values.isEmpty() ? null : String.join(AS_HTML.equals(as) ? "" : "\n", values);
        } catch (final RuntimeException e) {
            // A selector typo in a mapping rule must cost one field, not the whole response.
            LOG.warn("Selector '{}' could not be applied - leaving the field unset", selector, e);
            return null;
        }
    }

    private static String valueOf(final Element element, final String as, final String attribute) {
        if (element == null) {
            return null;
        }
        if (AS_ATTRIBUTE.equals(as)) {
            return StringUtils.isBlank(attribute) ? null : element.attr(attribute);
        }
        return AS_HTML.equals(as) ? element.html() : element.text();
    }
}

package com.edsMobileApp.core.services.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * A hero's Section Link ({@code link}, "Optional link for entire hero section") becomes the banner's
 * {@code linkTo} when the hero's text carries no link of its own; a link in the text still wins.
 */
class HeroSectionLinkTest {

    private final ComponentMapping rule = ComponentMappings.load().find("hero");

    private Map<String, Object> map(final Object... props) {
        assertNotNull(rule);
        final Map<String, Object> source = new LinkedHashMap<>();
        source.put("image", "/content/dam/x.jpg");
        for (int i = 0; i < props.length; i += 2) {
            source.put(String.valueOf(props[i]), props[i + 1]);
        }
        return rule.apply(source, Map.of());
    }

    @Test
    void theSectionLinkIsTheBannersLinkWhenTheTextHasNone() {
        final Map<String, Object> mapped = map("text", "<h1>Title</h1><p>Body</p>", "link", "medicare-plans");
        assertEquals("medicare-plans", mapped.get("linkTo"));
        assertEquals(false, mapped.get("showButton"));
    }

    @Test
    void aLinkInTheTextWinsOverTheSectionLink() {
        final Map<String, Object> mapped = map("text",
                "<h1>Title</h1><p><a href=\"enrollment\">Join now</a></p>", "link", "medicare-plans");
        assertEquals("enrollment", mapped.get("linkTo"));
        assertEquals("Join now", mapped.get("buttonLabel"));
        assertEquals(true, mapped.get("showButton"));
    }

    @Test
    void aHeroWithNeitherLinkExportsNoLinkTo() {
        assertFalse(map("text", "<h1>Title</h1>").containsKey("linkTo"));
    }

    @Test
    void aTextLinkAloneStillWorksAsBefore() {
        assertEquals("enrollment", map("text", "<h1>T</h1><p><a href=\"enrollment\">Go</a></p>").get("linkTo"));
    }
}

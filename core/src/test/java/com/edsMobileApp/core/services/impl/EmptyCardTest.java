package com.edsMobileApp.core.services.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.apache.sling.api.resource.Resource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.wcm.testing.mock.aem.junit5.AemContext;
import io.wcm.testing.mock.aem.junit5.AemContextExtension;

/**
 * wknd-fly's destinations screen lost its India card because the two untouched placeholder cards
 * beside it made the block look like a three-card group whose links were a live/dead mix - and the
 * guard that exists so a client never sees a mix of live and dead cards then dropped the whole
 * block. A card with nothing authored is not content, so it no longer counts toward that shape.
 */
@ExtendWith(AemContextExtension.class)
class EmptyCardTest {

    private final AemContext context = new AemContext();

    /** Only urlService is needed for isEmptyCard/cardItemsOf; the rest of the walk is not exercised here. */
    private ComponentTreeWalker walker;

    @BeforeEach
    void setUp() {
        walker = new ComponentTreeWalker(null, new UrlServiceImpl(), null, null, null);
    }

    private Resource resource(final String path, final Object... props) {
        return context.create().resource(path, props);
    }

    /** The reported shape: one authored card, two placeholders an author never filled in. */
    private Resource wkndFlyDestinationsBlock() {
        final Resource block = resource("/content/wknd-fly/en/destinations/jcr:content/root/block",
                "filter", "cards");
        resource(block.getPath() + "/item",
                "model", "card",
                "image", "/content/dam/wknd-fly/en/images/india.jpg",
                "text", "<h2>India</h2>\n<p>A land of rich cultural heritage.</p>",
                "link", "/content/wknd-fly/en/destinations/india");
        resource(block.getPath() + "/item_810748578",
                "model", "card", "selectable", "false", "imagestyle", "", "customstyles", "");
        resource(block.getPath() + "/item_1991735907",
                "model", "card", "selectable", "false", "imagestyle", "", "customstyles", "");
        return block;
    }

    @Test
    void aPlaceholderCardIsEmpty() {
        wkndFlyDestinationsBlock();
        assertTrue(walker.isEmptyCard(
                context.resourceResolver().getResource(
                        "/content/wknd-fly/en/destinations/jcr:content/root/block/item_810748578")));
    }

    @Test
    void anAuthoredCardIsNotEmpty() {
        wkndFlyDestinationsBlock();
        assertFalse(walker.isEmptyCard(
                context.resourceResolver().getResource(
                        "/content/wknd-fly/en/destinations/jcr:content/root/block/item")));
    }

    /** Text with no image is content - the card rule's own fallback exports it as htmlText. */
    @Test
    void aCardWithTextButNoImageIsNotEmpty() {
        final Resource card = resource("/content/s/en/p/jcr:content/root/block/words",
                "model", "card", "text", "<p>Just words, no picture.</p>");
        assertFalse(walker.isEmptyCard(card));
    }

    @Test
    void aCardWithAnImageButNoTextIsNotEmpty() {
        final Resource card = resource("/content/s/en/p/jcr:content/root/block/pic",
                "model", "card", "image", "/content/dam/s/a.jpg");
        assertFalse(walker.isEmptyCard(card));
    }

    /**
     * The predicate runs against every child in collectComponents, so it must not claim a component
     * that simply has no image or text - most components do not.
     */
    @Test
    void aNonCardComponentIsNeverTreatedAsAnEmptyCard() {
        assertFalse(walker.isEmptyCard(resource("/content/s/en/p/jcr:content/root/section",
                "model", "section")));
        assertFalse(walker.isEmptyCard(resource("/content/s/en/p/jcr:content/root/btn",
                "model", "button", "label", "Go")));
    }

    /**
     * The fix itself: with the placeholders excluded the block is no longer a three-card group, so
     * the screenList gate does not engage and the drop guard never fires - the authored card is
     * exported normally instead of taking the whole block down with it.
     */
    @Test
    void placeholdersDoNotCountTowardTheThreeCardShape() {
        assertNull(walker.cardItemsOf(wkndFlyDestinationsBlock()),
                "one real card is not a three-card group, so no screenList conversion");
    }

    /** A genuine three-card group must still be recognised - this is what builds the screenLists. */
    @Test
    void aGenuineThreeCardGroupIsStillRecognised() {
        final Resource block = resource("/content/s/en/p/jcr:content/root/trio", "filter", "cards");
        for (final String name : new String[] {"a", "b", "c"}) {
            resource(block.getPath() + "/" + name,
                    "model", "card",
                    "image", "/content/dam/s/" + name + ".jpg",
                    "text", "<h2>" + name + "</h2>",
                    "link", "/content/s/en/p/" + name);
        }
        final List<Resource> items = walker.cardItemsOf(block);
        assertNotNull(items, "three authored cards are still a three-card group");
        assertEquals(3, items.size());
    }
}

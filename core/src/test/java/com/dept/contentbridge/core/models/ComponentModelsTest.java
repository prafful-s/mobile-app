package com.dept.contentbridge.core.models;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Map;

import org.apache.sling.api.resource.Resource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import com.dept.contentbridge.core.ContentBridgeTestContext;
import com.dept.contentbridge.core.schema.ContentBridgeComponent;

import io.wcm.testing.mock.aem.junit5.AemContext;
import io.wcm.testing.mock.aem.junit5.AemContextExtension;

@ExtendWith(AemContextExtension.class)
class ComponentModelsTest {

    private static final String CONTAINER =
            ContentBridgeTestContext.APP_ROOT + "/jcr:content/root/container";

    private final AemContext context = ContentBridgeTestContext.newContext();

    @BeforeEach
    void setUp() {
        ContentBridgeTestContext.setUp(context);
    }

    private ContentBridgeComponent model(final String name) {
        final Resource resource = context.resourceResolver().getResource(CONTAINER + "/" + name);
        assertNotNull(resource, "fixture is missing " + name);
        final ContentBridgeComponent model = resource.adaptTo(ContentBridgeComponent.class);
        assertNotNull(model, name + " did not adapt to ContentBridgeComponent");
        return model;
    }

    @Test
    @DisplayName("banner externalises its image and only emits CTA keys when the button is on")
    void banner() {
        final ContentBridgeComponent banner = model("banner");
        final Map<String, Object> content = banner.getContent();

        assertEquals("banner", banner.getComponentName());
        assertEquals(ContentBridgeTestContext.PUBLISH_DOMAIN
                + "/content/dam/contentbridge/images/hero.jpg", content.get("image"));
        assertEquals("Welcome", content.get("title"));
        assertEquals("titleLarge", content.get("titleVariant"));
        assertEquals("true", content.get("showButton"));
        assertEquals("Learn More", content.get("buttonLabel"));
        assertEquals("true", content.get("isSubtitleRichText"));
    }

    @Test
    @DisplayName("banner without a button omits the CTA keys entirely")
    void bannerWithoutButton() {
        context.build().resource(CONTAINER + "/banner2",
                "sling:resourceType", "contentbridge/components/banner",
                "fileReference", "/content/dam/contentbridge/images/b.png",
                "showButton", false);

        final Map<String, Object> content = model("banner2").getContent();
        assertEquals("false", content.get("showButton"));
        assertFalse(content.containsKey("buttonLabel"));
        assertFalse(content.containsKey("linkTo"));
    }

    @Test
    @DisplayName("title maps jcr:title onto content.text")
    void title() {
        final Map<String, Object> content = model("title").getContent();
        assertEquals("A heading", content.get("text"));
        assertEquals("xlarge", content.get("size"));
        assertEquals("center", content.get("textAlignment"));
    }

    @Test
    @DisplayName("htmlText passes the authored markup through untouched")
    void htmlText() {
        final Map<String, Object> content = model("htmltext").getContent();
        assertEquals("<p>Body copy</p>", content.get("text"));
    }

    @Test
    @DisplayName("card falls back to sensible style defaults")
    void card() {
        final Map<String, Object> content = model("card").getContent();
        assertEquals("Card title", content.get("title"));
        assertEquals("titleMedium", content.get("titleVariant"));
        assertEquals("none", content.get("textTransform"));
        assertEquals("stacked", content.get("cardType"));
        assertEquals("/content/contentbridge/us/en/subpage-1", content.get("link"));
    }

    @Test
    @DisplayName("divider emits an empty content object rather than null")
    void divider() {
        final ContentBridgeComponent divider = model("divider");
        assertEquals("divider", divider.getComponentName());
        assertTrue(divider.getContent().isEmpty());
    }

    @Test
    @DisplayName("blank properties never reach the payload")
    void blankPropertiesAreDropped() {
        final Map<String, Object> content = model("image").getContent();
        assertFalse(content.containsKey("alt"), "alt was not authored and must be omitted");
    }

    @Test
    @DisplayName("ids are stable across repeated adaptations and unique per component")
    void idsAreStableAndUnique() {
        assertEquals(model("banner").getId(), model("banner").getId());

        final java.util.List<String> ids = new ArrayList<>();
        for (final String name : new String[] {"banner", "title", "htmltext", "card", "divider", "image"}) {
            ids.add(model(name).getId());
        }
        assertEquals(ids.size(), new java.util.HashSet<>(ids).size(), "ids collided: " + ids);
    }
}

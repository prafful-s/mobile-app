package com.edsMobileApp.core.services.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import org.apache.sling.models.factory.ModelFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import com.day.cq.wcm.api.Page;
import com.edsMobileApp.core.models.impl.GenericComponent;
import com.edsMobileApp.core.schema.SchemaConstants;

import io.wcm.testing.mock.aem.junit5.AemContext;
import io.wcm.testing.mock.aem.junit5.AemContextExtension;

/**
 * A hero laid out image-left or image-right is handled by the card rule, which reads the card type
 * from the authored {@code mobilecardstyle} (the hero's Mobile Specific tab offers it, defaulting to
 * {@code stacked}); with none authored it falls back to the card rule's own {@code overlay}.
 */
@ExtendWith(AemContextExtension.class)
class HeroCardVariantTest {

    private final AemContext context = new AemContext();

    private Map<String, Object> exported(final Object... heroProps) {
        final Page page = context.create().page("/content/s/en/p");
        context.create().resource(page.getPath() + "/jcr:content/root");
        final Object[] props = new Object[heroProps.length + 6];
        props[0] = "sling:resourceType";
        props[1] = SchemaConstants.RT_FRANKLIN_BLOCK;
        props[2] = "model";
        props[3] = "hero";
        props[4] = "image";
        props[5] = "/content/dam/x.jpg";
        System.arraycopy(heroProps, 0, props, 6, heroProps.length);
        context.create().resource(page.getPath() + "/jcr:content/root/hero", props);

        final UrlServiceImpl urlService = new UrlServiceImpl();
        final ModelFactory modelFactory = mock(ModelFactory.class);
        when(modelFactory.isModelAvailableForResource(any())).thenReturn(true);
        when(modelFactory.getModelFromResource(any()))
                .thenAnswer(invocation -> new GenericComponent(invocation.getArgument(0), urlService));
        final FormSchemaServiceImpl schemas = new FormSchemaServiceImpl();
        schemas.activate();
        final ComponentTreeWalker walker = new ComponentTreeWalker(modelFactory, urlService, schemas,
                new StyleResolver(new String[0]), ComponentMappings.load());
        final List<Map<String, Object>> components =
                walker.componentsOf(page, "https://assets.example.com", true, false);
        return firstNonPanel(components);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> firstNonPanel(final List<Map<String, Object>> tree) {
        for (final Map<String, Object> component : tree) {
            final Map<String, Object> meta = (Map<String, Object>) component.get(SchemaConstants.KEY_META);
            if (!"panel".equals(meta.get(SchemaConstants.KEY_COMPONENT))) {
                return component;
            }
            final Object inner = component.get(SchemaConstants.KEY_CONTENT);
            if (inner instanceof List) {
                final Map<String, Object> found = firstNonPanel((List<Map<String, Object>>) inner);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static String componentOf(final Map<String, Object> entry) {
        return (String) ((Map<String, Object>) entry.get(SchemaConstants.KEY_META)).get(SchemaConstants.KEY_COMPONENT);
    }

    @SuppressWarnings("unchecked")
    private static Object cardTypeOf(final Map<String, Object> entry) {
        return ((Map<String, Object>) entry.get(SchemaConstants.KEY_CONTENT)).get("cardType");
    }

    @Test
    void anImageLeftHeroIsACard() {
        final Map<String, Object> entry = exported("text", "<h2>T</h2><p>B</p>", "herolayout", "image-left");
        assertEquals("card", componentOf(entry));
    }

    @Test
    void anImageRightHeroIsACard() {
        final Map<String, Object> entry = exported("text", "<h2>T</h2><p>B</p>", "herolayout", "image-right");
        assertEquals("card", componentOf(entry));
    }

    @Test
    void theCardTypeComesFromTheAuthoredCardStyle() {
        for (final String[] pair : new String[][] {
                {"stacked", "stacked"}, {"overlay", "overlay"}, {"image-text-one-line", "image-text-one-line"}}) {
            final Map<String, Object> entry = exported("text", "<h2>T</h2><p>B</p>", "herolayout", "image-left",
                    "mobilecardstyle", pair[0]);
            assertEquals(pair[1], cardTypeOf(entry), pair[0]);
        }
    }

    @Test
    void aHeroWithNoCardStyleAuthoredUsesTheCardRulesOwnDefault() {
        final Map<String, Object> entry = exported("text", "<h2>T</h2><p>B</p>", "herolayout", "image-right");
        assertEquals("overlay", cardTypeOf(entry));
    }

    @Test
    void otherHeroLayoutsStayBanners() {
        final Map<String, Object> entry = exported("text", "<h1>T</h1><p>B</p>", "herolayout", "overlay");
        assertEquals("banner", componentOf(entry));
    }
}

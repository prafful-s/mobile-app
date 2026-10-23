package com.edsMobileApp.core.services.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.apache.sling.models.factory.ModelFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import com.day.cq.wcm.api.Page;
import com.edsMobileApp.core.models.impl.GenericComponent;
import com.edsMobileApp.core.schema.SchemaConstants;

import io.wcm.testing.mock.aem.junit5.AemContext;
import io.wcm.testing.mock.aem.junit5.AemContextExtension;

/**
 * A card with its Show Button switch on gets a button (label and link from the link in its text, variant
 * from CTA Style or the Button Variant choice); every other card - every card an existing site has -
 * exports exactly what it always did.
 */
@ExtendWith(AemContextExtension.class)
class CardWithButtonMappingTest {

    private final AemContext context = new AemContext();

    @SuppressWarnings("unchecked")
    private Map<String, Object> exportForm(final String model, final Object... props) {
        final Page page = context.create().page("/content/s/en/p");
        context.create().resource(page.getPath() + "/jcr:content/root");
        final List<Object> all = new ArrayList<>(List.of("sling:resourceType",
                SchemaConstants.RT_FRANKLIN_BLOCK, "model", model));
        all.addAll(List.of(props));
        context.create().resource(page.getPath() + "/jcr:content/root/form", all.toArray());

        final UrlServiceImpl urlService = new UrlServiceImpl();
        final ModelFactory modelFactory = mock(ModelFactory.class);
        when(modelFactory.isModelAvailableForResource(any())).thenReturn(true);
        when(modelFactory.getModelFromResource(any()))
                .thenAnswer(invocation -> new GenericComponent(invocation.getArgument(0), urlService));
        final FormSchemaServiceImpl schemas = new FormSchemaServiceImpl();
        schemas.activate();
        final ComponentTreeWalker walker = new ComponentTreeWalker(modelFactory, urlService, schemas,
                new StyleResolver(new String[0]), ComponentMappings.load());
        final List<Map<String, Object>> tree =
                walker.componentsOf(page, "https://assets.example.com", true, false);
        return tree.stream()
                .filter(c -> "form".equals(((Map<String, Object>) c.get(SchemaConstants.KEY_META))
                        .get(SchemaConstants.KEY_COMPONENT)))
                .findFirst().orElseThrow(() -> new AssertionError("no form in " + tree));
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> fields(final Map<String, Object> form) {
        return (List<Map<String, Object>>) form.get(SchemaConstants.KEY_CONTENT);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> meta(final Map<String, Object> component) {
        return (Map<String, Object>) component.get(SchemaConstants.KEY_META);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> content(final Map<String, Object> component) {
        return (Map<String, Object>) component.get(SchemaConstants.KEY_CONTENT);
    }

    private final ComponentMappings mappings = ComponentMappings.load();

    private static final String TEXT = "<h2>Plans</h2><p>Pick one.</p><p><a href=\"medicare-plans\">See plans</a></p>";

    private Map<String, Object> source(final Object... props) {
        final Map<String, Object> source = new java.util.LinkedHashMap<>();
        source.put("image", "/content/dam/x.jpg");
        source.put("text", TEXT);
        for (int i = 0; i < props.length; i += 2) {
            source.put(String.valueOf(props[i]), props[i + 1]);
        }
        return source;
    }

    @Test
    void onlyACardWithTheSwitchOnIsRedirectedToTheButtonRule() {
        final ComponentMapping card = mappings.find("card");
        assertEquals("card-with-button", card.variantRuleFor(source("mobileshowbutton", "true")));
        assertEquals("card-with-button", card.variantRuleFor(source("mobileshowbutton", true)));
        assertNull(card.variantRuleFor(source("mobileshowbutton", "false")));
        assertNull(card.variantRuleFor(source("mobileshowbutton", false)));
        assertNull(card.variantRuleFor(source()));
    }

    @Test
    void theButtonRuleIsTheCardRuleWithOnlyTheButtonFieldsAdded() {
        final ComponentMapping card = mappings.find("card");
        final ComponentMapping withButton = mappings.find("card-with-button");
        assertNotNull(withButton);
        final java.util.Set<String> buttonKeys = java.util.Set.of("showButton", "buttonLabel", "buttonVariant");
        final List<Map<String, Object>> samples = List.of(
                source(), source("mobilecardstyle", "stacked"), source("mobileimagestyle", "contain"),
                source("link", "enrollment", "textcolor", "#ffffff"), source("ctastyle", "cta-link"),
                source("mobilecardstyle", "one-line", "mobileimagestyle", "contain", "ctastyle", "cta-button"));
        for (final Map<String, Object> sample : samples) {
            final Map<String, Object> plain = card.apply(sample, Map.of());
            final Map<String, Object> button = new java.util.LinkedHashMap<>(withButton.apply(sample, Map.of()));
            buttonKeys.forEach(button::remove);
            assertEquals(plain, button, "same card fields for " + sample);
        }
    }

    @Test
    void theButtonTakesItsLabelAndLinkFromTheLinkInTheCardText() {
        final Map<String, Object> mapped = mappings.find("card-with-button")
                .apply(source("mobileshowbutton", "true"), Map.of());
        assertEquals(true, mapped.get("showButton"));
        assertEquals("See plans", mapped.get("buttonLabel"));
        assertEquals("medicare-plans", mapped.get("link"));
        assertEquals("contained", mapped.get("buttonVariant"));
    }

    @Test
    void theVariantFollowsCtaStyleUnlessAButtonVariantIsChosen() {
        final ComponentMapping withButton = mappings.find("card-with-button");
        assertEquals("outlined", withButton.apply(source("ctastyle", "cta-button-secondary"), Map.of())
                .get("buttonVariant"));
        assertEquals("text", withButton.apply(source("ctastyle", "cta-link"), Map.of()).get("buttonVariant"));
        assertEquals("elevated", withButton.apply(source("ctastyle", "cta-button", "mobilebuttonvariant", "elevated"),
                Map.of()).get("buttonVariant"));
    }

    private List<Map<String, Object>> exportCard(final Object... props) {
        final Page page = context.create().page("/content/s/en/p" + java.util.UUID.randomUUID().toString().substring(0, 6));
        context.create().resource(page.getPath() + "/jcr:content/root");
        final List<Object> all = new ArrayList<>(List.of("sling:resourceType", SchemaConstants.RT_FRANKLIN_BLOCK,
                "model", "card", "image", "/content/dam/x.jpg", "text", TEXT));
        all.addAll(List.of(props));
        context.create().resource(page.getPath() + "/jcr:content/root/card", all.toArray());
        final UrlServiceImpl urlService = new UrlServiceImpl();
        final ModelFactory modelFactory = mock(ModelFactory.class);
        when(modelFactory.isModelAvailableForResource(any())).thenReturn(true);
        when(modelFactory.getModelFromResource(any()))
                .thenAnswer(invocation -> new GenericComponent(invocation.getArgument(0), urlService));
        final FormSchemaServiceImpl schemas = new FormSchemaServiceImpl();
        schemas.activate();
        final ComponentTreeWalker walker = new ComponentTreeWalker(modelFactory, urlService, schemas,
                new StyleResolver(new String[0]), ComponentMappings.load());
        return walker.componentsOf(page, "https://assets.example.com", true, false);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> firstCard(final List<Map<String, Object>> tree) {
        for (final Map<String, Object> component : tree) {
            if ("card".equals(meta(component).get("component"))) {
                return content(component);
            }
            if (component.get(SchemaConstants.KEY_CONTENT) instanceof List) {
                final Map<String, Object> inner = firstCard((List<Map<String, Object>>) component.get(SchemaConstants.KEY_CONTENT));
                if (inner != null) {
                    return inner;
                }
            }
        }
        return null;
    }

    @Test
    void anExportedCardGetsAButtonOnlyWhenTheSwitchIsOn() {
        final Map<String, Object> off = firstCard(exportCard());
        assertNotNull(off);
        assertFalse(off.containsKey("showButton"));
        assertFalse(off.containsKey("buttonLabel"));
        assertFalse(off.containsKey("buttonVariant"));
        assertFalse(firstCard(exportCard("mobileshowbutton", false)).containsKey("showButton"));

        final Map<String, Object> on = firstCard(exportCard("mobileshowbutton", true, "mobilebuttonvariant", "elevated"));
        assertEquals(true, on.get("showButton"));
        assertEquals("See plans", on.get("buttonLabel"));
        assertEquals("elevated", on.get("buttonVariant"));
    }
}

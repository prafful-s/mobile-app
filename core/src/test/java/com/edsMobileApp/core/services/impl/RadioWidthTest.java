package com.edsMobileApp.core.services.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
 * A {@code radio-width} custom mobile style row sizes every radio group of a form, the way
 * {@code checkbox-width} sizes every checkbox group, and neither touches the other.
 */
@ExtendWith(AemContextExtension.class)
class RadioWidthTest {

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

    @SuppressWarnings("unchecked")
    private static Map<String, Object> stylesOf(final Map<String, Object> form, final String id) {
        return (Map<String, Object>) fields(form).stream().filter(f -> id.equals(meta(f).get("id")))
                .findFirst().orElseThrow(() -> new AssertionError("no " + id)).get(SchemaConstants.KEY_STYLES);
    }

    @Test
    void aRadioWidthRowSizesEveryRadioGroup() {
        final Map<String, Object> form = exportForm("checkout-shipping",
                "custommobilestyle", new String[] {"radio-width:280"});
        assertEquals(280, stylesOf(form, "paymentMethod").get("width"));
        assertEquals(280, stylesOf(form, "shippingMethod").get("width"));
    }

    @Test
    void aCheckboxWidthRowLeavesTheRadiosAlone() {
        final Map<String, Object> form = exportForm("checkout-shipping",
                "custommobilestyle", new String[] {"checkbox-width:280"});
        assertNull(stylesOf(form, "paymentMethod"));
    }

    @Test
    void withNoRowTheRadiosKeepNoStyles() {
        final Map<String, Object> form = exportForm("plan-application");
        assertNull(stylesOf(form, "jointApplicant"));
    }
}

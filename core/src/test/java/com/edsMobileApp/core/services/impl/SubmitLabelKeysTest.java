package com.edsMobileApp.core.services.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
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
 * A form's submit button text is read from whichever field its block authors it in, so the forms that
 * name it {@code submitbuttontext}, {@code submit-label} or {@code createaccountbuttontext} show it in
 * the app too, without renaming the field in the block.
 */
@ExtendWith(AemContextExtension.class)
class SubmitLabelKeysTest {

    private final AemContext context = new AemContext();

    @SuppressWarnings("unchecked")
    private String submitLabelOf(final String model, final String key, final String value) {
        final Page page = context.create().page("/content/s/en/" + model);
        context.create().resource(page.getPath() + "/jcr:content/root");
        final List<Object> props = new ArrayList<>(List.of("sling:resourceType",
                SchemaConstants.RT_FRANKLIN_BLOCK, "model", model));
        if (key != null) {
            props.addAll(List.of(key, value));
        }
        context.create().resource(page.getPath() + "/jcr:content/root/form", props.toArray());

        final UrlServiceImpl urlService = new UrlServiceImpl();
        final ModelFactory modelFactory = mock(ModelFactory.class);
        when(modelFactory.isModelAvailableForResource(any())).thenReturn(true);
        when(modelFactory.getModelFromResource(any()))
                .thenAnswer(invocation -> new GenericComponent(invocation.getArgument(0), urlService));
        final FormSchemaServiceImpl schemas = new FormSchemaServiceImpl();
        schemas.activate();
        final ComponentTreeWalker walker = new ComponentTreeWalker(modelFactory, urlService, schemas,
                new StyleResolver(new String[0]), ComponentMappings.load());
        final Map<String, Object> form = walker.componentsOf(page, "https://assets.example.com", true, false)
                .stream()
                .filter(c -> "form".equals(((Map<String, Object>) c.get(SchemaConstants.KEY_META))
                        .get(SchemaConstants.KEY_COMPONENT)))
                .findFirst().orElseThrow(() -> new AssertionError("no form for " + model));
        return (String) ((Map<String, Object>) form.get(SchemaConstants.KEY_META)).get("submitButtonLabel");
    }

    @Test
    void theOtherSubmitLabelFieldsAreRead() {
        assertEquals("Join", submitLabelOf("create-account", "createaccountbuttontext", "Join"));
        for (final String form : new String[] {"insurance-plan-selection", "medicare-plan-selection",
            "plan-application"}) {
            assertEquals("Find plans", submitLabelOf(form, "submitbuttontext", "Find plans"), form);
        }
        for (final String form : new String[] {"test-drive", "schedule-appointment", "schedule-consultation"}) {
            assertEquals("Book it", submitLabelOf(form, "submit-label", "Book it"), form);
        }
    }

    @Test
    void theLabelsTheAppAlreadyReadStillWin() {
        assertEquals("Pay now", submitLabelOf("get-offer", "button-label", "Pay now"));
        assertEquals("Join", submitLabelOf("join-us", "buttontext", "Join"));
    }

    @Test
    void withNoLabelAuthoredTheFormKeepsItsDefault() {
        assertEquals("Submit", submitLabelOf("plan-application", null, null));
    }
}

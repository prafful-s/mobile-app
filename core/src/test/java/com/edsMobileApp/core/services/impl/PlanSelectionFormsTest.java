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
 * The insurance and medicare plan selection journeys: each step's title, subtitle and subheading are
 * their own headings, in the order the EDS wizard shows them, with the question's options under them.
 */
@ExtendWith(AemContextExtension.class)
class PlanSelectionFormsTest {

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

    private static List<String> ids(final Map<String, Object> form) {
        return fields(form).stream().map(f -> String.valueOf(meta(f).get("id"))).collect(Collectors.toList());
    }

    private static List<Map<String, Object>> byComponent(final Map<String, Object> form, final String name) {
        return fields(form).stream().filter(f -> name.equals(meta(f).get("component")))
                .collect(Collectors.toList());
    }

    @SuppressWarnings("unchecked")
    private static int optionCount(final Map<String, Object> form) {
        return byComponent(form, "checkboxGroup").stream()
                .mapToInt(g -> ((List<Object>) content(g).get("options")).size()).sum();
    }

    private static List<String> ids(final Map<String, Object> form, final String component) {
        return fields(form).stream().filter(f -> component.equals(meta(f).get("component")))
                .map(f -> String.valueOf(meta(f).get("id"))).collect(Collectors.toList());
    }

    private static Map<String, Object> field(final Map<String, Object> form, final String id) {
        return fields(form).stream().filter(f -> id.equals(meta(f).get("id"))).findFirst()
                .orElseThrow(() -> new AssertionError("no " + id));
    }

    @Test
    void theInsuranceSubtitleSitsUnderItsTitleAndAboveItsOptions() {
        final Map<String, Object> form = exportForm("insurance-plan-selection");
        assertEquals(List.of("step-1-title", "coverageFor", "step-2-title", "step-2-sub", "careFrequency",
                "step-3-title", "costPreference"), ids(form));
        assertEquals("How often do you get care?", content(field(form, "step-2-title")).get("text"));
        assertNull(content(field(form, "careFrequency")).get("label"));
    }

    @Test
    void theMedicareStepsKeepTheEdsHeadingOrder() {
        final Map<String, Object> form = exportForm("medicare-plan-selection");
        assertEquals(List.of("step-1-title", "step-1-sub", "zipCode", "step-2-title",
                "monthlyPaymentPreference", "step-3-title-main", "step-3-sub-main", "step-3-title-sub1",
                "preferredBenefits", "step-3-title-sub2", "additionalBenefits", "step-4-title-main",
                "step-4-sub-main", "step-4-title-sub", "networkPreference"), ids(form));
        assertEquals("Which type of benefits do you want in your plan?",
                content(field(form, "step-3-title-sub1")).get("text"));
        assertNull(content(field(form, "preferredBenefits")).get("label"));
    }

    @Test
    void titlesSubtitlesAndSubheadingsAreStyledApart() {
        final Map<String, Object> form = exportForm("medicare-plan-selection");
        assertEquals(Map.of("size", "large", "weight", "bold", "textAlignment", "center"),
                headingStyle(field(form, "step-3-title-main")));
        assertEquals(Map.of("size", "small", "weight", "regular", "textAlignment", "center"),
                headingStyle(field(form, "step-3-sub-main")));
        assertEquals(Map.of("size", "medium", "weight", "bold", "textAlignment", "center"),
                headingStyle(field(form, "step-3-title-sub2")));
    }

    @Test
    void theJointApplicantQuestionIsAHeadingBeforeItsRadio() {
        final Map<String, Object> form = exportForm("plan-application");
        final List<String> all = fields(form).stream().map(f -> String.valueOf(meta(f).get("id")))
                .collect(Collectors.toList());
        assertEquals(all.indexOf("joint-applicant-title") + 1, all.indexOf("jointApplicant"));
        assertEquals("Is there a joint applicant?", content(field(form, "joint-applicant-title")).get("text"));
        assertNull(content(field(form, "jointApplicant")).get("label"));
    }

    private static Map<String, Object> headingStyle(final Map<String, Object> heading) {
        final Map<String, Object> c = content(heading);
        return Map.of("size", c.get("size"), "weight", c.get("weight"), "textAlignment", c.get("textAlignment"));
    }
}

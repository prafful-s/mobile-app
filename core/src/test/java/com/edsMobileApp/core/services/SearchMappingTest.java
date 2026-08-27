package com.edsMobileApp.core.services;

import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.List;
import java.util.Map;

import org.apache.sling.testing.mock.sling.ResourceResolverType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import com.edsMobileApp.core.services.impl.FormSchemaServiceImpl;
import com.edsMobileApp.core.services.impl.PageJsonExporterImpl;
import com.edsMobileApp.core.services.impl.UrlServiceImpl;
import com.day.cq.wcm.api.Page;

import io.wcm.testing.mock.aem.junit5.AemContext;
import io.wcm.testing.mock.aem.junit5.AemContextExtension;

/**
 * The {@code search} block projects onto a single nested {@code rightIcon} object rather than a
 * flat property bag - the reference client renders a header icon that carries its own icon file,
 * accessibility name, and click target. Three authored properties collapse into one keyed
 * sub-object via the mapping rule's dot-notation targets.
 */
@ExtendWith(AemContextExtension.class)
class SearchMappingTest {

    private static final String SITE = "/content/binji";
    private static final String HOME = SITE + "/language-masters/en";

    private final AemContext context = new AemContext(ResourceResolverType.JCR_MOCK);

    private PageJsonExporter exporter;

    @BeforeEach
    void setUp() {
        context.create().page(HOME, "", Map.of("jcr:title", "Home"));
        context.addModelsForPackage("com.edsMobileApp.core.models.impl");
        context.registerInjectActivateService(new UrlServiceImpl());
        context.registerInjectActivateService(new FormSchemaServiceImpl());
        context.registerInjectActivateService(new PageJsonExporterImpl(),
                "allowedRoots", new String[] {"/content"}, "cacheMaxAge", 0);

        exporter = context.getService(PageJsonExporter.class);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> map(final Object value) {
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> list(final Object value) {
        return (List<Map<String, Object>>) value;
    }

    private java.util.List<java.util.Map<String, Object>> exportContent(final Object... blockProperties) {
        final Object[] all = new Object[blockProperties.length + 2];
        all[0] = "sling:resourceType";
        all[1] = "core/franklin/components/block/v1/block";
        System.arraycopy(blockProperties, 0, all, 2, blockProperties.length);
        context.build().resource(HOME + "/jcr:content/root/block", all);

        final Page home = context.pageManager().getPage(HOME);
        final Map<String, Object> app = exporter.export(home, ExportOptions.of(false));
        final Map<String, Object> screens = map(app.get("screens"));
        return list(map(screens.get("en")).get("content"));
    }

    @Test
    @DisplayName("a search block is filtered out of screens.content - it feeds meta.rightIcon "
            + "from the nav page and is redundant elsewhere")
    void searchIsExcludedFromScreens() {
        final java.util.List<java.util.Map<String, Object>> content = exportContent(
                "model", "search",
                "searchstyle", "search-icon",
                "searchPath", "/content/foo");

        for (final Map<String, Object> component : content) {
            assertFalse("search".equals(map(component.get("meta")).get("component")),
                    "search must not appear in screens.content: " + content);
        }
    }
}

package com.dept.contentbridge.core.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.apache.sling.testing.mock.sling.ResourceResolverType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import com.dept.contentbridge.core.services.impl.FormSchemaServiceImpl;
import com.dept.contentbridge.core.services.impl.PageJsonExporterImpl;
import com.dept.contentbridge.core.services.impl.UrlServiceImpl;
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
        context.addModelsForPackage("com.dept.contentbridge.core.models.impl");
        context.registerInjectActivateService(new UrlServiceImpl(), "publishDomain", "");
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

    private Map<String, Object> exportSearchBlock(final Object... blockProperties) {
        final Object[] all = new Object[blockProperties.length + 2];
        all[0] = "sling:resourceType";
        all[1] = "core/franklin/components/block/v1/block";
        System.arraycopy(blockProperties, 0, all, 2, blockProperties.length);
        context.build().resource(HOME + "/jcr:content/root/block", all);

        final Page home = context.pageManager().getPage(HOME);
        final Map<String, Object> app = exporter.export(home, ExportOptions.of(ExportOptions.SCHEMA_V2, null));
        final Map<String, Object> screens = map(app.get("screens"));
        return list(map(screens.get("en")).get("content")).get(0);
    }

    @Test
    @DisplayName("a search block projects onto a nested rightIcon object with icon/name/target")
    void searchBecomesRightIcon() {
        final Map<String, Object> exported = exportSearchBlock(
                "model", "search",
                "searchstyle", "search-icon",
                "searchPath", "/content/binji/language-masters/en/search");

        assertEquals("search", map(exported.get("meta")).get("component"));

        final Map<String, Object> content = map(exported.get("content"));
        assertTrue(content.containsKey("rightIcon"),
                "flat search-* properties collapse into one rightIcon sub-object");
        final Map<String, Object> rightIcon = map(content.get("rightIcon"));

        assertEquals("search-icon", rightIcon.get("icon"), "icon comes from searchstyle");
        assertEquals("Search", rightIcon.get("name"),
                "name is a fixed accessibility label for every search block");
        assertEquals("/content/binji/language-masters/en/search", rightIcon.get("target"),
                "target comes from searchPath and stays site-relative");
    }

    @Test
    @DisplayName("skeleton mode fills rightIcon.icon and rightIcon.target even when unauthored, "
            + "and keeps the fixed name label")
    void unauthoredSearchFillsSkeletonDefaults() {
        final Map<String, Object> exported = exportSearchBlock("model", "search");

        final Map<String, Object> rightIcon = map(map(exported.get("content")).get("rightIcon"));
        assertEquals(Map.of("icon", "", "name", "Search", "target", ""), rightIcon,
                "the client always sees the same rightIcon shape - const name, empty icon+target");
    }

    @Test
    @DisplayName("source properties consumed by rightIcon do not leak into extras")
    void consumedPropsDoNotLeak() {
        final Map<String, Object> exported = exportSearchBlock(
                "model", "search",
                "searchstyle", "search-icon",
                "searchPath", "/content/foo");

        final Map<String, Object> content = map(exported.get("content"));
        assertFalse(content.containsKey("searchstyle"),
                "searchstyle was consumed into rightIcon.icon");
        assertFalse(content.containsKey("searchPath"),
                "searchPath was consumed into rightIcon.target");
    }
}

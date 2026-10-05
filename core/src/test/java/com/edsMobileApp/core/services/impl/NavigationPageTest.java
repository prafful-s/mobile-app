package com.edsMobileApp.core.services.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import com.day.cq.wcm.api.Page;

import io.wcm.testing.mock.aem.junit5.AemContext;
import io.wcm.testing.mock.aem.junit5.AemContextExtension;

@ExtendWith(AemContextExtension.class)
class NavigationPageTest {

    private final AemContext context = new AemContext();

    private Page resolveNavPage(final Page root) throws Exception {
        final PageJsonExporterImpl exporter = new PageJsonExporterImpl();
        final Field names = PageJsonExporterImpl.class.getDeclaredField("navPageNames");
        names.setAccessible(true);
        names.set(exporter, EdsMobileAppSettings.load(Map.of()).navPageNames());
        final Method resolve = PageJsonExporterImpl.class.getDeclaredMethod("resolveNavPage", Page.class);
        resolve.setAccessible(true);
        return (Page) resolve.invoke(exporter, root);
    }

    @Test
    void mobileNavigationTakesPrecedenceOverNavigation() throws Exception {
        final Page root = context.create().page("/content/site/en");
        context.create().page(root.getPath() + "/nav");
        final Page mobileNav = context.create().page(root.getPath() + "/mobile-nav");
        assertEquals(mobileNav.getPath(), resolveNavPage(root).getPath());
    }

    @Test
    void navigationIsUsedWhenMobileNavigationIsAbsent() throws Exception {
        final Page root = context.create().page("/content/site/en");
        final Page nav = context.create().page(root.getPath() + "/nav");
        assertEquals(nav.getPath(), resolveNavPage(root).getPath());
    }

    @Test
    void missingNavigationStillReturnsNull() throws Exception {
        assertNull(resolveNavPage(context.create().page("/content/site/en")));
    }

    @Test
    void explicitNavigationPathRetainsPrecedence() throws Exception {
        final Page root = context.create().page("/content/site/en", null, "navPath", "/content/custom-nav");
        context.create().page(root.getPath() + "/mobile-nav");
        final Page explicit = context.create().page("/content/custom-nav");
        assertEquals(explicit.getPath(), resolveNavPage(root).getPath());
    }

    @Test
    void bothNavigationPagesAreExcludedFromScreensByDefault() {
        final java.util.List<String> excluded = Arrays.asList(EdsMobileAppSettings.load(Map.of()).excludedPageNames());
        assertTrue(excluded.contains("mobile-nav"));
        assertTrue(excluded.contains("nav"));
    }
}
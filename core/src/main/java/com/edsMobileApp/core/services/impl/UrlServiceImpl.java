package com.edsMobileApp.core.services.impl;

import org.apache.commons.lang3.StringUtils;
import org.osgi.service.component.annotations.Component;

import com.edsMobileApp.core.services.UrlService;

@Component(service = UrlService.class)
public class UrlServiceImpl implements UrlService {

    private static final String PUBLISH_DOMAIN = "https://publish-p189874-e1977911.adobeaemcloud.com";

    private static final String SVG_SUFFIX = ".svg";
    private static final String PNG_SUFFIX = ".png";

    @Override
    public String getPublishDomain() {
        return PUBLISH_DOMAIN;
    }

    @Override
    public String externalize(final String path) {
        if (StringUtils.isBlank(path)) {
            return path;
        }
        final String trimmed = path.trim();
        final boolean alreadyAbsolute = StringUtils.startsWithAny(trimmed, "http://", "https://", "//", "#", "data:");
        if (alreadyAbsolute || !trimmed.startsWith("/")) {
            return trimmed;
        }
        return PUBLISH_DOMAIN + asRenderableImage(trimmed);
    }

    /**
     * Swaps an {@code .svg} extension for {@code .png}. The mobile client cannot render SVG, so an
     * asset authored as one reaches the app as a blank image - a section background authored as
     * {@code visual-4.svg}, for instance. Done here rather than in each mapping rule because this is
     * the one point every mapped asset passes through: {@code GenericComponent#externalizeIfAsset}
     * (any DAM-prefixed value, whatever the property is called) and
     * {@code AbstractComponentModel#asset} both route through it, as does a section's background
     * image.
     *
     * <p>Applies only to repository paths, which are the ones this method rewrites anyway - an
     * absolute URL on a host we do not control is left alone, since we cannot know a {@code .png}
     * is served there. The extension must exist as a real asset in the DAM; this changes the URL
     * emitted, it does not convert anything.</p>
     */
    private static String asRenderableImage(final String path) {
        return StringUtils.endsWithIgnoreCase(path, SVG_SUFFIX)
                ? StringUtils.removeEndIgnoreCase(path, SVG_SUFFIX) + PNG_SUFFIX
                : path;
    }
}

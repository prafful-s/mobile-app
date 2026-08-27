package com.edsMobileApp.core.services.impl;

import org.apache.commons.lang3.StringUtils;
import org.osgi.service.component.annotations.Component;

import com.edsMobileApp.core.services.UrlService;

@Component(service = UrlService.class)
public class UrlServiceImpl implements UrlService {

    private static final String PUBLISH_DOMAIN = "https://publish-p189874-e1977911.adobeaemcloud.com";

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
        return PUBLISH_DOMAIN + trimmed;
    }
}

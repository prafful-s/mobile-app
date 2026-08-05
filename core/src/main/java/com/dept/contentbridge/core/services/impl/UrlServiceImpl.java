package com.dept.contentbridge.core.services.impl;

import org.apache.commons.lang3.StringUtils;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.metatype.annotations.Designate;

import com.dept.contentbridge.core.services.UrlService;
import com.dept.contentbridge.core.services.UrlServiceConfiguration;

@Component(service = UrlService.class)
@Designate(ocd = UrlServiceConfiguration.class)
public class UrlServiceImpl implements UrlService {

    private String publishDomain;

    @Activate
    protected void activate(final UrlServiceConfiguration config) {
        this.publishDomain = StringUtils.removeEnd(StringUtils.trimToEmpty(config.publishDomain()), "/");
    }

    @Override
    public String getPublishDomain() {
        return StringUtils.trimToEmpty(publishDomain);
    }

    @Override
    public String externalize(final String path) {
        if (StringUtils.isBlank(path)) {
            return path;
        }
        final String trimmed = path.trim();
        final boolean alreadyAbsolute = StringUtils.startsWithAny(trimmed, "http://", "https://", "//", "#", "data:");
        if (alreadyAbsolute || StringUtils.isEmpty(publishDomain) || !trimmed.startsWith("/")) {
            return trimmed;
        }
        return publishDomain + trimmed;
    }
}

package com.dept.contentbridge.core.services;

import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;

/**
 * OSGi configuration for asset URL externalisation.
 */
@ObjectClassDefinition(
        name = "ContentBridge - URL Service",
        description = "Turns repository asset paths into the URLs the consuming client should call.")
public @interface UrlServiceConfiguration {

    @AttributeDefinition(
            name = "Publish domain",
            description = "Absolute origin prepended to /content/dam asset paths, e.g. "
                    + "https://publish-p12345-e67890.adobeaemcloud.com. Leave empty to emit relative paths, "
                    + "which is usually what you want on author.")
    String publishDomain() default "";
}

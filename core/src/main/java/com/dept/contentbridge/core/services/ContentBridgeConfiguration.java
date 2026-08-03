package com.dept.contentbridge.core.services;

import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;

/**
 * OSGi configuration for the ContentBridge JSON exporter.
 */
@ObjectClassDefinition(
        name = "ContentBridge - JSON Exporter",
        description = "Controls how authored pages are serialised into the ContentBridge JSON contract.")
public @interface ContentBridgeConfiguration {

    @AttributeDefinition(
            name = "Publish domain",
            description = "Absolute origin prepended to /content/dam asset paths, e.g. "
                    + "https://publish-p12345-e67890.adobeaemcloud.com. Leave empty to emit relative paths.")
    String publishDomain() default "";

    @AttributeDefinition(
            name = "Allowed content roots",
            description = "The servlet only exports paths under one of these roots; anything else returns "
                    + "403. Defaults to /content so any site can be exported. Narrow it to specific sites "
                    + "if you need a tighter boundary - note that /content also covers /content/dam.")
    String[] allowedRoots() default {"/content"};

    @AttributeDefinition(
            name = "Max screen depth",
            description = "How many levels below the site root are walked when collecting screens.")
    int maxDepth() default 3;

    @AttributeDefinition(
            name = "Cache max-age (seconds)",
            description = "Value used for the Cache-Control header on successful responses. 0 disables caching.")
    int cacheMaxAge() default 300;
}

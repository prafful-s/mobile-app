package com.dept.contentbridge.core.services;

import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;

/**
 * OSGi configuration for the ContentBridge JSON exporter.
 *
 * <p>Deliberately separate from {@link UrlServiceConfiguration}: one OCD shared by two components
 * means both PIDs advertise every property in the web console, so an operator sees settings that
 * the component they are editing ignores.</p>
 */
@ObjectClassDefinition(
        name = "ContentBridge - JSON Exporter",
        description = "Controls how authored pages are serialised into the ContentBridge JSON contract.")
public @interface ContentBridgeConfiguration {

    @AttributeDefinition(
            name = "Allowed content roots",
            description = "The servlet only exports paths under one of these roots; anything else returns "
                    + "403. Defaults to /content so any site can be exported. Narrow it to specific sites "
                    + "if you need a tighter boundary - note that /content also covers /content/dam.")
    String[] allowedRoots() default {"/content"};

    @AttributeDefinition(
            name = "Max screen depth",
            description = "How many levels below the site root are walked when no navigation page is "
                    + "found and the exporter falls back to listing child pages.")
    int maxDepth() default 3;

    @AttributeDefinition(
            name = "Navigation page names",
            description = "Child pages of the site root to look for, in order, when building the menu. "
                    + "The first that exists wins. A 'navPath' property on the site root overrides this "
                    + "entirely. If none is found the exporter falls back to listing child pages.")
    String[] navPageNames() default {"nav", "header", "navigation"};

    @AttributeDefinition(
            name = "Non-screen page names",
            description = "Child pages never exported as screens - navigation and footer fragments are "
                    + "site furniture, not destinations.")
    String[] excludedPageNames() default {"nav", "header", "navigation", "footer"};

    @AttributeDefinition(
            name = "Always include the home screen",
            description = "Navigation menus usually link the home page from the logo rather than listing "
                    + "it, so it would otherwise be missing from both menu and screens. When enabled the "
                    + "site root is prepended if the navigation does not already reference it.")
    boolean includeHomeScreen() default true;

    @AttributeDefinition(
            name = "Author host segment",
            description = "Substring identifying the author host in the 'hostname' value read from "
                    + "/content/<site>/placeholders. Replaced by the publish segment before the host is "
                    + "emitted, since clients must fetch assets from publish.")
    String authorHostSegment() default "author";

    @AttributeDefinition(
            name = "Publish host segment",
            description = "Replacement for the author segment. AEM as a Cloud Service hostnames are "
                    + "author-pNNNNN-eNNNNNN and publish-pNNNNN-eNNNNNN, so only this leading segment "
                    + "differs.")
    String publishHostSegment() default "publish";

    @AttributeDefinition(
            name = "Products folder name",
            description = "Folder name searched for under /content/dam/<site> to find the site's product "
                    + "Content Fragments. A 'productsPath' property on the site root overrides the search.")
    String productsFolderName() default "products";

    @AttributeDefinition(
            name = "Maximum products",
            description = "Upper bound on products read per response, so a large catalogue cannot stall "
                    + "the endpoint. The response is truncated and a warning logged when hit.")
    int maxProducts() default 200;

    @AttributeDefinition(
            name = "Product field aliases",
            description = "Maps the fixed product keys onto Content Fragment element names, as "
                    + "key=alias1,alias2 - first match wins. Only needed when a site's model uses names "
                    + "the defaults do not already cover, e.g. sku=productCode,itemNumber")
    String[] productFieldAliases() default {};

    @AttributeDefinition(
            name = "Cache max-age (seconds)",
            description = "Value used for the Cache-Control header on successful responses. 0 disables caching.")
    int cacheMaxAge() default 300;
}

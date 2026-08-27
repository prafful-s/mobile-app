package com.dept.contentbridge.core.services;

import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;

/**
 * OSGi configuration for the ContentBridge JSON exporter.
 *
 * <p>Deliberately separate from {@link UrlServiceConfiguration}: one OCD shared by two components
 * means both PIDs advertise every property in the web console, so an operator sees settings that
 * the component they are editing ignores.</p>
 *
 * <p>Site-level branding defaults ({@code brand}, {@code thumbnail}, {@code template},
 * {@code vertical}, {@code rightIcon}, {@code rightIconTarget}) do not live here - a single OSGi
 * config is shared by every site the servlet exports, so a per-site value here would apply to
 * all of them regardless of which one was actually requested. Those now live in each site's
 * {@code core/src/main/resources/contentbridge/sites/<site>/defaults.json} under a {@code meta}
 * key (see {@code SiteDefaults}), which ships inside the {@code core} bundle and needs no
 * separate {@code ui.config} deployment.</p>
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
            description = "How many levels below the site root are walked when collecting screens. "
                    + "Every page within this depth becomes a screen unless it is excluded by name or "
                    + "flagged with excludeFromApp, so raising it grows the response.")
    int maxDepth() default 3;

    @AttributeDefinition(
            name = "Navigation page names",
            description = "Child pages of the site root to look for, in order, when building "
                    + "meta.navigation.menu. The first that exists wins, and a 'navPath' property on "
                    + "the site root overrides this entirely. The menu is read from this fragment's "
                    + "links; it does not decide which pages are exported as screens.")
    String[] navPageNames() default {"nav", "header", "navigation"};

    @AttributeDefinition(
            name = "Non-screen page names",
            description = "Pages never exported as screens. Header, navigation and footer fragments "
                    + "are site furniture rather than destinations, a placeholders sheet is "
                    + "configuration, and language-masters/redirects are structural containers. Their "
                    + "descendants are still walked, so the screens beneath a container are not hidden.")
    String[] excludedPageNames() default {
        "nav", "header", "navigation", "footer", "placeholders", "language-masters", "redirects",
    };

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
            name = "Style tokens",
            description = "Resolves the style tokens authors pick (style=bg-default) into the "
                    + "concrete values a client renders, as token=key:value or token=k1:v1;k2:v2. Only "
                    + "used by the v2 schema, on panel styles. An unmapped token is dropped rather than "
                    + "guessed at. Keys the panel suppresses (e.g. padding) never surface, so only map "
                    + "tokens to keys a panel actually emits.")
    String[] styleTokens() default {
        "bg-default=backgroundColor:#F5F8FA"
    };

    @AttributeDefinition(
            name = "Cache max-age (seconds)",
            description = "Value used for the Cache-Control header on successful responses. 0 disables caching.")
    int cacheMaxAge() default 300;

    @AttributeDefinition(
            name = "Raw JSON override",
            description = "Local-iteration switch: when on, a node under an allowed root that carries a raw "
                    + "JSON payload is served verbatim instead of being exported, letting you drop a "
                    + "hand-crafted response at any /content path without a rebuild. The override only "
                    + "triggers where the payload is present, so pages without it are unaffected. Turn off "
                    + "in production if authors should not be able to shape the API response directly.")
    boolean overrideEnabled() default true;

    @AttributeDefinition(
            name = "Raw JSON override property",
            description = "Name of the String property read for the raw JSON override, checked on the "
                    + "requested node and on its jcr:content child. A .json file at the requested path is "
                    + "also served verbatim regardless of this name.")
    String overridePropertyName() default "cbJson";
}
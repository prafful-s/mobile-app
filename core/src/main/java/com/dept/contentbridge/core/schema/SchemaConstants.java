package com.dept.contentbridge.core.schema;

/**
 * Property names, resource types and JSON keys shared by the exporter and the models.
 */
public final class SchemaConstants {

    private SchemaConstants() {
        // constants only
    }

    /* ---- resource types ---- */
    public static final String RT_PAGE = "contentbridge/components/page";
    public static final String RT_BANNER = "contentbridge/components/banner";
    public static final String RT_TITLE = "contentbridge/components/title";
    public static final String RT_HTML_TEXT = "contentbridge/components/htmlText";
    public static final String RT_CARD = "contentbridge/components/card";
    public static final String RT_DIVIDER = "contentbridge/components/divider";
    public static final String RT_IMAGE = "contentbridge/components/image";

    /* ---- AEM Edge Delivery / Franklin resource types ----
     *
     * Franklin does not give each component its own resource type. Everything authored is either a
     * generic 'block' or a 'block/item', and the actual component identity lives in the 'model'
     * property. Structural nodes (root, section, columns and the row/col nodes beneath them) carry
     * no content of their own and are walked through rather than exported. */
    public static final String RT_FRANKLIN_PAGE = "core/franklin/components/page/v1/page";
    public static final String RT_FRANKLIN_ROOT = "core/franklin/components/root/v1/root";
    public static final String RT_FRANKLIN_SECTION = "core/franklin/components/section/v1/section";
    public static final String RT_FRANKLIN_COLUMNS = "core/franklin/components/columns/v1/columns";
    public static final String RT_FRANKLIN_BLOCK = "core/franklin/components/block/v1/block";
    public static final String RT_FRANKLIN_BLOCK_ITEM = "core/franklin/components/block/v1/block/item";
    public static final String RT_FRANKLIN_TEXT = "core/franklin/components/text/v1/text";
    public static final String RT_FRANKLIN_TITLE = "core/franklin/components/title/v1/title";

    /** Component identity for Franklin blocks and block items, e.g. hero, card, accordion-item. */
    public static final String PN_MODEL = "model";
    /** Franklin's own list of which properties on a node are authored content. */
    public static final String PN_MODEL_FIELDS = "modelFields";
    /** Present on container blocks such as cards and accordion. */
    public static final String PN_FILTER = "filter";
    public static final String PN_NAME = "name";

    /* ---- component names emitted in meta.component ---- */
    public static final String COMPONENT_BANNER = "banner";
    public static final String COMPONENT_TITLE = "title";
    public static final String COMPONENT_HTML_TEXT = "htmlText";
    public static final String COMPONENT_CARD = "card";
    public static final String COMPONENT_DIVIDER = "divider";
    public static final String COMPONENT_IMAGE = "image";

    /* ---- envelope keys ---- */
    public static final String KEY_ID = "id";
    public static final String KEY_META = "meta";
    public static final String KEY_SCREENS = "screens";
    public static final String KEY_CONTENT = "content";
    public static final String KEY_BRANDING = "branding";
    public static final String KEY_INTEGRATIONS = "integrations";
    public static final String KEY_PROFILE_VIEWER = "profileViewer";
    public static final String KEY_PRODUCTS = "products";
    public static final String KEY_NAVIGATION = "navigation";
    public static final String KEY_COMPONENT = "component";
    /** Field definitions attached to a form block, taken from the bundled form schemas. */
    public static final String KEY_SCHEMA = "schema";

    /** Properties that may name the form a block renders, tried before the component name. */
    public static final String PN_FORM_NAME = "formName";
    public static final String PN_FORM = "form";

    /* ---- page / app properties authored in AEM ---- */
    public static final String PN_APP_ID = "appId";
    public static final String PN_APP_TYPE = "appType";
    public static final String PN_APP_TITLE = "appTitle";
    public static final String PN_USE_CART = "useCart";
    public static final String PN_LOGIN_SCREEN = "loginScreen";
    public static final String PN_PROFILE_SCREEN = "profileScreen";
    public static final String PN_REGISTRATION_SCREEN = "registrationScreen";
    public static final String PN_AUTHORIZED_MENU = "authorizedMenu";
    public static final String PN_SCREEN_ICON = "screenIcon";
    /** Explicit path to the navigation page, overriding the configured name lookup. */
    public static final String PN_NAV_PATH = "navPath";
    public static final String PN_EXCLUDE_FROM_APP = "excludeFromApp";

    /** Child node of jcr:content holding the branding palette. */
    public static final String NN_BRANDING = "contentbridge/branding";
    /** Child node of jcr:content holding raw JSON blobs for integrations/profileViewer/products. */
    public static final String NN_EXTRAS = "contentbridge/extras";

    public static final String PN_INTEGRATIONS_JSON = "integrationsJson";
    public static final String PN_PROFILE_VIEWER_JSON = "profileViewerJson";
    public static final String PN_PRODUCTS_JSON = "productsJson";

    /** Name of the responsive/container node holding the authored components on a page. */
    public static final String NN_CONTAINER = "root/container";

    /* ---- defaults ---- */
    public static final String DEFAULT_APP_TYPE = "mobile-v2";
    public static final String DEFAULT_HOME_ICON = "home";
    public static final String DEFAULT_SCREEN_ICON = "file-document";
}

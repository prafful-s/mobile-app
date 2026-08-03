package com.dept.contentbridge.core.models.impl;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.models.annotations.DefaultInjectionStrategy;
import org.apache.sling.models.annotations.Model;

import com.dept.contentbridge.core.schema.ContentBridgeComponent;
import com.dept.contentbridge.core.schema.SchemaConstants;

/**
 * Hero banner: full-bleed image with optional overlaid heading, rich-text subtitle and CTA.
 */
@Model(adaptables = Resource.class,
       adapters = {ContentBridgeComponent.class, BannerModel.class},
       resourceType = SchemaConstants.RT_BANNER,
       defaultInjectionStrategy = DefaultInjectionStrategy.OPTIONAL)
public class BannerModel extends AbstractComponentModel {

    @Override
    public String getComponentName() {
        return SchemaConstants.COMPONENT_BANNER;
    }

    @Override
    protected void buildContent(final ContentBuilder builder) {
        final boolean showButton = bool("showButton", false);
        builder.put("image", asset("fileReference"))
                .put("title", prop("title"))
                .put("titleVariant", prop("titleVariant", "titleLarge"))
                .put("subtitle", prop("subtitle"));
        if (showButton) {
            builder.put("buttonLabel", prop("buttonLabel"))
                    .put("linkTo", externalize(prop("linkTo")))
                    .put("buttonIcon", prop("buttonIcon"));
        }
        builder.putFlag("showButton", showButton)
                .put("horizontalAlignment", prop("horizontalAlignment", "left"))
                .put("verticalAlignment", prop("verticalAlignment", "middle"))
                .put("contentAlignment", prop("contentAlignment", "left"))
                .putFlag("isSubtitleRichText", bool("isSubtitleRichText", true));
    }
}

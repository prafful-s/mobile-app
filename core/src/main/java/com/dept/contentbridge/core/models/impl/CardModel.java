package com.dept.contentbridge.core.models.impl;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.models.annotations.DefaultInjectionStrategy;
import org.apache.sling.models.annotations.Model;

import com.dept.contentbridge.core.schema.ContentBridgeComponent;
import com.dept.contentbridge.core.schema.SchemaConstants;

/**
 * Card teaser. Content may be authored inline or pulled from a linked page / content fragment.
 */
@Model(adaptables = Resource.class,
       adapters = {ContentBridgeComponent.class, CardModel.class},
       resourceType = SchemaConstants.RT_CARD,
       defaultInjectionStrategy = DefaultInjectionStrategy.OPTIONAL)
public class CardModel extends AbstractComponentModel {

    @Override
    public String getComponentName() {
        return SchemaConstants.COMPONENT_CARD;
    }

    @Override
    protected void buildContent(final ContentBuilder builder) {
        builder.put("image", asset("fileReference"))
                .put("link", prop("link"))
                .put("title", prop("jcr:title"))
                .put("titleVariant", prop("titleVariant", "titleMedium"))
                .put("textTransform", prop("textTransform", "none"))
                .putFlag("isSubtitleRichText", bool("isSubtitleRichText", true))
                .put("subtitle", prop("subtitle"))
                .put("buttonLabel", prop("buttonLabel"))
                .put("cardType", prop("cardType", "stacked"));
    }
}

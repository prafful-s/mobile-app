package com.dept.contentbridge.core.models.impl;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.models.annotations.DefaultInjectionStrategy;
import org.apache.sling.models.annotations.Model;

import com.dept.contentbridge.core.schema.ContentBridgeComponent;
import com.dept.contentbridge.core.schema.SchemaConstants;

/**
 * Plain-text heading with a size token and alignment.
 */
@Model(adaptables = Resource.class,
       adapters = {ContentBridgeComponent.class, TitleModel.class},
       resourceType = SchemaConstants.RT_TITLE,
       defaultInjectionStrategy = DefaultInjectionStrategy.OPTIONAL)
public class TitleModel extends AbstractComponentModel {

    @Override
    public String getComponentName() {
        return SchemaConstants.COMPONENT_TITLE;
    }

    @Override
    protected void buildContent(final ContentBuilder builder) {
        // textAlignment is inherited from the enclosing section's sec-alignment token
        final String textAlignment = resource.getParent() != null
                ? resource.getParent().getValueMap().get("sec-alignment", "")
                : "";
        builder.put("text", prop("jcr:title"))
                .put("size", prop("size", "medium"))
                .putOrEmpty("weight", prop("weight"))
                .putOrEmpty("textColor", prop("textColor"))
                .putOrEmpty("textAlignment", textAlignment);
    }
}

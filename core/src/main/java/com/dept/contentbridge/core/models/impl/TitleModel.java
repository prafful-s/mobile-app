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
        builder.put("text", prop("jcr:title"))
                .put("size", prop("size", "medium"))
                .put("textAlignment", prop("textAlignment", "left"));
    }
}

package com.dept.contentbridge.core.models.impl;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.models.annotations.DefaultInjectionStrategy;
import org.apache.sling.models.annotations.Model;

import com.dept.contentbridge.core.schema.ContentBridgeComponent;
import com.dept.contentbridge.core.schema.SchemaConstants;

/**
 * Standalone image, optionally wrapped in a link.
 */
@Model(adaptables = Resource.class,
       adapters = {ContentBridgeComponent.class, ImageModel.class},
       resourceType = SchemaConstants.RT_IMAGE,
       defaultInjectionStrategy = DefaultInjectionStrategy.OPTIONAL)
public class ImageModel extends AbstractComponentModel {

    @Override
    public String getComponentName() {
        return SchemaConstants.COMPONENT_IMAGE;
    }

    @Override
    protected void buildContent(final ContentBuilder builder) {
        builder.put("image", asset("fileReference"))
                .put("link", prop("link"))
                .put("alt", prop("alt"));
    }
}

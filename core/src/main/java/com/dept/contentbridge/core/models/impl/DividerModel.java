package com.dept.contentbridge.core.models.impl;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.models.annotations.DefaultInjectionStrategy;
import org.apache.sling.models.annotations.Model;

import com.dept.contentbridge.core.schema.ContentBridgeComponent;
import com.dept.contentbridge.core.schema.SchemaConstants;

/**
 * Horizontal rule. Carries no payload - {@code content} is intentionally an empty object.
 */
@Model(adaptables = Resource.class,
       adapters = {ContentBridgeComponent.class, DividerModel.class},
       resourceType = SchemaConstants.RT_DIVIDER,
       defaultInjectionStrategy = DefaultInjectionStrategy.OPTIONAL)
public class DividerModel extends AbstractComponentModel {

    @Override
    public String getComponentName() {
        return SchemaConstants.COMPONENT_DIVIDER;
    }

    @Override
    protected void buildContent(final ContentBuilder builder) {
        // no properties by design
    }
}

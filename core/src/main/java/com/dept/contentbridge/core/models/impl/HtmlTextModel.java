package com.dept.contentbridge.core.models.impl;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.models.annotations.DefaultInjectionStrategy;
import org.apache.sling.models.annotations.Model;

import com.dept.contentbridge.core.schema.ContentBridgeComponent;
import com.dept.contentbridge.core.schema.SchemaConstants;

/**
 * Rich-text block. The authored markup is passed through verbatim; the client is responsible for
 * rendering it safely.
 */
@Model(adaptables = Resource.class,
       adapters = {ContentBridgeComponent.class, HtmlTextModel.class},
       resourceType = SchemaConstants.RT_HTML_TEXT,
       defaultInjectionStrategy = DefaultInjectionStrategy.OPTIONAL)
public class HtmlTextModel extends AbstractComponentModel {

    @Override
    public String getComponentName() {
        return SchemaConstants.COMPONENT_HTML_TEXT;
    }

    @Override
    protected void buildContent(final ContentBuilder builder) {
        builder.put("text", prop("text"))
                .put("textAlignment", prop("textAlignment"));
    }
}

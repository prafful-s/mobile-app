package com.edsMobileApp.core.models.impl;

import java.util.Map;

import javax.annotation.PostConstruct;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.models.annotations.DefaultInjectionStrategy;
import org.apache.sling.models.annotations.Model;
import org.apache.sling.models.annotations.injectorspecific.OSGiService;
import org.apache.sling.models.annotations.injectorspecific.SlingObject;

import com.edsMobileApp.core.schema.ContentBridgeComponent;
import com.edsMobileApp.core.schema.SchemaConstants;
import com.edsMobileApp.core.services.UrlService;

/**
 * Registers the AEM Edge Delivery (Franklin) resource types explicitly.
 *
 * <p>Behaviourally this is identical to the generic fallback - Edge Delivery's {@code model} and
 * {@code modelFields} conventions are exactly what {@link GenericComponent} implements - so all the
 * logic is delegated rather than duplicated. The registration is kept because it makes the supported
 * vocabulary visible at {@code /system/console/status-slingmodels}, which is the first place anyone
 * looks when the export comes back empty.</p>
 */
@Model(adaptables = Resource.class,
       adapters = {ContentBridgeComponent.class, FranklinComponentModel.class},
       resourceType = {
           SchemaConstants.RT_FRANKLIN_BLOCK,
           SchemaConstants.RT_FRANKLIN_BLOCK_ITEM,
           SchemaConstants.RT_FRANKLIN_TEXT,
           SchemaConstants.RT_FRANKLIN_TITLE
       },
       defaultInjectionStrategy = DefaultInjectionStrategy.OPTIONAL)
public class FranklinComponentModel implements ContentBridgeComponent {

    @SlingObject
    private Resource resource;

    @OSGiService
    private UrlService urlService;

    private ContentBridgeComponent delegate;

    @PostConstruct
    protected void init() {
        this.delegate = new GenericComponent(resource, urlService);
    }

    @Override
    public String getId() {
        return delegate.getId();
    }

    @Override
    public String getComponentName() {
        return delegate.getComponentName();
    }

    @Override
    public Map<String, Object> getContent() {
        return delegate.getContent();
    }
}

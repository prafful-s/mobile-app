package com.dept.contentbridge.core.schema;

import java.util.Map;

import org.osgi.annotation.versioning.ConsumerType;

/**
 * Contract every ContentBridge component Sling Model must fulfil.
 *
 * <p>The exporter adapts each child resource of a page's container to this interface. Whichever
 * model is registered for that {@code sling:resourceType} wins, so adding a new component to the
 * JSON contract is purely a matter of adding a model annotated with the new resource type - no
 * change to the exporter is required.</p>
 *
 * <p>Serialised shape:</p>
 * <pre>
 * {
 *   "meta":    { "id": "9199a89b", "component": "banner" },
 *   "content": { ...getContent()... }
 * }
 * </pre>
 */
@ConsumerType
public interface ContentBridgeComponent {

    /**
     * @return stable identifier for this component instance, unique within the app.
     */
    String getId();

    /**
     * @return the component name as it appears in {@code meta.component}, e.g. {@code banner}.
     */
    String getComponentName();

    /**
     * @return the component payload. Implementations must return an insertion-ordered map and must
     *         omit keys whose value is blank so the JSON stays free of empty noise. Never null -
     *         return an empty map instead (see {@code divider}).
     */
    Map<String, Object> getContent();
}

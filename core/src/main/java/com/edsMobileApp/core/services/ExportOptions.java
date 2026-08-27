package com.edsMobileApp.core.services;

import org.osgi.annotation.versioning.ProviderType;

/**
 * Per-request options for an export.
 *
 * <p>Immutable, so it can be built once per request and passed down without defensive copying. The
 * export is always the nested contract; the only knob is whether screen content is reduced to the
 * enabled component set.</p>
 */
@ProviderType
public final class ExportOptions {

    private final boolean reduced;

    private ExportOptions(final boolean reduced) {
        this.reduced = reduced;
    }

    /**
     * @param reduced when true, screen content is reduced to the enabled components
     *                ({@code banner}, {@code card}, {@code htmlText}, {@code form}) and panels
     *                left empty are pruned
     */
    public static ExportOptions of(final boolean reduced) {
        return new ExportOptions(reduced);
    }

    /** @return true when screen content should be reduced to the enabled component set */
    public boolean isReduced() {
        return reduced;
    }

    @Override
    public String toString() {
        return "ExportOptions[reduced=" + reduced + "]";
    }
}

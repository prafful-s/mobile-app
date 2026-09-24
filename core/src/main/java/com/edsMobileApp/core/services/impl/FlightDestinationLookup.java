package com.edsMobileApp.core.services.impl;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.lang3.StringUtils;
import org.apache.sling.api.resource.Resource;

/**
 * Resolves a screen's flight-inventory filter value - the city products are filtered by on a
 * destination screen's {@code productList} block - from the site's destination-dropdown Content
 * Fragment (see {@code SiteDefaults#KEY_FLIGHT_DESTINATIONS_FRAGMENT}), so adding a destination is
 * a content change - a new row in the fragment's {@code destination_mapping} multifield - rather
 * than a code deployment.
 *
 * <p>Each row is authored as free text shaped like a JS object literal, e.g.
 * {@code "{ code: 'LAS', city: 'Las Vegas', country: 'United States' }"} - not JSON, so
 * {@link #ROW_PATTERN} parses it rather than a JSON reader. A screen key is matched against the
 * row's kebab-cased {@code city} first (covers city-named screens like {@code las-vegas}), then
 * its kebab-cased {@code country} (covers country-named screens like {@code poland}) - the two
 * screen-key conventions this site's destination screens actually use. Either way the row's
 * {@code city} is what {@link #cityForScreen} returns: flight products are tagged with their
 * destination city (see {@code ProductCatalog}), not country, so the filter value must match that.
 * A country with more than one city (Mexico's Cancún/Tulum) returns whichever row is found first -
 * a screen covering several cities under one filter isn't handled yet.</p>
 *
 * <p>{@link #forScreen} exposes the whole matched row ({@code code}/{@code city}/{@code country}),
 * not just the city - needed while the app's {@code flightResults} component's exact
 * {@code to} matching rule (city name? airport code? something else?) is still being verified; see
 * {@code PageJsonExporterImpl#applyFlightDestination}.</p>
 */
final class FlightDestinationLookup {

    private static final String ELEMENT_DESTINATION_MAPPING = "destination_mapping";
    private static final Pattern ROW_PATTERN = Pattern.compile(
            "code\\s*:\\s*'([^']*)'\\s*,\\s*city\\s*:\\s*'([^']*)'\\s*,\\s*country\\s*:\\s*'([^']*)'");

    private FlightDestinationLookup() {
    }

    /** One {@code destination_mapping} row: the airport/city code, the city, and the country. */
    static final class Destination {
        final String code;
        final String city;
        final String country;

        private Destination(final String code, final String city, final String country) {
            this.code = code;
            this.city = city;
            this.country = country;
        }
    }

    /**
     * @param fragment  the destination-dropdown Content Fragment's own resource, or {@code null}
     * @param screenKey the exported screen key, e.g. {@code "las-vegas"} or {@code "poland"}
     * @return the matching row's city, or {@code null} when the fragment, or a matching row, is
     *         not found
     */
    static String cityForScreen(final Resource fragment, final String screenKey) {
        final Destination destination = forScreen(fragment, screenKey);
        return destination == null ? null : destination.city;
    }

    /**
     * @param fragment  the destination-dropdown Content Fragment's own resource, or {@code null}
     * @param screenKey the exported screen key, e.g. {@code "las-vegas"} or {@code "poland"}
     * @return the matching row in full, or {@code null} when the fragment, or a matching row, is
     *         not found
     */
    static Destination forScreen(final Resource fragment, final String screenKey) {
        if (fragment == null || StringUtils.isBlank(screenKey)) {
            return null;
        }
        Destination byCountry = null;
        for (final String row : ContentFragmentReader.readMultiValue(fragment, ELEMENT_DESTINATION_MAPPING)) {
            final Matcher matcher = ROW_PATTERN.matcher(row);
            if (!matcher.find()) {
                continue;
            }
            final String code = matcher.group(1).trim();
            final String city = matcher.group(2).trim();
            final String country = matcher.group(3).trim();
            if (screenKey.equals(PageJsonExporterImpl.kebab(city))) {
                return new Destination(code, city, country);
            }
            if (byCountry == null && screenKey.equals(PageJsonExporterImpl.kebab(country))) {
                byCountry = new Destination(code, city, country);
            }
        }
        return byCountry;
    }
}

package com.flightmonitor.infrastructure.catalog;

import com.flightmonitor.config.FlightMonitorProperties;
import com.flightmonitor.config.SafetyConfig;
import com.flightmonitor.domain.port.AirportCatalog;
import com.flightmonitor.domain.port.RoutePreFilter;
import java.util.Collection;
import org.springframework.stereotype.Component;

/**
 * Applies the blocked-country and blocked-airport lists to a bare set of airport codes.
 *
 * <p>Deliberately more permissive than {@code SafetyRule}: an airport missing from the catalogue is
 * treated as plausible here rather than rejected. This runs on a partial itinerary to decide
 * whether finishing it is worth an API call, and being strict at that point would discard routes
 * the full check might well accept. The full check still fails closed afterwards.
 */
@Component
public class SafetyRoutePreFilter implements RoutePreFilter {

    private final SafetyConfig safety;
    private final AirportCatalog catalog;

    public SafetyRoutePreFilter(FlightMonitorProperties properties, AirportCatalog catalog) {
        this.safety = properties.safety();
        this.catalog = catalog;
    }

    @Override
    public boolean plausible(Collection<String> airports) {
        for (String iata : airports) {
            if (iata == null) {
                continue;
            }
            if (safety.blocksAirport(iata)) {
                return false;
            }
            boolean blockedCountry = catalog.find(iata)
                    .map(airport -> safety.blocksCountry(airport.countryCode()))
                    .orElse(false);
            if (blockedCountry) {
                return false;
            }
        }
        return true;
    }
}

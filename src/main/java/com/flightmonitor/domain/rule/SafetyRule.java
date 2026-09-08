package com.flightmonitor.domain.rule;

import com.flightmonitor.config.SafetyConfig;
import com.flightmonitor.domain.model.Airport;
import com.flightmonitor.domain.model.Itinerary;
import com.flightmonitor.domain.model.Rejection;
import com.flightmonitor.domain.model.RejectionReason;
import com.flightmonitor.domain.port.AirportCatalog;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Applies the user's routing preference to a whole itinerary.
 *
 * <p>The rule is intentionally mechanical: <em>blocked country or blocked airport anywhere in the
 * itinerary means reject</em>. There is no scoring, no partial credit, and no attempt by the
 * system to judge which places are safe — see {@link SafetyConfig} for why.
 *
 * <p>"Anywhere" means every airport the itinerary touches, in both directions: the origin, the
 * final destination, and every intermediate airport, taken both from the segment boundaries and
 * from the source's own layover list. Checking only the first and last city would let
 * {@code HND -> DOH -> GRU -> POA} through, which is exactly the case the user called out.
 *
 * <p>The rule fails closed twice over. An airport that is not in the catalogue cannot have its
 * country checked, so it is rejected. An offer from a source that never disclosed its connections
 * cannot be checked at all, so it is rejected too. Both behaviours are configurable, and both
 * default to rejecting.
 */
public final class SafetyRule {

    private final SafetyConfig config;
    private final AirportCatalog catalog;

    public SafetyRule(SafetyConfig config, AirportCatalog catalog) {
        this.config = config;
        this.catalog = catalog;
    }

    /** @return every safety problem found; an empty list means the itinerary is acceptable */
    public List<Rejection> check(Itinerary itinerary) {
        List<Rejection> rejections = new ArrayList<>();

        if (!itinerary.detailLevel().allowsSafetyVerification()) {
            if (config.rejectUnverifiableRoutes()) {
                rejections.add(Rejection.of(
                        RejectionReason.ROUTE_UNVERIFIABLE,
                        "source=" + itinerary.source()
                                + " detail=" + itinerary.detailLevel()
                                + " stops=" + itinerary.totalStops()
                                + " (connecting airports not disclosed, cannot verify)"));
            }
            // Even without connection detail, the endpoints themselves are still checkable.
            checkAirports(endpointsOf(itinerary), rejections);
            return rejections;
        }

        checkAirports(itinerary.airportsVisited(), rejections);
        return rejections;
    }

    private Set<String> endpointsOf(Itinerary itinerary) {
        List<String> endpoints = new ArrayList<>();
        endpoints.add(itinerary.originAirport());
        endpoints.add(itinerary.destinationAirport());
        itinerary.inboundLeg().ifPresent(leg -> {
            endpoints.add(leg.originAirport());
            endpoints.add(leg.destinationAirport());
        });
        return new java.util.LinkedHashSet<>(endpoints.stream().filter(java.util.Objects::nonNull).toList());
    }

    private void checkAirports(Set<String> airports, List<Rejection> rejections) {
        for (String iata : airports) {
            if (config.blocksAirport(iata)) {
                rejections.add(Rejection.of(RejectionReason.BLOCKED_AIRPORT, "airport=" + iata));
                continue;
            }
            Optional<Airport> airport = catalog.find(iata);
            if (airport.isEmpty()) {
                if (config.rejectUnknownAirports()) {
                    rejections.add(Rejection.of(
                            RejectionReason.UNKNOWN_AIRPORT_COUNTRY, "airport=" + iata));
                }
                continue;
            }
            Airport resolved = airport.get();
            if (config.blocksCountry(resolved.countryCode())) {
                rejections.add(Rejection.of(
                        RejectionReason.BLOCKED_COUNTRY,
                        "country=" + resolved.countryName()
                                + " code=" + resolved.countryCode()
                                + " airport=" + iata));
            }
        }
    }
}

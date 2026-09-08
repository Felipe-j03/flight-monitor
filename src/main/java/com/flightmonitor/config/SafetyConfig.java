package com.flightmonitor.config;

import java.util.List;
import java.util.Set;

/**
 * The user's personal routing preferences.
 *
 * <p><b>This list is a user preference, not a risk assessment.</b> The system deliberately does not
 * compute, infer or maintain any notion of which countries are "safe". It applies exactly one rule:
 * if a country or airport appears here, the itinerary is rejected. Editing this list is the only
 * way to change that behaviour, and it lives entirely in configuration
 * ({@code application.yml} / {@code BLOCKED_COUNTRIES} / {@code BLOCKED_AIRPORTS}) rather than
 * anywhere in the code.
 *
 * @param blockedCountries         ISO 3166-1 alpha-2 codes to reject
 * @param blockedAirports          IATA codes to reject regardless of their country
 * @param rejectUnverifiableRoutes when true (default) an offer whose connecting airports the source
 *                                 never disclosed is rejected rather than assumed acceptable
 * @param rejectUnknownAirports    when true (default) an airport missing from the catalogue is
 *                                 rejected, because its country cannot be checked
 */
public record SafetyConfig(
        Set<String> blockedCountries,
        Set<String> blockedAirports,
        boolean rejectUnverifiableRoutes,
        boolean rejectUnknownAirports) {

    public SafetyConfig {
        blockedCountries = normalize(blockedCountries);
        blockedAirports = normalize(blockedAirports);
    }

    private static Set<String> normalize(Set<String> values) {
        if (values == null) {
            return Set.of();
        }
        return values.stream()
                .filter(v -> v != null && !v.isBlank())
                .map(v -> v.trim().toUpperCase())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    public boolean blocksCountry(String isoCode) {
        return isoCode != null && blockedCountries.contains(isoCode.toUpperCase());
    }

    public boolean blocksAirport(String iata) {
        return iata != null && blockedAirports.contains(iata.toUpperCase());
    }

    public List<String> sortedCountries() {
        return blockedCountries.stream().sorted().toList();
    }

    public List<String> sortedAirports() {
        return blockedAirports.stream().sorted().toList();
    }
}

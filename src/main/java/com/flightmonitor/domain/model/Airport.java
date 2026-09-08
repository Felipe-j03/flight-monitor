package com.flightmonitor.domain.model;

import java.time.ZoneId;

/**
 * A known airport. Sourced from a bundled catalogue, not from provider payloads, so the
 * airport-to-country mapping the safety filter depends on cannot be influenced by a provider.
 */
public record Airport(
        String iata,
        String name,
        String city,
        String countryCode,
        String countryName,
        ZoneId zone) {

    public Airport {
        iata = iata == null ? null : iata.trim().toUpperCase();
        countryCode = countryCode == null ? null : countryCode.trim().toUpperCase();
    }
}

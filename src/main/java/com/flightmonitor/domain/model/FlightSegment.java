package com.flightmonitor.domain.model;

import java.time.Duration;
import java.time.OffsetDateTime;

/**
 * One marketed flight number between two airports. Any field the source did not provide is
 * {@code null} — it is never filled in with a guess.
 */
public record FlightSegment(
        String departureAirport,
        OffsetDateTime departureAt,
        String arrivalAirport,
        OffsetDateTime arrivalAt,
        Duration duration,
        String marketingAirline,
        String operatingAirline,
        String flightNumber,
        String aircraft,
        String travelClass) {

    public FlightSegment {
        departureAirport = normalizeIata(departureAirport);
        arrivalAirport = normalizeIata(arrivalAirport);
    }

    private static String normalizeIata(String code) {
        return code == null ? null : code.trim().toUpperCase();
    }
}

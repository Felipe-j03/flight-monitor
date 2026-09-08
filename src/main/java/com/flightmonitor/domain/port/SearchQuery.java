package com.flightmonitor.domain.port;

import java.time.LocalDate;

/**
 * One concrete question to ask a provider: this origin, this destination, these dates.
 *
 * @param priority lower is more important; the query planner emits the ideal date/airport
 *                 combination first so a metered provider spends its budget where it matters
 */
public record SearchQuery(
        String tripId,
        String origin,
        String destination,
        LocalDate departureDate,
        LocalDate returnDate,
        int adults,
        String cabinClass,
        int priority) {

    public SearchQuery {
        origin = origin.toUpperCase();
        destination = destination.toUpperCase();
    }

    public boolean isRoundTrip() {
        return returnDate != null;
    }

    public String describe() {
        return origin + "->" + destination + " " + departureDate
                + (returnDate == null ? " (one way)" : " / " + returnDate);
    }
}

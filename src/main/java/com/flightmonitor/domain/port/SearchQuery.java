package com.flightmonitor.domain.port;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Arrays;
import java.util.List;

/**
 * One concrete question to ask a provider: this origin, this destination, these dates.
 *
 * @param destination        one IATA code, or several joined by commas when the trip searches a
 *                           city's airports together ({@code "GIG,SDU"})
 * @param priority           lower is more important; the query planner emits the ideal date/airport
 *                           combination first so a metered provider spends its budget where it matters
 * @param currency           currency to request prices in; converted to GBP afterwards
 * @param outboundFrom       optional earliest local departure time for the outbound leg
 * @param outboundTo         optional latest local departure time for the outbound leg
 * @param preferredOutbound  optional ideal outbound departure time, used to decide which options
 *                           are worth paying to resolve
 * @param maxOptions         optional per-query override of how many outbound options to resolve
 */
public record SearchQuery(
        String tripId,
        String origin,
        String destination,
        LocalDate departureDate,
        LocalDate returnDate,
        int adults,
        String cabinClass,
        int priority,
        String currency,
        LocalTime outboundFrom,
        LocalTime outboundTo,
        LocalTime preferredOutbound,
        Integer maxOptions) {

    public SearchQuery {
        origin = origin.toUpperCase();
        destination = destination.toUpperCase().replace(" ", "");
        currency = currency == null || currency.isBlank() ? "GBP" : currency.toUpperCase();
    }

    /** A query with no time window, no preference and prices in GBP. */
    public SearchQuery(
            String tripId,
            String origin,
            String destination,
            LocalDate departureDate,
            LocalDate returnDate,
            int adults,
            String cabinClass,
            int priority) {
        this(tripId, origin, destination, departureDate, returnDate, adults, cabinClass, priority,
                "GBP", null, null, null, null);
    }

    public boolean isRoundTrip() {
        return returnDate != null;
    }

    public boolean hasOutboundWindow() {
        return outboundFrom != null && outboundTo != null;
    }

    public List<String> destinations() {
        return Arrays.stream(destination.split(",")).filter(code -> !code.isBlank()).toList();
    }

    /** The same question for a single destination airport, for providers that accept only one. */
    public SearchQuery forDestination(String airport) {
        return new SearchQuery(tripId, origin, airport, departureDate, returnDate, adults,
                cabinClass, priority, currency, outboundFrom, outboundTo, preferredOutbound,
                maxOptions);
    }

    public String describe() {
        return origin + "->" + destination + " " + departureDate
                + (returnDate == null ? " (one way)" : " / " + returnDate);
    }
}

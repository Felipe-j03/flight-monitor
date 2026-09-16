package com.flightmonitor.domain.port;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Arrays;
import java.util.List;

/**
 * One concrete question to ask a provider: this origin, this destination, these dates.
 *
 * @param origin             one IATA code, or several joined by commas ({@code "HND,NRT"})
 * @param destination        one IATA code, or several joined by commas ({@code "GIG,SDU"})
 * @param returnDate         null for a one-way question
 * @param priority           lower is more important; the query planner emits the ideal date/airport
 *                           combination first so a metered provider spends its budget where it matters
 * @param currency           currency to request prices in; converted to GBP afterwards
 * @param outboundFrom       optional earliest local departure time for the outbound leg
 * @param outboundTo         optional latest local departure time for the outbound leg
 * @param preferredOutbound  optional ideal outbound departure time, used to decide which options
 *                           are worth paying to resolve
 * @param maxOptions         optional per-query override of how many outbound options to resolve
 * @param returnOrigin       open jaw: where the return leaves from, when not {@code destination};
 *                           null for a normal round trip
 * @param returnDestination  open jaw: where the return lands; null means back to {@code origin}
 * @param maxPrice           optional hard cap in {@code currency}; options above it are not worth
 *                           a paid call to resolve
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
        Integer maxOptions,
        String returnOrigin,
        String returnDestination,
        BigDecimal maxPrice) {

    public SearchQuery {
        origin = normalize(origin);
        destination = normalize(destination);
        returnOrigin = returnOrigin == null || returnOrigin.isBlank() ? null : normalize(returnOrigin);
        returnDestination = returnDestination == null || returnDestination.isBlank()
                ? null
                : normalize(returnDestination);
        currency = currency == null || currency.isBlank() ? "GBP" : currency.toUpperCase();
    }

    private static String normalize(String airports) {
        return airports.toUpperCase().replace(" ", "");
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
                "GBP", null, null, null, null, null, null, null);
    }

    public boolean isRoundTrip() {
        return returnDate != null;
    }

    /** The return leaves from somewhere other than where the outbound lands: a multi-city ticket. */
    public boolean isOpenJaw() {
        return isRoundTrip() && returnOrigin != null;
    }

    public boolean hasOutboundWindow() {
        return outboundFrom != null && outboundTo != null;
    }

    public List<String> destinations() {
        return split(destination);
    }

    public List<String> origins() {
        return split(origin);
    }

    private static List<String> split(String airports) {
        return Arrays.stream(airports.split(",")).filter(code -> !code.isBlank()).toList();
    }

    /** The same question for a single destination airport, for providers that accept only one. */
    public SearchQuery forDestination(String airport) {
        return new SearchQuery(tripId, origin, airport, departureDate, returnDate, adults,
                cabinClass, priority, currency, outboundFrom, outboundTo, preferredOutbound,
                maxOptions, returnOrigin, returnDestination, maxPrice);
    }

    /** The same question for a single origin airport. */
    public SearchQuery forOrigin(String airport) {
        return new SearchQuery(tripId, airport, destination, departureDate, returnDate, adults,
                cabinClass, priority, currency, outboundFrom, outboundTo, preferredOutbound,
                maxOptions, returnOrigin, returnDestination, maxPrice);
    }

    public String describe() {
        String outbound = origin + "->" + destination + " " + departureDate;
        if (!isRoundTrip()) {
            return outbound + " (one way)";
        }
        if (isOpenJaw()) {
            return outbound + " + " + returnOrigin + "->"
                    + (returnDestination == null ? origin : returnDestination) + " " + returnDate;
        }
        return outbound + " / " + returnDate;
    }
}

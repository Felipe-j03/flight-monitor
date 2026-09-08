package com.flightmonitor.domain.model;

/**
 * How much of the itinerary the source actually disclosed.
 *
 * <p>This drives the safety filter. A source that only reports "1 stop" without naming the
 * connecting airport cannot be checked against the blocked-country list, and the system must not
 * pretend otherwise (see {@code SafetyRule} and the {@code ROUTE_UNVERIFIABLE} rejection reason).
 */
public enum RouteDetailLevel {

    /** Every segment and every connecting airport is known. Safety filter can be fully applied. */
    FULL_ITINERARY,

    /** Some segments known, but the source may have omitted connections. Treated as unverifiable. */
    PARTIAL,

    /** Only a stop count and totals are known. No connecting airports at all. */
    COUNTS_ONLY;

    public boolean allowsSafetyVerification() {
        return this == FULL_ITINERARY;
    }
}

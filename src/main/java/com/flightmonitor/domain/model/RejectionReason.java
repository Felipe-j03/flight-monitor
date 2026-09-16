package com.flightmonitor.domain.model;

/** Why an offer was filtered out. Always logged and persisted alongside the offending detail. */
public enum RejectionReason {

    /** A country on the user's blocked list appears somewhere in the itinerary. */
    BLOCKED_COUNTRY,

    /** A specific airport on the user's blocked list appears somewhere in the itinerary. */
    BLOCKED_AIRPORT,

    /**
     * An airport in the itinerary is not in the catalogue, so its country cannot be established.
     * The safety filter fails closed: an unmappable airport is rejected rather than assumed safe.
     */
    UNKNOWN_AIRPORT_COUNTRY,

    /**
     * The source disclosed only a stop count, not the connecting airports, so the blocked-country
     * rule cannot be applied to it at all. Fails closed by default.
     */
    ROUTE_UNVERIFIABLE,

    /** Outbound departure falls outside the configured window around the target date. */
    DEPARTURE_OUTSIDE_WINDOW,

    /** Outbound leaves outside the configured time-of-day window (e.g. an afternoon flight). */
    DEPARTURE_TIME_OUTSIDE_WINDOW,

    /** The return would land back home after the trip's hard deadline. */
    RETURN_ARRIVAL_TOO_LATE,

    /** Longest leg exceeds the absolute duration cap. */
    MAX_DURATION_EXCEEDED,

    /** The offer is a round trip requirement but the source returned no return leg. */
    MISSING_RETURN_LEG,

    /** Essential fields (airports, dates, price) were absent, so the offer cannot be evaluated. */
    MISSING_REQUIRED_DATA
}

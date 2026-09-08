package com.flightmonitor.application;

/** The kinds of news worth interrupting someone for. */
public enum AlertType {

    /** Price fell by enough to clear at least one of the configured thresholds. */
    PRICE_DROP,

    /** Cheapest this exact itinerary has ever been since monitoring began. */
    NEW_ALL_TIME_LOW,

    /** Price crossed below the budget floor — the "excelente" case. */
    EXCELLENT_PRICE,

    /** A newly discovered itinerary that is already inside the budget window. */
    NEW_OFFER_WITHIN_BUDGET
}

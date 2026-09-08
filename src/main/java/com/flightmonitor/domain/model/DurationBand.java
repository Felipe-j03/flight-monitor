package com.flightmonitor.domain.model;

import java.time.Duration;

/**
 * Quality of an itinerary's longest leg by elapsed time. Thresholds come from configuration;
 * the defaults match the brief: 30h excellent, 36h acceptable, 42h long, beyond that very long.
 */
public enum DurationBand {

    EXCELLENT,
    ACCEPTABLE,
    LONG,
    VERY_LONG,

    /** Beyond the absolute cap — such offers are rejected outright, not merely down-ranked. */
    REJECTED,

    /** The source did not disclose a duration. */
    UNKNOWN;

    public static DurationBand classify(
            Duration longestLeg,
            Duration excellentMax,
            Duration acceptableMax,
            Duration longMax,
            Duration absoluteMax) {
        if (longestLeg == null) {
            return UNKNOWN;
        }
        if (longestLeg.compareTo(absoluteMax) > 0) {
            return REJECTED;
        }
        if (longestLeg.compareTo(excellentMax) <= 0) {
            return EXCELLENT;
        }
        if (longestLeg.compareTo(acceptableMax) <= 0) {
            return ACCEPTABLE;
        }
        if (longestLeg.compareTo(longMax) <= 0) {
            return LONG;
        }
        return VERY_LONG;
    }
}

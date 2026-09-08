package com.flightmonitor.domain.rule;

import com.flightmonitor.config.TripConfig;
import com.flightmonitor.domain.model.DurationBand;
import com.flightmonitor.domain.model.Itinerary;
import com.flightmonitor.domain.model.Rejection;
import com.flightmonitor.domain.model.RejectionReason;
import java.time.Duration;
import java.util.List;

/**
 * Bands an itinerary by the elapsed time of its longest leg and rejects only what exceeds the
 * absolute cap. A 40-hour leg is kept and ranked poorly, which is what the brief asked for: long
 * itineraries are unattractive, not invalid.
 */
public final class DurationRule {

    private final TripConfig trip;

    public DurationRule(TripConfig trip) {
        this.trip = trip;
    }

    public DurationBand band(Itinerary itinerary) {
        return DurationBand.classify(
                itinerary.longestLegDuration(),
                trip.excellentDuration(),
                trip.preferredDuration(),
                trip.longDuration(),
                trip.absoluteDuration());
    }

    public List<Rejection> check(Itinerary itinerary) {
        Duration longest = itinerary.longestLegDuration();
        if (longest == null) {
            // Unknown duration is not fatal: it is scored as the worst case and flagged in the UI.
            return List.of();
        }
        if (longest.compareTo(trip.absoluteDuration()) > 0) {
            return List.of(Rejection.of(
                    RejectionReason.MAX_DURATION_EXCEEDED,
                    "duration=" + format(longest) + " limit=" + trip.maxAbsoluteDurationHours() + "h"));
        }
        return List.of();
    }

    public static String format(Duration duration) {
        if (duration == null) {
            return "unknown";
        }
        long hours = duration.toHours();
        long minutes = duration.toMinutesPart();
        return minutes == 0 ? hours + "h" : hours + "h " + minutes + "min";
    }
}

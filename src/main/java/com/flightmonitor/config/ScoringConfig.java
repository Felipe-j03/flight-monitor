package com.flightmonitor.config;

/**
 * Weights for the ranking score. Each component is normalised to 0..1 by the scorer and then
 * multiplied by its weight here, so the weights are directly comparable and the total is simply
 * their sum. Defaults sum to 100, which makes the resulting score readable as a percentage.
 *
 * <p>Rationale for the defaults: price is what the user is monitoring for, so it dominates;
 * duration matters nearly as much because a cheap 45-hour itinerary was explicitly called out as
 * uninteresting; stops are largely a proxy for duration and risk, so they get less; baggage and
 * date proximity are tie-breakers.
 */
public record ScoringConfig(
        double priceWeight,
        double durationWeight,
        double stopsWeight,
        double baggageWeight,
        double dateWeight,
        int maxStopsForScoring) {

    public ScoringConfig {
        maxStopsForScoring = maxStopsForScoring <= 0 ? 4 : maxStopsForScoring;
    }

    public double total() {
        return priceWeight + durationWeight + stopsWeight + baggageWeight + dateWeight;
    }
}

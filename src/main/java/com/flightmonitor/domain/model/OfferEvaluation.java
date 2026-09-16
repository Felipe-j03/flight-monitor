package com.flightmonitor.domain.model;

import java.util.List;
import java.util.Map;

/**
 * The verdict on one itinerary: whether it survives the filters, how it bands, how it scores and
 * anything the user should be told about it (an alternative arrival airport, a tight return).
 *
 * <p>Rejected offers are still produced — they are logged and counted, they just never reach the
 * ranking or an alert.
 *
 * @param alternative the itinerary uses an alternative airport — landing somewhere other than the
 *                    intended destination, or flying home from somewhere other than planned. Such
 *                    offers only alert when clearly cheaper than the best primary one
 */
public record OfferEvaluation(
        Itinerary itinerary,
        boolean accepted,
        List<Rejection> rejections,
        PriceBand priceBand,
        DurationBand durationBand,
        double score,
        Map<String, Double> scoreBreakdown,
        List<String> warnings,
        boolean alternative) {

    public OfferEvaluation {
        rejections = rejections == null ? List.of() : List.copyOf(rejections);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
        scoreBreakdown = scoreBreakdown == null ? Map.of() : Map.copyOf(scoreBreakdown);
    }

    public static OfferEvaluation rejected(Itinerary itinerary, List<Rejection> rejections) {
        return new OfferEvaluation(
                itinerary, false, rejections, null, null, 0.0, Map.of(), List.of(), false);
    }

    public String rejectionSummary() {
        return rejections.stream().map(Rejection::toString).reduce((a, b) -> a + ", " + b).orElse("");
    }

    public boolean isWithinBudget() {
        return priceBand == PriceBand.EXCELLENT || priceBand == PriceBand.WITHIN_BUDGET;
    }
}

package com.flightmonitor.config;

import java.math.BigDecimal;

/**
 * Anti-spam thresholds. An alert fires only when a drop clears at least one of the two thresholds
 * and the itinerary is outside its cooldown window.
 *
 * @param minPriceDropGbp     absolute drop in GBP that justifies a message
 * @param minPriceDropPercent percentage drop that justifies a message
 * @param cooldownHours       minimum gap between two alerts for the same itinerary
 * @param notifyAboveBudget   whether price drops on over-budget itineraries are worth a message
 * @param notifyFirstSighting whether a brand new itinerary already inside budget is announced
 * @param maxAlertsPerRun     hard ceiling so one search cannot flood the chat
 * @param alternativeDestinationMinSavingGbp
 *        how much cheaper an offer landing at a non-primary airport must be than the best known
 *        primary-airport price before it is worth a message. An alternative airport means a
 *        connecting flight, a bus or a night in another city, so "slightly cheaper" is not a
 *        reason to interrupt anyone — only a real saving is. Offers below this bar are still
 *        stored and visible in the dashboard and the API; they just stay quiet.
 */
public record AlertConfig(
        BigDecimal minPriceDropGbp,
        BigDecimal minPriceDropPercent,
        int cooldownHours,
        boolean notifyAboveBudget,
        boolean notifyFirstSighting,
        int maxAlertsPerRun,
        BigDecimal alternativeDestinationMinSavingGbp) {

    public AlertConfig {
        maxAlertsPerRun = maxAlertsPerRun <= 0 ? 5 : maxAlertsPerRun;
        alternativeDestinationMinSavingGbp = alternativeDestinationMinSavingGbp == null
                ? new BigDecimal("150")
                : alternativeDestinationMinSavingGbp;
    }
}

package com.flightmonitor.application;

import com.flightmonitor.config.AlertConfig;
import com.flightmonitor.config.TripConfig;
import com.flightmonitor.domain.model.OfferEvaluation;
import com.flightmonitor.domain.model.PriceBand;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.Optional;

/**
 * Decides whether an observation deserves a Telegram message. Pure — no clock, no database, no
 * network — so every anti-spam rule is directly unit-testable.
 *
 * <p>The guiding rule is that a message must carry news. A £1,450 to £1,449 move is not news, so a
 * drop must clear an absolute threshold <em>or</em> a percentage threshold before it qualifies. On
 * top of that a cooldown stops the same itinerary from talking repeatedly.
 *
 * <p>Two cases bypass the cooldown, because a cooldown that hides them would defeat the point of
 * the monitor: a genuine new all-time low, and a price crossing below the budget floor for the
 * first time. Both still have to clear the drop thresholds, so neither can fire on noise.
 */
public final class AlertDecider {

    private final AlertConfig config;
    private final TripConfig trip;

    public AlertDecider(AlertConfig config, TripConfig trip) {
        this.config = config;
        this.trip = trip;
    }

    /** Convenience overload for trips with no alternative airports in play. */
    public Optional<AlertType> decide(
            OfferEvaluation evaluation,
            PriceChange change,
            OffsetDateTime lastAlertedAt,
            OffsetDateTime now) {
        return decide(evaluation, change, lastAlertedAt, now, null);
    }

    /**
     * @param bestPrimaryPriceGbp cheapest known price landing at a primary airport, or null when
     *                            none has been seen yet. Used only to judge whether an alternative
     *                            airport is cheap enough to be worth mentioning.
     */
    public Optional<AlertType> decide(
            OfferEvaluation evaluation,
            PriceChange change,
            OffsetDateTime lastAlertedAt,
            OffsetDateTime now,
            BigDecimal bestPrimaryPriceGbp) {

        if (!evaluation.accepted()) {
            return Optional.empty();
        }
        if (evaluation.priceBand() == PriceBand.ABOVE_BUDGET && !config.notifyAboveBudget()) {
            return Optional.empty();
        }
        if (!alternativeAirportIsWorthIt(evaluation, bestPrimaryPriceGbp)) {
            return Optional.empty();
        }

        if (change.firstSighting()) {
            boolean worthAnnouncing = config.notifyFirstSighting() && evaluation.isWithinBudget();
            return worthAnnouncing
                    ? Optional.of(AlertType.NEW_OFFER_WITHIN_BUDGET)
                    : Optional.empty();
        }

        if (change.newAllTimeLow()
                && isMeaningful(change.previousLowestGbp(), change.currentGbp())) {
            return Optional.of(AlertType.NEW_ALL_TIME_LOW);
        }

        if (crossedIntoExcellent(evaluation, change)
                && isMeaningful(change.previousGbp(), change.currentGbp())) {
            return Optional.of(AlertType.EXCELLENT_PRICE);
        }

        if (inCooldown(lastAlertedAt, now)) {
            return Optional.empty();
        }

        if (change.isDrop() && isMeaningful(change.previousGbp(), change.currentGbp())) {
            return Optional.of(AlertType.PRICE_DROP);
        }

        return Optional.empty();
    }

    /**
     * Landing somewhere other than the intended city costs a connecting flight, a bus, or a night
     * elsewhere. That is only worth hearing about for a substantial saving, so an alternative
     * airport has to beat the best known primary-airport price by the configured margin.
     *
     * <p>With no primary price on record yet there is nothing to call it a bargain against, so it
     * stays quiet rather than being announced on an unproven claim. It is still stored and shows up
     * in the dashboard and the API.
     */
    private boolean alternativeAirportIsWorthIt(
            OfferEvaluation evaluation, BigDecimal bestPrimaryPriceGbp) {

        if (!trip.isAlternativeDestination(evaluation.itinerary().destinationAirport())) {
            return true;
        }
        if (bestPrimaryPriceGbp == null) {
            return false;
        }
        BigDecimal saving = bestPrimaryPriceGbp.subtract(evaluation.itinerary().price().gbpAmount());
        return saving.compareTo(config.alternativeDestinationMinSavingGbp()) >= 0;
    }

    /**
     * True only on the observation where the price first lands below the budget floor. A price that
     * was already excellent last time is not news again, so the previous value must have been at or
     * above the floor.
     */
    private boolean crossedIntoExcellent(OfferEvaluation evaluation, PriceChange change) {
        if (evaluation.priceBand() != PriceBand.EXCELLENT || change.previousGbp() == null) {
            return false;
        }
        return change.previousGbp().compareTo(trip.targetMinPriceGbp()) >= 0;
    }

    /** A move counts when it clears either the absolute or the percentage threshold. */
    public boolean isMeaningful(BigDecimal from, BigDecimal to) {
        if (from == null || to == null) {
            return false;
        }
        BigDecimal drop = from.subtract(to);
        if (drop.signum() <= 0) {
            return false;
        }
        if (drop.compareTo(config.minPriceDropGbp()) >= 0) {
            return true;
        }
        if (from.signum() == 0) {
            return false;
        }
        BigDecimal percent = drop
                .multiply(BigDecimal.valueOf(100))
                .divide(from, 2, RoundingMode.HALF_UP);
        return percent.compareTo(config.minPriceDropPercent()) >= 0;
    }

    public boolean inCooldown(OffsetDateTime lastAlertedAt, OffsetDateTime now) {
        if (lastAlertedAt == null) {
            return false;
        }
        return lastAlertedAt.plusHours(config.cooldownHours()).isAfter(now);
    }
}

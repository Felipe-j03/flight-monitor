package com.flightmonitor.domain.scoring;

import com.flightmonitor.config.ScoringConfig;
import com.flightmonitor.config.TripConfig;
import com.flightmonitor.domain.model.BaggageAllowance;
import com.flightmonitor.domain.model.Itinerary;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Turns an accepted itinerary into a single comparable number.
 *
 * <p>Every component is normalised to 0..1 and then multiplied by its configured weight, so the
 * weights are directly comparable and the total is their weighted sum (100 with the defaults).
 * Deliberately simple arithmetic — the goal is a useful ordering, not a model.
 *
 * <table>
 *   <caption>Components</caption>
 *   <tr><td>price</td><td>1.0 at 70% of the budget floor, 0.0 at 150% of the budget ceiling</td></tr>
 *   <tr><td>duration</td><td>1.0 at or below 20h on the longest leg, 0.0 at the absolute cap</td></tr>
 *   <tr><td>stops</td><td>1.0 for non-stop, falling linearly to 0.0 at the configured maximum</td></tr>
 *   <tr><td>baggage</td><td>1.0 checked included, 0.6 cabin only, 0.3 undisclosed, 0.0 nothing</td></tr>
 *   <tr><td>date</td><td>1.0 on the target date, falling across the flex window; a return that
 *       lands inside the safety margin keeps only 80% of this component</td></tr>
 * </table>
 *
 * <p>An itinerary that fails the safety rule never reaches this class — it is rejected upstream and
 * scores nothing at all, which is the "0 pontos / rejeitada" behaviour the brief asked for.
 */
public final class OfferScorer {

    /** Below this, extra speed stops being worth extra points. */
    private static final Duration DURATION_FLOOR = Duration.ofHours(20);

    private static final BigDecimal PRICE_FLOOR_FACTOR = new BigDecimal("0.70");
    private static final BigDecimal PRICE_CEIL_FACTOR = new BigDecimal("1.50");

    /** How far from a preferred departure time the time-of-day credit runs out. */
    private static final double TIME_PREFERENCE_SPAN_MINUTES = 360.0;

    private final ScoringConfig weights;

    public OfferScorer(ScoringConfig weights) {
        this.weights = weights;
    }

    public Result score(Itinerary itinerary, TripConfig trip, boolean returnInsideSafetyMargin) {
        Map<String, Double> breakdown = new LinkedHashMap<>();

        double price = priceComponent(itinerary.price().gbpAmount(), trip);
        double duration = durationComponent(itinerary.longestLegDuration(), trip);
        double stops = stopsComponent(itinerary);
        double baggage = baggageComponent(itinerary.baggage());
        double date = dateComponent(itinerary, trip, returnInsideSafetyMargin);

        breakdown.put("price", round(price * weights.priceWeight()));
        breakdown.put("duration", round(duration * weights.durationWeight()));
        breakdown.put("stops", round(stops * weights.stopsWeight()));
        breakdown.put("baggage", round(baggage * weights.baggageWeight()));
        breakdown.put("date", round(date * weights.dateWeight()));

        double total = breakdown.values().stream().mapToDouble(Double::doubleValue).sum();
        return new Result(round(total), breakdown);
    }

    private double priceComponent(BigDecimal gbp, TripConfig trip) {
        double floor = trip.targetMinPriceGbp().multiply(PRICE_FLOOR_FACTOR).doubleValue();
        double ceiling = trip.targetMaxPriceGbp().multiply(PRICE_CEIL_FACTOR).doubleValue();
        return normalizeDescending(gbp.doubleValue(), floor, ceiling);
    }

    private double durationComponent(Duration longestLeg, TripConfig trip) {
        if (longestLeg == null) {
            return 0.0;
        }
        // The floor is capped by the trip's own "excellent" threshold. A fixed 20h floor made every
        // domestic itinerary score perfectly, so a 2h non-stop and a 9h double-connection looked the
        // same. Long-haul trips have excellent >= 20h, so their scoring is unchanged.
        double floor = Math.min(DURATION_FLOOR.toMinutes(), trip.excellentDuration().toMinutes());
        double ceiling = trip.absoluteDuration().toMinutes();
        return normalizeDescending(longestLeg.toMinutes(), floor, ceiling);
    }

    /** Uses the worse of the two legs, so a non-stop outbound cannot hide a three-stop return. */
    private double stopsComponent(Itinerary itinerary) {
        int worstLeg = itinerary.outbound().stops();
        if (itinerary.inbound() != null) {
            worstLeg = Math.max(worstLeg, itinerary.inbound().stops());
        }
        double max = weights.maxStopsForScoring();
        return clamp(1.0 - (Math.min(worstLeg, max) / max));
    }

    private double baggageComponent(BaggageAllowance baggage) {
        if (!baggage.known()) {
            return 0.3;
        }
        if (baggage.hasIncludedCheckedBag()) {
            return 1.0;
        }
        if (baggage.hasIncludedCabinBag()) {
            return 0.6;
        }
        return 0.0;
    }

    private double dateComponent(
            Itinerary itinerary, TripConfig trip, boolean returnInsideSafetyMargin) {
        OffsetDateTime departure = itinerary.departureAt();
        if (departure == null) {
            return 0.0;
        }
        long daysOff = Math.abs(ChronoUnit.DAYS.between(
                trip.targetDepartureDate(), departure.toLocalDate()));
        double window = trip.departureFlexDays() + 1.0;

        // Date closeness, plus closeness to any preferred departure time, averaged. With no time
        // preference configured this is exactly the old date-only score.
        double total = clamp(1.0 - (daysOff / window));
        int parts = 1;
        if (trip.outboundPreferredDeparture() != null) {
            total += timeCloseness(departure.toLocalTime(), trip.outboundPreferredDeparture());
            parts++;
        }
        if (trip.returnPreferredDeparture() != null && itinerary.returnDepartureAt() != null) {
            total += timeCloseness(
                    itinerary.returnDepartureAt().toLocalTime(), trip.returnPreferredDeparture());
            parts++;
        }
        double base = total / parts;
        return returnInsideSafetyMargin ? base * 0.8 : base;
    }

    /** 1.0 on the preferred time, falling linearly to 0.0 six hours away. */
    private static double timeCloseness(LocalTime actual, LocalTime preferred) {
        long minutesOff = Math.abs(ChronoUnit.MINUTES.between(preferred, actual));
        return clamp(1.0 - minutesOff / TIME_PREFERENCE_SPAN_MINUTES);
    }

    /** 1.0 at or below {@code best}, 0.0 at or above {@code worst}, linear in between. */
    private static double normalizeDescending(double value, double best, double worst) {
        if (worst <= best) {
            return value <= best ? 1.0 : 0.0;
        }
        return clamp((worst - value) / (worst - best));
    }

    private static double clamp(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }

    private static double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    public record Result(double total, Map<String, Double> breakdown) {}
}

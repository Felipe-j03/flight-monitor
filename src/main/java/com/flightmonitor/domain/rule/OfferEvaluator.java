package com.flightmonitor.domain.rule;

import com.flightmonitor.config.SafetyConfig;
import com.flightmonitor.config.ScoringConfig;
import com.flightmonitor.config.TripConfig;
import com.flightmonitor.domain.model.DurationBand;
import com.flightmonitor.domain.model.Itinerary;
import com.flightmonitor.domain.model.OfferEvaluation;
import com.flightmonitor.domain.model.PriceBand;
import com.flightmonitor.domain.model.Rejection;
import com.flightmonitor.domain.model.RejectionReason;
import com.flightmonitor.domain.port.AirportCatalog;
import com.flightmonitor.domain.scoring.OfferScorer;
import java.util.ArrayList;
import java.util.List;

/**
 * Runs every rule against one itinerary and produces the single verdict the rest of the
 * application works with. Pure: no I/O, no clock, no persistence — which is why the whole rule set
 * is covered by fast unit tests.
 *
 * <p>Order matters only for readability; all rules run so that a rejected offer can be logged with
 * every reason it failed rather than just the first.
 */
public final class OfferEvaluator {

    private final TripConfig trip;
    private final SafetyRule safetyRule;
    private final DateWindowRule dateRule;
    private final DurationRule durationRule;
    private final OfferScorer scorer;

    public OfferEvaluator(
            TripConfig trip,
            SafetyConfig safety,
            ScoringConfig scoring,
            AirportCatalog catalog) {
        this.trip = trip;
        this.safetyRule = new SafetyRule(safety, catalog);
        this.dateRule = new DateWindowRule(trip);
        this.durationRule = new DurationRule(trip);
        this.scorer = new OfferScorer(scoring);
    }

    public OfferEvaluation evaluate(Itinerary itinerary) {
        List<Rejection> rejections = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        rejections.addAll(safetyRule.check(itinerary));

        DateWindowRule.Result dates = dateRule.check(itinerary);
        rejections.addAll(dates.rejections());
        warnings.addAll(dates.warnings());

        rejections.addAll(durationRule.check(itinerary));

        // A hard ceiling: above it the offer is not "over budget", it is out of the question.
        if (trip.discardAboveBudget()
                && itinerary.price().gbpAmount().compareTo(trip.targetMaxPriceGbp()) > 0) {
            rejections.add(Rejection.of(RejectionReason.PRICE_ABOVE_LIMIT,
                    "price=GBP" + itinerary.price().gbpAmount()
                            + " limit=GBP" + trip.targetMaxPriceGbp()));
        }

        if (!rejections.isEmpty()) {
            return OfferEvaluation.rejected(itinerary, rejections);
        }

        boolean alternative = false;
        if (trip.isAlternativeDestination(itinerary.destinationAirport())) {
            alternative = true;
            warnings.add("Alternative arrival airport: " + itinerary.destinationAirport()
                    + " (primary: " + String.join("/", trip.primaryDestinations()) + ")");
        }
        String returnOrigin = itinerary.inbound() == null ? null : itinerary.inbound().originAirport();
        if (trip.isAlternativeReturnOrigin(returnOrigin)) {
            alternative = true;
            warnings.add("Alternative return: flies home from " + returnOrigin
                    + " (primary: " + String.join("/", trip.returnOriginAirports()) + ")");
        }
        if (!itinerary.baggage().known()) {
            warnings.add("Baggage information unavailable");
        }
        if (itinerary.bookingUrl() == null) {
            warnings.add("No direct booking link from " + itinerary.source());
        }

        PriceBand priceBand = PriceBand.classify(
                itinerary.price().gbpAmount(), trip.targetMinPriceGbp(), trip.targetMaxPriceGbp());
        DurationBand durationBand = durationRule.band(itinerary);

        boolean tightReturn = warnings.stream().anyMatch(w -> w.contains("safety margin"));
        OfferScorer.Result score = scorer.score(itinerary, trip, tightReturn);

        return new OfferEvaluation(
                itinerary,
                true,
                List.of(),
                priceBand,
                durationBand,
                score.total(),
                score.breakdown(),
                warnings,
                alternative);
    }

    public TripConfig trip() {
        return trip;
    }
}

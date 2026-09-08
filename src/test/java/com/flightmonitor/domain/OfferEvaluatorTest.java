package com.flightmonitor.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.flightmonitor.TestFixtures;
import com.flightmonitor.domain.model.DurationBand;
import com.flightmonitor.domain.model.Itinerary;
import com.flightmonitor.domain.model.OfferEvaluation;
import com.flightmonitor.domain.model.PriceBand;
import com.flightmonitor.domain.model.RejectionReason;
import com.flightmonitor.domain.rule.OfferEvaluator;
import com.flightmonitor.infrastructure.catalog.CsvAirportCatalog;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Price banding, duration banding and the ranking, exercised through the composed evaluator. */
class OfferEvaluatorTest {

    private final OfferEvaluator evaluator = new OfferEvaluator(
            TestFixtures.trip(),
            TestFixtures.safety(),
            TestFixtures.scoring(),
            new CsvAirportCatalog());

    @ParameterizedTest(name = "GBP {0} is {1}")
    @CsvSource({
            "1250, EXCELLENT",
            "1299.99, EXCELLENT",
            "1300, WITHIN_BUDGET",
            "1400, WITHIN_BUDGET",
            "1500, WITHIN_BUDGET",
            "1600, ABOVE_BUDGET"
    })
    @DisplayName("classifies price against the budget window")
    void classifiesPrice(String amount, PriceBand expected) {
        Itinerary itinerary = TestFixtures.acceptableItinerary().priceGbp(amount).build();

        assertThat(evaluator.evaluate(itinerary).priceBand()).isEqualTo(expected);
    }

    @ParameterizedTest(name = "a {0}h leg is {1}")
    @CsvSource({
            "25, EXCELLENT",
            "30, EXCELLENT",
            "35, ACCEPTABLE",
            "36, ACCEPTABLE",
            "40, LONG",
            "45, VERY_LONG"
    })
    @DisplayName("bands duration by the longest leg without rejecting under the absolute cap")
    void classifiesDuration(int hours, DurationBand expected) {
        Itinerary itinerary = TestFixtures.acceptableItinerary()
                .bothLegsDuration(Duration.ofHours(hours))
                .build();

        OfferEvaluation evaluation = evaluator.evaluate(itinerary);

        assertThat(evaluation.accepted()).isTrue();
        assertThat(evaluation.durationBand()).isEqualTo(expected);
    }

    @Test
    @DisplayName("rejects a leg beyond the absolute cap")
    void rejectsBeyondAbsoluteCap() {
        Itinerary marathon = TestFixtures.acceptableItinerary()
                .bothLegsDuration(Duration.ofHours(50))
                .build();

        OfferEvaluation evaluation = evaluator.evaluate(marathon);

        assertThat(evaluation.accepted()).isFalse();
        assertThat(evaluation.rejections())
                .extracting(rejection -> rejection.reason())
                .contains(RejectionReason.MAX_DURATION_EXCEEDED);
        assertThat(evaluation.score()).isZero();
    }

    @Test
    @DisplayName("a blocked route scores nothing at all, whatever it costs")
    void blockedRouteScoresZero() {
        Itinerary cheapButBlocked = TestFixtures.acceptableItinerary()
                .connectingVia("DOH", TestFixtures.QATAR)
                .priceGbp("900")
                .build();

        OfferEvaluation evaluation = evaluator.evaluate(cheapButBlocked);

        assertThat(evaluation.accepted()).isFalse();
        assertThat(evaluation.score()).isZero();
        assertThat(evaluation.priceBand()).isNull();
    }

    @Test
    @DisplayName("ranks a pricier, faster, single-connection trip above a cheap four-stop marathon")
    void ranksQualityAbovePriceAlone() {
        // The example from the brief: GBP 1400 / 1 stop / 25h should beat GBP 1250 / 4 stops / 45h.
        Itinerary good = TestFixtures.acceptableItinerary()
                .priceGbp("1400")
                .bothLegsDuration(Duration.ofHours(25))
                .build();
        Itinerary cheapButAwful = TestFixtures.acceptableItinerary()
                .priceGbp("1250")
                .bothLegsDuration(Duration.ofHours(45))
                .declaredStops(4)
                .build();

        double goodScore = evaluator.evaluate(good).score();
        double awfulScore = evaluator.evaluate(cheapButAwful).score();

        assertThat(goodScore).isGreaterThan(awfulScore);
    }

    @Test
    @DisplayName("an included checked bag ranks above an identical fare with none")
    void baggageImprovesRanking() {
        Itinerary withBag = TestFixtures.acceptableItinerary().build();
        Itinerary withoutBag = TestFixtures.acceptableItinerary()
                .baggage(new com.flightmonitor.domain.model.BaggageAllowance(
                        true, null, false, 0, null, "no checked bag"))
                .build();

        assertThat(evaluator.evaluate(withBag).score())
                .isGreaterThan(evaluator.evaluate(withoutBag).score());
    }

    @Test
    @DisplayName("departing on the target date ranks above departing at the edge of the window")
    void dateProximityImprovesRanking() {
        Itinerary onTarget = TestFixtures.acceptableItinerary().build();
        Itinerary twoDaysOff = TestFixtures.acceptableItinerary()
                .departingAt(java.time.OffsetDateTime.of(
                        2027, 1, 11, 9, 55, 0, 0, TestFixtures.JAPAN))
                .build();

        assertThat(evaluator.evaluate(onTarget).score())
                .isGreaterThan(evaluator.evaluate(twoDaysOff).score());
    }

    @Test
    @DisplayName("flags an alternative arrival airport instead of hiding it")
    void flagsAlternativeAirport() {
        Itinerary florianopolis = TestFixtures.acceptableItinerary()
                .destination("FLN")
                .build();

        OfferEvaluation evaluation = evaluator.evaluate(florianopolis);

        assertThat(evaluation.accepted()).isTrue();
        assertThat(evaluation.warnings())
                .anyMatch(warning -> warning.contains("Alternative arrival airport: FLN"));
    }

    @Test
    @DisplayName("says so when baggage was never disclosed")
    void flagsUnknownBaggage() {
        Itinerary noBaggageInfo = TestFixtures.acceptableItinerary()
                .baggage(com.flightmonitor.domain.model.BaggageAllowance.unknown())
                .build();

        assertThat(evaluator.evaluate(noBaggageInfo).warnings())
                .contains("Baggage information unavailable");
    }
}

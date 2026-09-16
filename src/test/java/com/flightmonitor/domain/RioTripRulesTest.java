package com.flightmonitor.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.flightmonitor.TestFixtures;
import com.flightmonitor.config.TripConfig;
import com.flightmonitor.domain.model.FlightSegment;
import com.flightmonitor.domain.model.Itinerary;
import com.flightmonitor.domain.model.ItineraryLeg;
import com.flightmonitor.domain.model.OfferEvaluation;
import com.flightmonitor.domain.model.PriceBand;
import com.flightmonitor.domain.model.RejectionReason;
import com.flightmonitor.domain.model.RouteDetailLevel;
import com.flightmonitor.domain.rule.OfferEvaluator;
import com.flightmonitor.infrastructure.catalog.CsvAirportCatalog;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The Rio trip exercises everything the Tokyo trip never needed: a budget in reais, a morning
 * departure window, preferred times on both legs, and two destination airports treated as one.
 */
class RioTripRulesTest {

    /** Today's live rate when this was written (R$ 1 = £0.14442). */
    private static final BigDecimal BRL_TO_GBP = new BigDecimal("0.14442");

    private final TripConfig rio = TestFixtures.rioTrip();
    private final OfferEvaluator evaluator = new OfferEvaluator(
            rio.withBudgetConvertedToGbp(BRL_TO_GBP),
            TestFixtures.safety(),
            TestFixtures.scoring(),
            new CsvAirportCatalog());

    /** A direct POA→Rio round trip leaving and returning at the given local times. */
    private static Itinerary roundTrip(
            String destination, int outHour, int outMinute, int backHour, int backMinute, String brl) {
        OffsetDateTime leaves = OffsetDateTime.of(2027, 1, 11, outHour, outMinute, 0, 0,
                TestFixtures.BRAZIL);
        OffsetDateTime returns = OffsetDateTime.of(2027, 1, 13, backHour, backMinute, 0, 0,
                TestFixtures.BRAZIL);
        BigDecimal gbp = new BigDecimal(brl).multiply(BRL_TO_GBP);

        return Itinerary.builder()
                .tripId("poa-rio-2027")
                .source("test")
                .observedAt(Instant.parse("2026-09-16T12:00:00Z"))
                .outbound(ItineraryLeg.of(List.of(new FlightSegment(
                        "POA", leaves, destination, leaves.plusMinutes(115),
                        Duration.ofMinutes(115), "Gol", null, "G3 1000", null, "Economy")), List.of()))
                .inbound(ItineraryLeg.of(List.of(new FlightSegment(
                        destination, returns, "POA", returns.plusMinutes(130),
                        Duration.ofMinutes(130), "Gol", null, "G3 1001", null, "Economy")), List.of()))
                .price(new com.flightmonitor.domain.model.PriceQuote(
                        com.flightmonitor.domain.model.Money.of(brl, "BRL"), gbp, BRL_TO_GBP,
                        Instant.parse("2026-09-16T12:00:00Z")))
                .detailLevel(RouteDetailLevel.FULL_ITINERARY)
                .build();
    }

    @Test
    @DisplayName("the Gol 05:10 direct found today is accepted")
    void acceptsTheEarlyDirectFlight() {
        OfferEvaluation evaluation = evaluator.evaluate(roundTrip("GIG", 5, 10, 9, 40, "1021"));

        assertThat(evaluation.accepted()).isTrue();
        assertThat(evaluation.warnings())
                .as("GIG is a primary destination here, not an alternative")
                .noneMatch(warning -> warning.contains("Alternative arrival airport"));
    }

    @Test
    @DisplayName("an afternoon outbound is rejected by the morning window")
    void rejectsAfternoonOutbound() {
        OfferEvaluation evaluation = evaluator.evaluate(roundTrip("GIG", 14, 35, 11, 0, "600"));

        assertThat(evaluation.accepted()).isFalse();
        assertThat(evaluation.rejections())
                .extracting(rejection -> rejection.reason())
                .contains(RejectionReason.DEPARTURE_TIME_OUTSIDE_WINDOW);
    }

    @Test
    @DisplayName("11:59 is still morning; 12:00 is not")
    void windowEdges() {
        assertThat(evaluator.evaluate(roundTrip("SDU", 11, 59, 11, 0, "700")).accepted()).isTrue();
        assertThat(evaluator.evaluate(roundTrip("SDU", 12, 0, 11, 0, "700")).accepted()).isFalse();
    }

    @Test
    @DisplayName("SDU is accepted as a primary destination alongside GIG")
    void sduIsPrimary() {
        assertThat(rio.isAlternativeDestination("SDU")).isFalse();
        assertThat(rio.isAlternativeDestination("GIG,SDU")).isFalse();
        assertThat(rio.isAlternativeDestination("GRU")).isTrue();
    }

    @Test
    @DisplayName("prices are banded against the budget in reais")
    void bandsAgainstReais() {
        assertThat(evaluator.evaluate(roundTrip("GIG", 8, 25, 11, 0, "600")).priceBand())
                .isEqualTo(PriceBand.EXCELLENT);
        assertThat(evaluator.evaluate(roundTrip("GIG", 8, 25, 11, 0, "711")).priceBand())
                .as("R$ 711 sits inside R$ 650-800")
                .isEqualTo(PriceBand.WITHIN_BUDGET);
        assertThat(evaluator.evaluate(roundTrip("GIG", 5, 10, 9, 40, "1021")).priceBand())
                .isEqualTo(PriceBand.ABOVE_BUDGET);
    }

    @Test
    @DisplayName("at the same price, the earlier outbound ranks higher")
    void earlierOutboundRanksHigher() {
        double fiveAm = evaluator.evaluate(roundTrip("GIG", 5, 10, 11, 0, "750")).score();
        double eightAm = evaluator.evaluate(roundTrip("GIG", 8, 25, 11, 0, "750")).score();

        assertThat(fiveAm).isGreaterThan(eightAm);
    }

    @Test
    @DisplayName("at the same price, a return leaving near 11:00 ranks higher than one at 21:15")
    void returnNearElevenRanksHigher() {
        double nearEleven = evaluator.evaluate(roundTrip("GIG", 5, 10, 9, 40, "750")).score();
        double lateNight = evaluator.evaluate(roundTrip("GIG", 5, 10, 21, 15, "750")).score();

        assertThat(nearEleven).isGreaterThan(lateNight);
    }

    @Test
    @DisplayName("a return landing after midnight on the 13th misses the same-day deadline")
    void sameDayDeadline() {
        OfferEvaluation evaluation = evaluator.evaluate(roundTrip("GIG", 5, 10, 22, 30, "700"));

        assertThat(evaluation.accepted()).isFalse();
        assertThat(evaluation.rejections())
                .extracting(rejection -> rejection.reason())
                .contains(RejectionReason.RETURN_ARRIVAL_TOO_LATE);
    }

    @Test
    @DisplayName("a 2h direct flight scores better on duration than a 5h connection")
    void domesticDurationsAreMeaningful() {
        OfferEvaluation direct = evaluator.evaluate(roundTrip("GIG", 8, 25, 11, 0, "750"));
        double directDuration = direct.scoreBreakdown().get("duration");

        assertThat(directDuration)
                .as("the 20h long-haul floor used to make every domestic flight score perfectly")
                .isGreaterThan(0);
        assertThat(evaluator.evaluate(roundTrip("GIG", 8, 25, 11, 0, "750")).durationBand())
                .isEqualTo(com.flightmonitor.domain.model.DurationBand.EXCELLENT);
    }

    @Test
    @DisplayName("comparing reais against pounds without converting first is refused")
    void refusesUnconvertedBudget() {
        assertThatThrownBy(rio::targetMinPriceGbp)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("BRL");
    }

    @Test
    @DisplayName("the budget converts to GBP with the rate it is given")
    void convertsBudget() {
        TripConfig inGbp = rio.withBudgetConvertedToGbp(BRL_TO_GBP);

        assertThat(inGbp.budgetCurrency()).isEqualTo("GBP");
        assertThat(inGbp.targetMaxPriceGbp()).isEqualByComparingTo("115.54");
        assertThat(inGbp.targetMinPriceGbp()).isEqualByComparingTo("93.87");
    }

    @Test
    @DisplayName("a half-set time window is a configuration error, not a silent no-op")
    void rejectsHalfWindow() {
        assertThatThrownBy(() -> new TripConfig(
                "x", "x", true, List.of("POA"), List.of("GIG"), List.of("GIG"),
                java.time.LocalDate.of(2027, 1, 11), 0,
                java.time.LocalDate.of(2027, 1, 13), java.time.LocalDate.of(2027, 1, 13),
                OffsetDateTime.of(2027, 1, 13, 23, 59, 0, 0, TestFixtures.BRAZIL), 0,
                BigDecimal.ONE, BigDecimal.TEN, "BRL", 4, 6, 8, 12, 1, "ECONOMY",
                java.time.LocalTime.of(0, 0), null, null, null, true, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("set together");
    }
}

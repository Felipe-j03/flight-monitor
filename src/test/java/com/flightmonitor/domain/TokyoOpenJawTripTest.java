package com.flightmonitor.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.flightmonitor.TestFixtures;
import com.flightmonitor.application.AlertDecider;
import com.flightmonitor.application.AlertType;
import com.flightmonitor.application.PriceChange;
import com.flightmonitor.application.QueryPlanner;
import com.flightmonitor.config.AlertConfig;
import com.flightmonitor.config.TripConfig;
import com.flightmonitor.domain.model.OfferEvaluation;
import com.flightmonitor.domain.model.PriceBand;
import com.flightmonitor.domain.model.RejectionReason;
import com.flightmonitor.domain.port.SearchQuery;
import com.flightmonitor.domain.rule.OfferEvaluator;
import com.flightmonitor.infrastructure.catalog.CsvAirportCatalog;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The Tokyo plan as two tickets: a multi-city Tokyo -> Rio / Porto Alegre -> Tokyo, hard-capped at
 * GBP 2,000, and a one-way Rio -> Porto Alegre on 13/01.
 */
class TokyoOpenJawTripTest {

    private static final TripConfig TOKYO = TestFixtures.tokyoOpenJawTrip();
    private static final OffsetDateTime NOW =
            OffsetDateTime.of(2026, 9, 17, 12, 0, 0, 0, ZoneOffset.UTC);

    private static QueryPlanner plannerOn(String date) {
        return new QueryPlanner(Clock.fixed(Instant.parse(date + "T12:00:00Z"), ZoneOffset.UTC));
    }

    private final OfferEvaluator evaluator = new OfferEvaluator(
            TOKYO, TestFixtures.safety(), TestFixtures.scoring(), new CsvAirportCatalog());

    private OfferEvaluation evaluate(String returnFrom, String priceGbp) {
        return evaluator.evaluate(TestFixtures.acceptableItinerary()
                .destination("GIG")
                .returnFrom(returnFrom)
                .returnDepartingAt(OffsetDateTime.of(2027, 1, 19, 6, 10, 0, 0, TestFixtures.BRAZIL))
                .priceGbp(priceGbp)
                .build());
    }

    @Test
    @DisplayName("plans one multi-city query: both Tokyo airports to both Rio airports, home from POA")
    void plansOneOpenJawQuery() {
        // 2026-09-16 is day 259: odd, so the every-other-day variant is not due.
        List<SearchQuery> plan = plannerOn("2026-09-16").plan(TOKYO, 12);

        assertThat(plan).singleElement().satisfies(query -> {
            assertThat(query.isOpenJaw()).isTrue();
            assertThat(query.origin()).isEqualTo("HND,NRT");
            assertThat(query.destination()).isEqualTo("GIG,SDU");
            assertThat(query.departureDate()).isEqualTo(LocalDate.of(2027, 1, 9));
            assertThat(query.returnOrigin()).isEqualTo("POA");
            assertThat(query.returnDestination()).isEqualTo("HND,NRT");
            assertThat(query.returnDate()).isEqualTo(LocalDate.of(2027, 1, 19));
            assertThat(query.maxPrice()).isEqualByComparingTo("2000");
            assertThat(query.currency()).isEqualTo("GBP");
        });
    }

    @Test
    @DisplayName("every other day adds the São Paulo / Rio way home, second in line")
    void addsAlternativeReturnEveryOtherDay() {
        // 2026-09-17 is day 260: even.
        List<SearchQuery> plan = plannerOn("2026-09-17").plan(TOKYO, 12);

        assertThat(plan).hasSize(2);
        assertThat(plan.get(0).returnOrigin()).isEqualTo("POA");
        assertThat(plan.get(1).returnOrigin()).isEqualTo("GRU,GIG,SDU");
        assertThat(plan.get(1).returnDestination()).isEqualTo("HND,NRT");
        assertThat(plan.get(1).priority()).isGreaterThan(plan.get(0).priority());
    }

    @Test
    @DisplayName("an open-jaw ticket inside budget is accepted; under GBP 1,700 is excellent")
    void acceptsOpenJawInBudget() {
        OfferEvaluation evaluation = evaluate("POA", "1608");

        assertThat(evaluation.accepted()).as(evaluation.rejections().toString()).isTrue();
        assertThat(evaluation.alternative()).isFalse();
        assertThat(evaluation.priceBand()).isEqualTo(PriceBand.EXCELLENT);
        assertThat(evaluate("POA", "1850").priceBand()).isEqualTo(PriceBand.WITHIN_BUDGET);
    }

    @Test
    @DisplayName("anything above GBP 2,000 is rejected outright, not merely flagged over budget")
    void rejectsAboveTheCap() {
        OfferEvaluation evaluation = evaluate("POA", "2049");

        assertThat(evaluation.accepted()).isFalse();
        assertThat(evaluation.rejections())
                .anySatisfy(r -> assertThat(r.reason()).isEqualTo(RejectionReason.PRICE_ABOVE_LIMIT));
    }

    @Test
    @DisplayName("flying home from São Paulo is accepted but marked as the alternative")
    void flagsAlternativeReturn() {
        OfferEvaluation evaluation = evaluate("GRU", "1500");

        assertThat(evaluation.accepted()).isTrue();
        assertThat(evaluation.alternative()).isTrue();
        assertThat(evaluation.warnings()).anyMatch(w -> w.contains("flies home from GRU"));
    }

    @Test
    @DisplayName("the alternative way home only alerts when at least GBP 200 cheaper than from POA")
    void alternativeNeedsTwoHundredSaving() {
        AlertDecider decider = new AlertDecider(new AlertConfig(
                new BigDecimal("30"), new BigDecimal("3"), 12, false, true, 5,
                new BigDecimal("200")), TOKYO);
        BigDecimal bestFromPoa = new BigDecimal("1700");

        Optional<AlertType> smallSaving = decider.decide(
                evaluate("GRU", "1550"), change("1550"), null, NOW, bestFromPoa);
        Optional<AlertType> bigSaving = decider.decide(
                evaluate("GRU", "1480"), change("1480"), null, NOW, bestFromPoa);

        assertThat(smallSaving).as("150 cheaper is not worth changing the plan").isEmpty();
        assertThat(bigSaving).as("220 cheaper is").isPresent();
    }

    @Test
    @DisplayName("Rio -> Porto Alegre on 13/01 is a one-way query for both Rio airports, in reais")
    void domesticLegIsOneWay() {
        TripConfig domestic = TestFixtures.rioToPoaOneWay();
        List<SearchQuery> plan = plannerOn("2026-09-17").plan(domestic, 12);

        assertThat(domestic.isOneWay()).isTrue();
        assertThat(plan).singleElement().satisfies(query -> {
            assertThat(query.isRoundTrip()).isFalse();
            assertThat(query.isOpenJaw()).isFalse();
            assertThat(query.origin()).isEqualTo("GIG,SDU");
            assertThat(query.destination()).isEqualTo("POA");
            assertThat(query.departureDate()).isEqualTo(LocalDate.of(2027, 1, 13));
            assertThat(query.currency()).isEqualTo("BRL");
        });
    }

    private static PriceChange change(String current) {
        BigDecimal price = new BigDecimal(current);
        return new PriceChange(price, new BigDecimal("1900"), new BigDecimal("1900"), false, true, 3);
    }
}

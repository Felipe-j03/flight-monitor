package com.flightmonitor.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.flightmonitor.TestFixtures;
import com.flightmonitor.domain.model.OfferEvaluation;
import com.flightmonitor.domain.rule.OfferEvaluator;
import com.flightmonitor.infrastructure.catalog.CsvAirportCatalog;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The anti-spam rules: what earns a message and what is just noise. */
class AlertDeciderTest {

    private static final OffsetDateTime NOW =
            OffsetDateTime.of(2026, 9, 7, 12, 0, 0, 0, ZoneOffset.UTC);

    private final OfferEvaluator evaluator = new OfferEvaluator(
            TestFixtures.trip(),
            TestFixtures.safety(),
            TestFixtures.scoring(),
            new CsvAirportCatalog());

    private final AlertDecider decider =
            new AlertDecider(TestFixtures.alerts(), TestFixtures.trip());

    private OfferEvaluation evaluate(String priceGbp) {
        return evaluator.evaluate(TestFixtures.acceptableItinerary().priceGbp(priceGbp).build());
    }

    private static PriceChange change(
            String current, String previous, String previousLowest, boolean newLow) {
        return new PriceChange(
                new BigDecimal(current),
                previous == null ? null : new BigDecimal(previous),
                previousLowest == null ? null : new BigDecimal(previousLowest),
                false,
                newLow,
                5);
    }

    @Test
    @DisplayName("a GBP 10 drop is noise and stays silent")
    void smallDropIsSilent() {
        Optional<AlertType> decision = decider.decide(
                evaluate("1440"), change("1440", "1450", "1440", false), null, NOW);

        assertThat(decision).isEmpty();
    }

    @Test
    @DisplayName("the brief's example: 1450 to 1449 must not send anything")
    void onePoundDropIsSilent() {
        Optional<AlertType> decision = decider.decide(
                evaluate("1449"), change("1449", "1450", "1449", false), null, NOW);

        assertThat(decision).isEmpty();
    }

    @Test
    @DisplayName("a GBP 50 drop clears the absolute threshold and alerts")
    void meaningfulDropAlerts() {
        Optional<AlertType> decision = decider.decide(
                evaluate("1400"), change("1400", "1450", "1400", false), null, NOW);

        assertThat(decision).contains(AlertType.PRICE_DROP);
    }

    @Test
    @DisplayName("a drop under GBP 30 still alerts when it clears the percentage threshold")
    void percentageThresholdAlerts() {
        // 25 off 500 is 5%, which beats the 3% threshold even though it misses the GBP 30 one.
        Optional<AlertType> decision = decider.decide(
                evaluate("475"), change("475", "500", "475", false), null, NOW);

        assertThat(decision).contains(AlertType.PRICE_DROP);
    }

    @Test
    @DisplayName("a new all-time low is announced")
    void newAllTimeLowAlerts() {
        Optional<AlertType> decision = decider.decide(
                evaluate("1387"), change("1387", "1449", "1449", true), null, NOW);

        assertThat(decision).contains(AlertType.NEW_ALL_TIME_LOW);
    }

    @Test
    @DisplayName("a new all-time low by GBP 1 is still noise and stays silent")
    void trivialNewLowIsSilent() {
        Optional<AlertType> decision = decider.decide(
                evaluate("1448"), change("1448", "1449", "1449", true), null, NOW);

        assertThat(decision).isEmpty();
    }

    @Test
    @DisplayName("crossing below the budget floor is announced even during a cooldown")
    void crossingIntoExcellentBypassesCooldown() {
        Optional<AlertType> decision = decider.decide(
                evaluate("1247"),
                change("1247", "1320", "1247", false),
                NOW.minusHours(1),
                NOW);

        assertThat(decision).contains(AlertType.EXCELLENT_PRICE);
    }

    @Test
    @DisplayName("an ordinary drop inside the cooldown window stays silent")
    void cooldownSuppressesOrdinaryDrop() {
        Optional<AlertType> decision = decider.decide(
                evaluate("1400"),
                change("1400", "1450", "1400", false),
                NOW.minusHours(2),
                NOW);

        assertThat(decision).isEmpty();
    }

    @Test
    @DisplayName("the same drop alerts once the cooldown has expired")
    void cooldownExpires() {
        Optional<AlertType> decision = decider.decide(
                evaluate("1400"),
                change("1400", "1450", "1400", false),
                NOW.minusHours(13),
                NOW);

        assertThat(decision).contains(AlertType.PRICE_DROP);
    }

    @Test
    @DisplayName("a first sighting inside budget is announced but claims no drop")
    void firstSightingWithinBudget() {
        PriceChange firstSighting =
                new PriceChange(new BigDecimal("1400"), null, null, true, false, 1);

        Optional<AlertType> decision = decider.decide(evaluate("1400"), firstSighting, null, NOW);

        assertThat(decision).contains(AlertType.NEW_OFFER_WITHIN_BUDGET);
        assertThat(firstSighting.isDrop()).isFalse();
        assertThat(firstSighting.dropGbp()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("a first sighting above budget stays silent")
    void firstSightingAboveBudgetIsSilent() {
        PriceChange firstSighting =
                new PriceChange(new BigDecimal("1900"), null, null, true, false, 1);

        assertThat(decider.decide(evaluate("1900"), firstSighting, null, NOW)).isEmpty();
    }

    @Test
    @DisplayName("a rejected offer never alerts, however cheap it is")
    void rejectedOfferNeverAlerts() {
        OfferEvaluation blocked = evaluator.evaluate(TestFixtures.acceptableItinerary()
                .connectingVia("DOH", TestFixtures.QATAR)
                .priceGbp("800")
                .build());

        Optional<AlertType> decision =
                decider.decide(blocked, change("800", "1400", "1400", true), null, NOW);

        assertThat(decision).isEmpty();
    }

    private OfferEvaluation evaluateAt(String destination, String priceGbp) {
        return evaluator.evaluate(TestFixtures.acceptableItinerary()
                .destination(destination)
                .priceGbp(priceGbp)
                .build());
    }

    @Test
    @DisplayName("an alternative airport barely cheaper than the primary stays silent")
    void alternativeAirportNeedsARealSaving() {
        // GRU at 1350 against POA at 1400: 50 cheaper is not worth a connecting flight.
        Optional<AlertType> decision = decider.decide(
                evaluateAt("GRU", "1350"),
                change("1350", "1450", "1350", false),
                null,
                NOW,
                new BigDecimal("1400"));

        assertThat(decision).isEmpty();
    }

    @Test
    @DisplayName("an alternative airport that is dramatically cheaper does alert")
    void alternativeAirportWithBigSavingAlerts() {
        // GRU at 1150 against POA at 1400: 250 cheaper clears the 150 bar.
        Optional<AlertType> decision = decider.decide(
                evaluateAt("GRU", "1150"),
                change("1150", "1400", "1400", true),
                null,
                NOW,
                new BigDecimal("1400"));

        assertThat(decision).contains(AlertType.NEW_ALL_TIME_LOW);
    }

    @Test
    @DisplayName("an alternative airport stays silent while no primary price is known")
    void alternativeAirportWithoutBaselineIsSilent() {
        Optional<AlertType> decision = decider.decide(
                evaluateAt("GRU", "1100"),
                change("1100", "1400", "1400", true),
                null,
                NOW,
                null);

        assertThat(decision)
                .as("nothing to call it a bargain against yet, so no claim is made")
                .isEmpty();
    }

    @Test
    @DisplayName("the saving rule never touches the primary destination")
    void primaryDestinationIsUnaffected() {
        Optional<AlertType> decision = decider.decide(
                evaluateAt("POA", "1400"),
                change("1400", "1450", "1400", false),
                null,
                NOW,
                new BigDecimal("1400"));

        assertThat(decision).contains(AlertType.PRICE_DROP);
    }

    @Test
    @DisplayName("a price rise never alerts")
    void priceRiseIsSilent() {
        Optional<AlertType> decision = decider.decide(
                evaluate("1500"), change("1500", "1400", "1400", false), null, NOW);

        assertThat(decision).isEmpty();
    }
}

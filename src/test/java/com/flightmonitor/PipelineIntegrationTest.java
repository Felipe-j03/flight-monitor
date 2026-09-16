package com.flightmonitor;

import static org.assertj.core.api.Assertions.assertThat;

import com.flightmonitor.application.AlertType;
import com.flightmonitor.application.OfferStore;
import com.flightmonitor.application.PriceChange;
import com.flightmonitor.application.SearchOrchestrator;
import com.flightmonitor.config.FlightMonitorProperties;
import com.flightmonitor.config.TripConfig;
import com.flightmonitor.domain.model.OfferEvaluation;
import com.flightmonitor.domain.rule.OfferEvaluator;
import com.flightmonitor.domain.port.AirportCatalog;
import com.flightmonitor.infrastructure.persistence.FlightOfferEntity;
import com.flightmonitor.infrastructure.persistence.FlightOfferRepository;
import com.flightmonitor.infrastructure.persistence.PriceHistoryRepository;
import com.flightmonitor.infrastructure.persistence.SearchRunRepository;
import com.flightmonitor.infrastructure.web.MonitorQueryService;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/**
 * The whole pipeline against a real database and the real migration: search, filter, store,
 * compare, notify.
 *
 * <p>The fixture provider supplies the flights, so no network and no API key are involved, and the
 * payload it replays deliberately contains a cheap Doha routing that must be thrown out.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(RecordingNotificationPort.Config.class)
class PipelineIntegrationTest {

    @Autowired private SearchOrchestrator orchestrator;
    @Autowired private FlightOfferRepository offers;
    @Autowired private PriceHistoryRepository history;
    @Autowired private SearchRunRepository searchRuns;
    @Autowired private RecordingNotificationPort notifications;
    @Autowired private FlightMonitorProperties properties;
    @Autowired private OfferStore offerStore;
    @Autowired private AirportCatalog catalog;
    @Autowired private MonitorQueryService queries;
    @Autowired private com.flightmonitor.infrastructure.web.MonitorController controller;

    /**
     * The classic round trip the fixture payload was recorded for. Runs do not depend on which trips
     * are configured; the configured ones are checked by {@link #statusIsUsable()}.
     */
    private TripConfig trip() {
        return TestFixtures.trip();
    }

    @Test
    @DisplayName("a full run stores the good itinerary, rejects the blocked one and notifies")
    void fullPipeline() {
        notifications.clear();

        SearchOrchestrator.SearchSummary summary = orchestrator.run(trip());

        assertThat(summary.status()).isEqualTo("COMPLETED");
        assertThat(summary.offersFound()).isGreaterThan(0);

        List<FlightOfferEntity> stored = offers.findAll();
        assertThat(stored).isNotEmpty();

        FlightOfferEntity viaFrankfurt = stored.stream()
                .filter(offer -> offer.airportsVisited.contains("FRA"))
                .filter(offer -> !offer.airportsVisited.contains("DOH"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("the Frankfurt routing was not stored"));

        assertThat(viaFrankfurt.accepted).isTrue();
        assertThat(viaFrankfurt.originAirport).isEqualTo("HND");
        assertThat(viaFrankfurt.destinationAirport).isEqualTo("POA");
        assertThat(viaFrankfurt.currentPriceGbp).isEqualByComparingTo("1387");
        assertThat(viaFrankfurt.priceBand).isEqualTo("WITHIN_BUDGET");
        assertThat(viaFrankfurt.countriesVisited).contains("Germany").contains("Brazil");

        FlightOfferEntity viaDoha = stored.stream()
                .filter(offer -> offer.airportsVisited.contains("DOH"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("the Doha routing was not stored"));

        assertThat(viaDoha.accepted)
                .as("a Doha connection must be rejected even though it is the cheaper fare")
                .isFalse();
        assertThat(viaDoha.currentPriceGbp).isEqualByComparingTo("1250");
        assertThat(viaDoha.rejectionReasons).contains("BLOCKED_COUNTRY").contains("Qatar");

        assertThat(history.countByOfferId(viaFrankfurt.id)).isPositive();

        assertThat(searchRuns.findFirstByTripIdOrderByStartedAtDesc(trip().id()))
                .get()
                .satisfies(run -> {
                    assertThat(run.status).isEqualTo("COMPLETED");
                    assertThat(run.offersRejected).isPositive();
                });

        assertThat(notifications.messages())
                .as("the accepted offer should have produced exactly one alert")
                .hasSize(1);
        assertThat(notifications.messages().get(0))
                .contains("DADOS SIMULADOS")
                .contains("1.387")
                .contains("Porto Alegre");
        assertThat(notifications.messages().get(0))
                .as("no alert may ever be produced for the blocked routing")
                .doesNotContain("Doha");
    }

    @Test
    @DisplayName("history accumulates and a real drop is detected against the previous price")
    @Transactional
    void priceHistoryAndDropDetection() {
        OfferEvaluator evaluator = new OfferEvaluator(
                trip(), properties.safety(), properties.scoring(), catalog);

        OfferEvaluation first = evaluator.evaluate(TestFixtures.acceptableItinerary()
                .source("history-test")
                .priceGbp("1590")
                .build());
        OfferStore.StoredOffer firstSave = offerStore.save(first);

        assertThat(firstSave.created()).isTrue();
        assertThat(firstSave.change().firstSighting())
                .as("the very first price is an observed price, not a drop")
                .isTrue();
        assertThat(firstSave.change().isDrop()).isFalse();
        assertThat(firstSave.change().previousGbp()).isNull();

        OfferEvaluation second = evaluator.evaluate(TestFixtures.acceptableItinerary()
                .source("history-test")
                .priceGbp("1449")
                .build());
        OfferStore.StoredOffer secondSave = offerStore.save(second);

        assertThat(secondSave.created()).isFalse();
        assertThat(secondSave.entity().id)
                .as("the same itinerary must reuse one row, keyed on its fingerprint")
                .isEqualTo(firstSave.entity().id);

        PriceChange change = secondSave.change();
        assertThat(change.previousGbp()).isEqualByComparingTo("1590");
        assertThat(change.currentGbp()).isEqualByComparingTo("1449");
        assertThat(change.dropGbp()).isEqualByComparingTo("141");
        assertThat(change.dropPercent()).isEqualByComparingTo("8.87");
        assertThat(change.newAllTimeLow()).isTrue();
        assertThat(change.observationCount()).isEqualTo(2);

        assertThat(secondSave.entity().lowestPriceGbp).isEqualByComparingTo("1449");
        assertThat(secondSave.entity().highestPriceGbp).isEqualByComparingTo("1590");

        assertThat(history.findByOfferIdOrderByObservedAtAsc(firstSave.entity().id))
                .extracting(row -> row.priceGbp.stripTrailingZeros().toPlainString())
                .containsExactly("1590", "1449");
    }

    @Test
    @DisplayName("two providers quoting the same flights collapse into one offer with two sources")
    @Transactional
    void deduplicatesAcrossProviders() {
        OfferEvaluator evaluator = new OfferEvaluator(
                trip(), properties.safety(), properties.scoring(), catalog);

        OfferEvaluation fromA = evaluator.evaluate(TestFixtures.acceptableItinerary()
                .source("provider-a")
                .destination("CWB")
                .priceGbp("1450")
                .build());
        OfferEvaluation fromB = evaluator.evaluate(TestFixtures.acceptableItinerary()
                .source("provider-b")
                .destination("CWB")
                .priceGbp("1445")
                .build());

        OfferStore.StoredOffer stored = offerStore.saveGroup(List.of(fromA, fromB));

        assertThat(stored.providerCodes()).containsExactly("provider-a", "provider-b");
        assertThat(stored.entity().currentPriceGbp)
                .as("the headline price is the best quote across sources")
                .isEqualByComparingTo("1445");
        assertThat(stored.sources()).hasSize(2);

        assertThat(offers.findByTripIdAndFingerprint(
                        trip().id(), fromA.itinerary().fingerprint()))
                .as("both providers must map to the same fingerprint")
                .isPresent();
        assertThat(fromA.itinerary().fingerprint()).isEqualTo(fromB.itinerary().fingerprint());
    }

    @Test
    @DisplayName("stores the ticket's local calendar date, not a UTC-shifted one")
    @Transactional
    void storesLocalCalendarDates() {
        OfferEvaluator evaluator = new OfferEvaluator(
                trip(), properties.safety(), properties.scoring(), catalog);

        // 07:00 in Tokyo on the 8th is 22:00 UTC on the 7th. Reading the instant back from a
        // timestamptz column would show the 7th; the ticket says the 8th.
        OfferEvaluation early = evaluator.evaluate(TestFixtures.acceptableItinerary()
                .source("tz-test")
                .destination("CWB")
                .departingAt(java.time.OffsetDateTime.of(
                        2027, 1, 8, 7, 0, 0, 0, TestFixtures.JAPAN))
                .build());

        OfferStore.StoredOffer stored = offerStore.save(early);

        assertThat(early.itinerary().departureAt().toInstant())
                .as("the instant really is the previous day in UTC")
                .isEqualTo(java.time.Instant.parse("2027-01-07T22:00:00Z"));
        assertThat(stored.entity().departureLocalDate).isEqualTo("2027-01-08");
        assertThat(stored.entity().returnArrivalLocalTime)
                .as("return arrival is kept in Tokyo local time for display")
                .startsWith("2027-01-23T");
    }

    @Test
    @DisplayName("one run leaves one history row per provider, not one per query")
    @Transactional
    void oneHistoryRowPerProviderPerRun() {
        OfferEvaluator evaluator = new OfferEvaluator(
                trip(), properties.safety(), properties.scoring(), catalog);

        // The same itinerary surfaced by four neighbouring-date queries from one provider, plus
        // one quote from a second provider.
        List<OfferEvaluation> oneRun = List.of(
                evaluator.evaluate(TestFixtures.acceptableItinerary()
                        .source("provider-a").destination("FLN").priceGbp("1500").build()),
                evaluator.evaluate(TestFixtures.acceptableItinerary()
                        .source("provider-a").destination("FLN").priceGbp("1480").build()),
                evaluator.evaluate(TestFixtures.acceptableItinerary()
                        .source("provider-a").destination("FLN").priceGbp("1495").build()),
                evaluator.evaluate(TestFixtures.acceptableItinerary()
                        .source("provider-b").destination("FLN").priceGbp("1470").build()));

        OfferStore.StoredOffer stored = offerStore.saveGroup(oneRun);

        assertThat(history.countByOfferId(stored.entity().id))
                .as("four quotes from two providers must leave two observations, not four")
                .isEqualTo(2);
        assertThat(stored.change().observationCount()).isEqualTo(2);
        assertThat(stored.entity().currentPriceGbp).isEqualByComparingTo("1470");
        assertThat(stored.sources())
                .extracting(source -> source.priceGbp.stripTrailingZeros().toPlainString())
                .containsExactlyInAnyOrder("1480", "1470");
    }

    @Test
    @DisplayName("the status endpoint reports the trip, the providers and the safety list")
    void statusIsUsable() {
        orchestrator.run(trip());

        MonitorQueryService.StatusView status = queries.status();

        assertThat(status.trips())
                .extracting(MonitorQueryService.TripStatus::id)
                .containsExactly("tokyo-rio-poa-2027", "rio-poa-2027-01-13", "poa-rio-2027");

        MonitorQueryService.TripStatus tokyo = status.trips().get(0);
        assertThat(tokyo.origins()).containsExactly("HND", "NRT");
        assertThat(tokyo.destinations()).containsExactly("GIG", "SDU");
        assertThat(tokyo.budgetCurrency()).isEqualTo("GBP");
        assertThat(tokyo.budgetMax()).isEqualByComparingTo("2000");

        MonitorQueryService.TripStatus domestic = status.trips().get(1);
        assertThat(domestic.origins()).containsExactly("GIG", "SDU");
        assertThat(domestic.destinations()).containsExactly("POA");
        assertThat(domestic.budgetCurrency()).isEqualTo("BRL");
        assertThat(domestic.budgetMax()).isEqualByComparingTo("350");

        MonitorQueryService.TripStatus rio = status.trips().get(2);
        assertThat(rio.origins()).containsExactly("POA");
        assertThat(rio.destinations()).containsExactly("GIG", "SDU");
        assertThat(rio.budgetCurrency()).isEqualTo("BRL");
        assertThat(rio.budgetMax()).isEqualByComparingTo("800");
        assertThat(status.providers())
                .extracting(MonitorQueryService.ProviderStatus::code)
                .contains("fixture", "serpapi", "travelpayouts");
        assertThat(status.providers())
                .filteredOn(provider -> provider.code().equals("fixture"))
                .allSatisfy(provider -> assertThat(provider.available()).isTrue());
        assertThat(status.safety().get("blockedCountries").toString()).contains("QA");
        assertThat(status.schedulerEnabled()).isFalse();
    }

    @Test
    @DisplayName("the test-notification endpoint proves the channel end to end")
    void testNotificationReachesTheChannel() {
        notifications.clear();

        var response = controller.testNotification();

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getBody()).containsEntry("delivered", true);
        assertThat(notifications.messages()).hasSize(1);
        assertThat(notifications.messages().get(0))
                .contains("Flight Monitor")
                .contains("HND")
                .contains("POA");
    }

    @Test
    @DisplayName("rejections are visible and attributed to a reason")
    void rejectionsAreVisible() {
        orchestrator.run(trip());

        assertThat(queries.rejectionBreakdown(trip().id()))
                .containsKey("BLOCKED_COUNTRY");
    }
}

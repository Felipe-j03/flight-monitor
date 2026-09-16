package com.flightmonitor.application;

import com.flightmonitor.config.FlightMonitorProperties;
import com.flightmonitor.config.TripConfig;
import com.flightmonitor.domain.model.Itinerary;
import com.flightmonitor.domain.model.OfferEvaluation;
import com.flightmonitor.domain.port.AirportCatalog;
import com.flightmonitor.domain.port.ExchangeRateProvider;
import com.flightmonitor.domain.port.FlightSearchProvider;
import com.flightmonitor.domain.port.NotificationPort;
import com.flightmonitor.domain.port.ProviderResult;
import com.flightmonitor.domain.port.SearchQuery;
import com.flightmonitor.domain.rule.OfferEvaluator;
import com.flightmonitor.infrastructure.persistence.AlertEntity;
import com.flightmonitor.infrastructure.persistence.ProviderRunEntity;
import com.flightmonitor.infrastructure.persistence.AlertRepository;
import com.flightmonitor.infrastructure.persistence.FlightOfferRepository;
import com.flightmonitor.infrastructure.persistence.ProviderRunRepository;
import com.flightmonitor.infrastructure.persistence.SearchRunRepository;
import com.flightmonitor.infrastructure.persistence.SearchRunEntity;
import com.flightmonitor.notification.AlertMessageFormatter;
import com.flightmonitor.providers.AbstractHttpFlightProvider;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * The pipeline: plan queries, ask every available provider, filter, de-duplicate, store, compare
 * against history, and notify.
 *
 * <p>Nothing here throws on a provider failure. A source that is down produces a recorded
 * {@code PROVIDER_ERROR} and the run continues with whatever the other sources returned, because a
 * partial answer is useful and a crashed scheduler is not.
 *
 * <p>Rejected offers are stored too, with their reasons. Being able to see that eleven itineraries
 * were dropped for {@code BLOCKED_COUNTRY} is the difference between a filter you trust and one you
 * hope is working.
 */
@Service
public class SearchOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(SearchOrchestrator.class);

    private final FlightMonitorProperties properties;
    private final List<FlightSearchProvider> providers;
    private final QueryPlanner planner;
    private final OfferStore offerStore;
    private final AirportCatalog catalog;
    private final NotificationPort notifier;
    private final AlertMessageFormatter formatter;
    private final SearchRunRepository searchRuns;
    private final ProviderRunRepository providerRuns;
    private final AlertRepository alerts;
    private final FlightOfferRepository offers;
    private final ExchangeRateProvider exchangeRates;
    private final Clock clock;

    public SearchOrchestrator(
            FlightMonitorProperties properties,
            List<FlightSearchProvider> providers,
            QueryPlanner planner,
            OfferStore offerStore,
            AirportCatalog catalog,
            NotificationPort notifier,
            AlertMessageFormatter formatter,
            SearchRunRepository searchRuns,
            ProviderRunRepository providerRuns,
            AlertRepository alerts,
            FlightOfferRepository offers,
            ExchangeRateProvider exchangeRates,
            Clock clock) {
        this.properties = properties;
        this.providers = providers;
        this.planner = planner;
        this.offerStore = offerStore;
        this.catalog = catalog;
        this.notifier = notifier;
        this.formatter = formatter;
        this.searchRuns = searchRuns;
        this.providerRuns = providerRuns;
        this.alerts = alerts;
        this.offers = offers;
        this.exchangeRates = exchangeRates;
        this.clock = clock;
    }

    public List<SearchSummary> runAll() {
        List<SearchSummary> summaries = new ArrayList<>();
        for (TripConfig trip : properties.enabledTrips()) {
            summaries.add(run(trip));
        }
        return summaries;
    }

    public SearchSummary run(TripConfig trip) {
        OffsetDateTime startedAt = OffsetDateTime.now(clock);
        List<SearchQuery> queries = planner.plan(trip, properties.search().maxQueriesPerRun());

        SearchRunEntity run = new SearchRunEntity();
        run.tripId = trip.id();
        run.startedAt = startedAt;
        run.queriesPlanned = queries.size();
        run.status = "RUNNING";
        run = searchRuns.save(run);

        List<FlightSearchProvider> active = providers.stream()
                .filter(FlightSearchProvider::available)
                .toList();

        log.info("SEARCH_STARTED trip={} queries={} providers={}",
                trip.id(), queries.size(),
                active.stream().map(FlightSearchProvider::code).toList());

        active.forEach(provider -> {
            if (provider instanceof AbstractHttpFlightProvider http) {
                http.resetRunCounters();
            }
        });

        if (active.isEmpty()) {
            run.status = "NO_PROVIDERS";
            run.finishedAt = OffsetDateTime.now(clock);
            run.notes = "No provider is enabled and configured. "
                    + "Set an API key or enable the fixture provider for a dry run.";
            searchRuns.save(run);
            log.error("SEARCH_COMPLETED trip={} status=NO_PROVIDERS", trip.id());
            return SearchSummary.of(run, List.of());
        }

        // Every price comparison in the domain is in GBP. A trip budgeted in another currency gets
        // its budget converted with today's live rate — checked BEFORE any provider is called, so
        // a missing rate costs nothing instead of costing a search that could not be judged.
        TripConfig pricedTrip = trip;
        if (!trip.budgetInGbp()) {
            var rate = exchangeRates.rate(trip.budgetCurrency(), "GBP");
            if (rate.isEmpty()) {
                run.status = "FX_UNAVAILABLE";
                run.finishedAt = OffsetDateTime.now(clock);
                run.notes = "No live " + trip.budgetCurrency() + "->GBP rate; the budget could "
                        + "not be converted, so no provider was called.";
                searchRuns.save(run);
                log.error("SEARCH_COMPLETED trip={} status=FX_UNAVAILABLE currency={}",
                        trip.id(), trip.budgetCurrency());
                return SearchSummary.of(run, List.of());
            }
            pricedTrip = trip.withBudgetConvertedToGbp(rate.get().rate());
            log.info("BUDGET_CONVERTED trip={} {} {}-{} = GBP {}-{} (rate {})",
                    trip.id(), trip.budgetCurrency(), trip.targetMinPrice(), trip.targetMaxPrice(),
                    pricedTrip.targetMinPriceGbp(), pricedTrip.targetMaxPriceGbp(),
                    rate.get().rate());
        }

        List<Itinerary> found = new ArrayList<>();
        int executed = 0;

        for (SearchQuery query : queries) {
            for (FlightSearchProvider provider : active) {
                ProviderResult result = provider.search(query);
                recordProviderRun(run.id, result);
                if (result.success() && !result.offers().isEmpty()) {
                    found.addAll(result.offers());
                }
                if (!result.success()) {
                    log.warn("PROVIDER_ERROR provider={} query={} message={}",
                            result.providerCode(), query.describe(), result.errorMessage());
                }
                executed++;
            }
        }

        OfferEvaluator evaluator = new OfferEvaluator(
                pricedTrip, properties.safety(), properties.scoring(), catalog);

        Map<String, List<OfferEvaluation>> byFingerprint = new LinkedHashMap<>();
        int rejected = 0;

        for (Itinerary itinerary : found) {
            OfferEvaluation evaluation = evaluator.evaluate(itinerary);
            if (!evaluation.accepted()) {
                rejected++;
                log.info("OFFER_REJECTED trip={} source={} route={}->{} {}",
                        trip.id(), itinerary.source(), itinerary.originAirport(),
                        itinerary.destinationAirport(), evaluation.rejectionSummary());
            }
            byFingerprint
                    .computeIfAbsent(itinerary.fingerprint(), key -> new ArrayList<>())
                    .add(evaluation);
        }

        List<AlertCandidate> candidates = new ArrayList<>();
        AlertDecider decider = new AlertDecider(properties.alerts(), pricedTrip);

        for (List<OfferEvaluation> group : byFingerprint.values()) {
            OfferStore.StoredOffer stored = offerStore.saveGroup(group);
            OfferEvaluation best = group.stream()
                    .filter(OfferEvaluation::accepted)
                    .max(Comparator.comparingDouble(OfferEvaluation::score))
                    .orElse(group.get(0));

            // Read after each save, not once up front: an offer stored earlier in this same run
            // may itself be the new primary-airport benchmark.
            BigDecimal bestPrimary =
                    offers.findBestPriceForDestinations(trip.id(), trip.primaryDestinations());

            decider.decide(best, stored.change(), stored.entity().lastAlertedAt,
                            OffsetDateTime.now(clock), bestPrimary)
                    .ifPresent(type -> candidates.add(new AlertCandidate(type, stored, best)));
        }

        int alertsSent = sendAlerts(trip, candidates);

        run.queriesExecuted = executed;
        run.offersFound = found.size();
        run.offersRejected = rejected;
        run.offersAccepted = found.size() - rejected;
        run.alertsSent = alertsSent;
        run.finishedAt = OffsetDateTime.now(clock);
        run.status = "COMPLETED";
        run = searchRuns.save(run);

        log.info("SEARCH_COMPLETED trip={} queries={} offers={} accepted={} rejected={} alerts={}",
                trip.id(), executed, found.size(), run.offersAccepted, rejected, alertsSent);

        return SearchSummary.of(run, candidates.stream().map(AlertCandidate::type).toList());
    }

    /** Most important news first, capped so one run cannot flood the chat. */
    private int sendAlerts(TripConfig trip, List<AlertCandidate> candidates) {
        List<AlertCandidate> ordered = candidates.stream()
                .sorted(Comparator
                        .comparingInt((AlertCandidate candidate) -> importance(candidate.type()))
                        .thenComparing(candidate -> candidate.entity().currentPriceGbp))
                .limit(properties.alerts().maxAlertsPerRun())
                .toList();

        int sent = 0;
        for (AlertCandidate candidate : ordered) {
            String message = formatter.format(
                    candidate.type(),
                    candidate.stored().entity(),
                    candidate.stored().sources(),
                    candidate.evaluation(),
                    candidate.stored().change(),
                    trip,
                    candidate.stored().providerCodes());

            NotificationPort.DeliveryResult delivery = notifier.send(message);

            AlertEntity record = new AlertEntity();
            record.offerId = candidate.stored().entity().id;
            record.tripId = trip.id();
            record.alertType = candidate.type().name();
            record.priceGbp = candidate.entity().currentPriceGbp;
            record.previousPriceGbp = candidate.stored().change().previousGbp();
            record.deltaGbp = candidate.stored().change().dropGbp();
            record.deltaPercent = candidate.stored().change().dropPercent();
            record.message = message;
            record.createdAt = OffsetDateTime.now(clock);
            record.delivered = delivery.delivered();
            record.deliveryError = delivery.error();
            alerts.save(record);

            if (delivery.delivered()) {
                offerStore.markAlerted(candidate.stored().entity().id, OffsetDateTime.now(clock));
                sent++;
                log.info("ALERT_SENT trip={} type={} offer={} price=GBP{}",
                        trip.id(), candidate.type(), candidate.stored().entity().id,
                        candidate.entity().currentPriceGbp);
            }
        }
        return sent;
    }

    private static int importance(AlertType type) {
        return switch (type) {
            case EXCELLENT_PRICE -> 0;
            case NEW_ALL_TIME_LOW -> 1;
            case PRICE_DROP -> 2;
            case NEW_OFFER_WITHIN_BUDGET -> 3;
        };
    }

    private void recordProviderRun(Long searchRunId, ProviderResult result) {
        ProviderRunEntity entity = new ProviderRunEntity();
        entity.searchRunId = searchRunId;
        entity.providerCode = result.providerCode();
        entity.querySummary = result.query().describe();
        entity.success = result.success();
        entity.errorMessage = result.errorMessage();
        entity.offersFound = result.offers().size();
        entity.elapsedMillis = result.elapsedMillis();
        entity.executedAt = OffsetDateTime.now(clock);
        providerRuns.save(entity);
    }

    private record AlertCandidate(
            AlertType type, OfferStore.StoredOffer stored, OfferEvaluation evaluation) {

        com.flightmonitor.infrastructure.persistence.FlightOfferEntity entity() {
            return stored.entity();
        }
    }

    /** What a run did, for the API and the scheduler log. */
    public record SearchSummary(
            Long runId,
            String tripId,
            String status,
            int queriesPlanned,
            int queriesExecuted,
            int offersFound,
            int offersAccepted,
            int offersRejected,
            int alertsSent,
            OffsetDateTime startedAt,
            OffsetDateTime finishedAt,
            List<AlertType> alertTypes,
            String notes) {

        static SearchSummary of(SearchRunEntity run, List<AlertType> alertTypes) {
            return new SearchSummary(
                    run.id, run.tripId, run.status, run.queriesPlanned, run.queriesExecuted,
                    run.offersFound, run.offersAccepted, run.offersRejected, run.alertsSent,
                    run.startedAt, run.finishedAt, alertTypes, run.notes);
        }
    }
}

package com.flightmonitor.infrastructure.web;

import com.flightmonitor.config.FlightMonitorProperties;
import com.flightmonitor.config.TripConfig;
import com.flightmonitor.domain.port.FlightSearchProvider;
import com.flightmonitor.domain.port.NotificationPort;
import com.flightmonitor.domain.port.ProviderBudget;
import com.flightmonitor.application.SearchScheduler;
import com.flightmonitor.infrastructure.persistence.FlightOfferEntity;
import com.flightmonitor.infrastructure.persistence.PriceHistoryEntity;
import com.flightmonitor.infrastructure.persistence.AlertRepository;
import com.flightmonitor.infrastructure.persistence.FlightOfferRepository;
import com.flightmonitor.infrastructure.persistence.OfferSourceRepository;
import com.flightmonitor.infrastructure.persistence.PriceHistoryRepository;
import com.flightmonitor.infrastructure.persistence.SearchRunRepository;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Read side of the application: everything the REST API and the dashboard need. */
@Service
@Transactional(readOnly = true)
public class MonitorQueryService {

    private final FlightMonitorProperties properties;
    private final FlightOfferRepository offers;
    private final OfferSourceRepository sources;
    private final PriceHistoryRepository history;
    private final AlertRepository alerts;
    private final SearchRunRepository searchRuns;
    private final SearchScheduler scheduler;
    private final List<FlightSearchProvider> providers;
    private final ProviderBudget budget;
    private final NotificationPort notifier;

    public MonitorQueryService(
            FlightMonitorProperties properties,
            FlightOfferRepository offers,
            OfferSourceRepository sources,
            PriceHistoryRepository history,
            AlertRepository alerts,
            SearchRunRepository searchRuns,
            SearchScheduler scheduler,
            List<FlightSearchProvider> providers,
            ProviderBudget budget,
            NotificationPort notifier) {
        this.properties = properties;
        this.offers = offers;
        this.sources = sources;
        this.history = history;
        this.alerts = alerts;
        this.searchRuns = searchRuns;
        this.scheduler = scheduler;
        this.providers = providers;
        this.budget = budget;
        this.notifier = notifier;
    }

    public List<OfferView> offers(String tripId, boolean acceptedOnly, String sort, int limit) {
        PageRequest page = PageRequest.of(0, Math.min(Math.max(limit, 1), 500));
        List<FlightOfferEntity> found = switch (sort == null ? "price" : sort.toLowerCase()) {
            case "score" -> offers.findByTripIdAndAcceptedTrueOrderByScoreDesc(tripId, page);
            case "recent" -> offers.findByTripIdOrderByLastSeenAtDesc(tripId, page);
            default -> offers.findByTripIdAndAcceptedTrueOrderByCurrentPriceGbpAsc(tripId, page);
        };
        return found.stream()
                .filter(entity -> !acceptedOnly || entity.accepted)
                .map(entity -> OfferView.from(entity, sources.findByOfferId(entity.id)))
                .toList();
    }

    public List<OfferView> cheapest(String tripId, int limit) {
        return offers(tripId, true, "price", limit);
    }

    public List<OfferView> best(String tripId, int limit) {
        return offers(tripId, true, "score", limit);
    }

    /**
     * @param offerId when given, the full history of that one itinerary, rejected or not; when
     *                null, the trip-wide history restricted to itineraries that passed the filters,
     *                because a blocked route's price is not part of the opportunity being tracked
     */
    public List<PricePoint> priceHistory(String tripId, Long offerId, int limit) {
        List<PriceHistoryEntity> rows = offerId != null
                ? history.findByOfferIdOrderByObservedAtAsc(offerId)
                : history.findAcceptedByTrip(
                        tripId, PageRequest.of(0, Math.min(Math.max(limit, 1), 2000)));
        return rows.stream()
                .map(row -> new PricePoint(
                        row.offerId, row.observedAt, row.priceGbp, row.priceOriginal,
                        row.currency, row.fxRate, row.providerCode))
                .toList();
    }

    public StatusView status() {
        Map<String, Object> safety = new java.util.LinkedHashMap<>();
        safety.put("blockedCountries", properties.safety().sortedCountries());
        safety.put("blockedAirports", properties.safety().sortedAirports());
        safety.put("rejectUnverifiableRoutes", properties.safety().rejectUnverifiableRoutes());
        safety.put("rejectUnknownAirports", properties.safety().rejectUnknownAirports());

        List<TripStatus> trips = properties.enabledTrips().stream()
                .map(this::tripStatus)
                .toList();

        List<ProviderStatus> providerStatuses = providers.stream()
                .map(provider -> {
                    var config = properties.providers().get(provider.code());
                    int cap = config == null ? 0 : config.maxQueriesPerMonth();
                    return new ProviderStatus(
                            provider.code(),
                            provider.displayName(),
                            provider.available(),
                            provider.routeDetailLevel().name(),
                            budget.usedThisMonth(provider.code()),
                            cap,
                            cap > 0 ? budget.pacedAllowance(cap) : null);
                })
                .toList();

        return new StatusView(
                trips,
                providerStatuses,
                new NotificationStatus(notifier.channelName(), notifier.configured()),
                scheduler.running(),
                properties.search().enabled(),
                properties.search().intervalHours(),
                scheduler.lastRunAt().map(instant -> instant.atOffset(OffsetDateTime.now().getOffset()))
                        .orElse(null),
                scheduler.nextRunAt().map(instant -> instant.atOffset(OffsetDateTime.now().getOffset()))
                        .orElse(null),
                safety);
    }

    private TripStatus tripStatus(TripConfig trip) {
        var lastRun = searchRuns.findFirstByTripIdOrderByStartedAtDesc(trip.id());
        return new TripStatus(
                trip.id(),
                trip.name(),
                trip.originAirports(),
                trip.destinationAirports(),
                trip.targetDepartureDate().toString(),
                trip.earliestDeparture() + ".." + trip.latestDeparture(),
                trip.latestReturnArrival().toString(),
                trip.preferredReturnArrival().toString(),
                trip.targetMinPrice(),
                trip.targetMaxPrice(),
                trip.budgetCurrency(),
                offers.countByTripIdAndAcceptedTrue(trip.id()),
                offers.findCurrentBestPrice(trip.id()),
                offers.findAllTimeLowPrice(trip.id()),
                alerts.countByTripId(trip.id()),
                lastRun.map(run -> run.startedAt).orElse(null),
                lastRun.map(run -> run.status).orElse("NEVER_RUN"),
                lastRun.map(run -> run.offersFound).orElse(0),
                lastRun.map(run -> run.offersRejected).orElse(0));
    }

    public List<AlertView> recentAlerts(String tripId, int limit) {
        return alerts.findByTripIdOrderByCreatedAtDesc(
                        tripId, PageRequest.of(0, Math.min(Math.max(limit, 1), 100)))
                .stream()
                .map(alert -> new AlertView(
                        alert.id, alert.alertType, alert.priceGbp, alert.previousPriceGbp,
                        alert.deltaGbp, alert.deltaPercent, alert.message, alert.createdAt, alert.delivered,
                        alert.deliveryError))
                .toList();
    }

    public Map<String, Long> rejectionBreakdown(String tripId) {
        return offers.findByTripIdOrderByLastSeenAtDesc(tripId, PageRequest.of(0, 500)).stream()
                .filter(entity -> !entity.accepted && entity.rejectionReasons != null)
                .flatMap(entity -> java.util.Arrays.stream(entity.rejectionReasons.split(", ")))
                .map(reason -> reason.contains("(") ? reason.substring(0, reason.indexOf('(')) : reason)
                .collect(Collectors.groupingBy(reason -> reason, Collectors.counting()));
    }

    public record PricePoint(
            Long offerId,
            OffsetDateTime observedAt,
            BigDecimal priceGbp,
            BigDecimal priceOriginal,
            String currency,
            BigDecimal fxRate,
            String provider) {}

    public record AlertView(
            Long id,
            String type,
            BigDecimal priceGbp,
            BigDecimal previousPriceGbp,
            BigDecimal deltaGbp,
            BigDecimal deltaPercent,
            String message,
            OffsetDateTime createdAt,
            boolean delivered,
            String deliveryError) {}

    public record ProviderStatus(
            String code,
            String name,
            boolean available,
            String routeDetail,
            int callsUsedThisMonth,
            int monthlyCap,
            Integer releasedSoFar) {}

    public record NotificationStatus(String channel, boolean configured) {}

    public record TripStatus(
            String id,
            String name,
            List<String> origins,
            List<String> destinations,
            String targetDeparture,
            String departureWindow,
            String latestReturnArrival,
            String preferredReturnArrival,
            BigDecimal budgetMin,
            BigDecimal budgetMax,
            String budgetCurrency,
            long acceptedOffers,
            BigDecimal currentBestPriceGbp,
            BigDecimal allTimeLowGbp,
            long alertsSent,
            OffsetDateTime lastSearchAt,
            String lastSearchStatus,
            int lastSearchOffers,
            int lastSearchRejected) {}

    public record StatusView(
            List<TripStatus> trips,
            List<ProviderStatus> providers,
            NotificationStatus notifications,
            boolean searchInProgress,
            boolean schedulerEnabled,
            int intervalHours,
            OffsetDateTime lastRunAt,
            OffsetDateTime nextRunAt,
            Map<String, Object> safety) {}
}

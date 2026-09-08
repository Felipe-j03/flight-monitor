package com.flightmonitor.infrastructure.web;

import com.flightmonitor.application.SearchScheduler;
import com.flightmonitor.config.FlightMonitorProperties;
import com.flightmonitor.config.TripConfig;
import com.flightmonitor.domain.port.FlightSearchProvider;
import com.flightmonitor.domain.port.NotificationPort;
import com.flightmonitor.notification.AlertMessageFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The HTTP surface. Read endpoints are cheap database reads; {@code POST /api/search} exists so a
 * run can be triggered without waiting for the scheduler, which is how the pipeline gets tested
 * against real providers.
 */
@RestController
@RequestMapping("/api")
public class MonitorController {

    private final MonitorQueryService queries;
    private final SearchScheduler scheduler;
    private final FlightMonitorProperties properties;
    private final NotificationPort notifier;
    private final AlertMessageFormatter formatter;
    private final List<FlightSearchProvider> providers;

    public MonitorController(
            MonitorQueryService queries,
            SearchScheduler scheduler,
            FlightMonitorProperties properties,
            NotificationPort notifier,
            AlertMessageFormatter formatter,
            List<FlightSearchProvider> providers) {
        this.queries = queries;
        this.scheduler = scheduler;
        this.properties = properties;
        this.notifier = notifier;
        this.formatter = formatter;
        this.providers = providers;
    }

    @GetMapping("/trips")
    public List<Map<String, Object>> trips() {
        return properties.trips().stream()
                .map(trip -> Map.<String, Object>of(
                        "id", trip.id(),
                        "name", trip.name(),
                        "enabled", trip.enabled(),
                        "origins", trip.originAirports(),
                        "destinations", trip.destinationAirports()))
                .toList();
    }

    @GetMapping("/offers")
    public List<OfferView> offers(
            @RequestParam(required = false) String tripId,
            @RequestParam(defaultValue = "true") boolean acceptedOnly,
            @RequestParam(defaultValue = "price") String sort,
            @RequestParam(defaultValue = "50") int limit) {
        return queries.offers(resolveTrip(tripId), acceptedOnly, sort, limit);
    }

    @GetMapping("/offers/best")
    public List<OfferView> best(
            @RequestParam(required = false) String tripId,
            @RequestParam(defaultValue = "10") int limit) {
        return queries.best(resolveTrip(tripId), limit);
    }

    @GetMapping("/offers/cheapest")
    public List<OfferView> cheapest(
            @RequestParam(required = false) String tripId,
            @RequestParam(defaultValue = "10") int limit) {
        return queries.cheapest(resolveTrip(tripId), limit);
    }

    @GetMapping("/offers/{id}/price-history")
    public List<MonitorQueryService.PricePoint> offerHistory(@PathVariable Long id) {
        return queries.priceHistory(null, id, 1000);
    }

    @GetMapping("/price-history")
    public List<MonitorQueryService.PricePoint> priceHistory(
            @RequestParam(required = false) String tripId,
            @RequestParam(defaultValue = "500") int limit) {
        return queries.priceHistory(resolveTrip(tripId), null, limit);
    }

    @GetMapping("/alerts")
    public List<MonitorQueryService.AlertView> alerts(
            @RequestParam(required = false) String tripId,
            @RequestParam(defaultValue = "20") int limit) {
        return queries.recentAlerts(resolveTrip(tripId), limit);
    }

    @GetMapping("/rejections")
    public Map<String, Long> rejections(@RequestParam(required = false) String tripId) {
        return queries.rejectionBreakdown(resolveTrip(tripId));
    }

    @GetMapping("/status")
    public MonitorQueryService.StatusView status() {
        return queries.status();
    }

    /**
     * Triggers a search now. Returns 409 if one is already running rather than queuing a second,
     * because two concurrent runs would double-spend the metered provider budget.
     */
    @PostMapping("/search")
    public ResponseEntity<?> search(@RequestParam(required = false) String tripId) {
        TripConfig trip = tripId == null
                ? null
                : properties.trip(tripId)
                        .orElseThrow(() -> new IllegalArgumentException("unknown trip: " + tripId));

        // Goes through the scheduler's lock, so a manual trigger can never run alongside a
        // scheduled one and double-spend the provider budget.
        return scheduler.runNow(trip)
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(HttpStatus.CONFLICT)
                        .body(Map.of("error", "a search is already in progress")));
    }

    /**
     * Sends a confirmation message through the real notification channel.
     *
     * <p>Exists because "is my Telegram set up?" is otherwise only answerable by waiting for a
     * price to move. This goes through the same configuration and the same client the alerts use,
     * so it catches a token that is valid but never reached the application — the failure a plain
     * curl against the Bot API cannot detect.
     */
    @PostMapping("/test-notification")
    public ResponseEntity<Map<String, Object>> testNotification() {
        if (!notifier.configured()) {
            return ResponseEntity.status(HttpStatus.PRECONDITION_FAILED).body(Map.of(
                    "delivered", false,
                    "channel", notifier.channelName(),
                    "error", "Channel not configured. Set TELEGRAM_ENABLED=true, "
                            + "TELEGRAM_BOT_TOKEN and TELEGRAM_CHAT_ID, then restart."));
        }

        List<TripConfig> trips = properties.enabledTrips();
        String message = trips.isEmpty()
                ? "✅ <b>Flight Monitor</b>\nCanal configurado. Nenhuma viagem ativa ainda."
                : formatter.formatStartupSummary(
                        trips.get(0),
                        (int) providers.stream().filter(FlightSearchProvider::available).count(),
                        providers.stream()
                                .filter(FlightSearchProvider::available)
                                .map(FlightSearchProvider::displayName)
                                .collect(Collectors.joining(", ")));

        NotificationPort.DeliveryResult result = notifier.send(message);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("delivered", result.delivered());
        body.put("channel", notifier.channelName());
        if (result.error() != null) {
            body.put("error", result.error());
        }
        return result.delivered()
                ? ResponseEntity.ok(body)
                : ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(body);
    }

    private String resolveTrip(String tripId) {
        if (tripId != null) {
            return tripId;
        }
        return properties.enabledTrips().stream()
                .findFirst()
                .map(TripConfig::id)
                .orElseThrow(() -> new IllegalStateException("no enabled trip is configured"));
    }
}

package com.flightmonitor.config;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Root of the application's external configuration. Bound from {@code application.yml}, which in
 * turn reads environment variables, so every business rule can be changed without touching code.
 *
 * @param providers keyed by provider code ("serpapi", "travelpayouts", "fixture")
 */
@ConfigurationProperties(prefix = "flight-monitor")
public record FlightMonitorProperties(
        List<TripConfig> trips,
        SafetyConfig safety,
        AlertConfig alerts,
        ScoringConfig scoring,
        SearchConfig search,
        CurrencyConfig currency,
        Map<String, ProviderConfig> providers) {

    public FlightMonitorProperties {
        trips = trips == null ? List.of() : trips;
        providers = providers == null ? Map.of() : providers;

        // Trip ids key the price history. Two trips sharing one would silently merge their offers
        // and compare a Tokyo fare against a Rio fare, so it fails at startup instead.
        List<String> ids = trips.stream().map(TripConfig::id).toList();
        List<String> duplicates = ids.stream()
                .filter(id -> ids.indexOf(id) != ids.lastIndexOf(id))
                .distinct()
                .toList();
        if (!duplicates.isEmpty()) {
            throw new IllegalArgumentException(
                    "Invalid configuration: trip ids must be unique, repeated: " + duplicates);
        }
    }

    public List<TripConfig> enabledTrips() {
        return trips.stream().filter(TripConfig::enabled).toList();
    }

    public Optional<TripConfig> trip(String id) {
        return trips.stream().filter(t -> t.id().equals(id)).findFirst();
    }

    public ProviderConfig provider(String code) {
        ProviderConfig config = providers.get(code);
        if (config == null) {
            throw new IllegalStateException("no configuration block for provider '" + code + "'");
        }
        return config;
    }

    /** Scheduling and per-run breadth. */
    public record SearchConfig(
            boolean enabled,
            int intervalHours,
            int maxQueriesPerRun,
            boolean runOnStartup) {

        public SearchConfig {
            intervalHours = intervalHours <= 0 ? 6 : intervalHours;
            maxQueriesPerRun = maxQueriesPerRun <= 0 ? 12 : maxQueriesPerRun;
        }
    }

    /**
     * Currency conversion. Rates come from a live source (Frankfurter, ECB reference rates, no key
     * required); there are no hardcoded fallback rates anywhere, and a failed lookup means the
     * offer is skipped rather than converted with a stale number.
     */
    public record CurrencyConfig(
            String baseCurrency,
            String apiUrl,
            int cacheTtlMinutes,
            int timeoutSeconds) {

        public CurrencyConfig {
            baseCurrency = baseCurrency == null ? "GBP" : baseCurrency.toUpperCase();
            apiUrl = apiUrl == null ? "https://api.frankfurter.dev/v1" : apiUrl;
            cacheTtlMinutes = cacheTtlMinutes <= 0 ? 360 : cacheTtlMinutes;
            timeoutSeconds = timeoutSeconds <= 0 ? 10 : timeoutSeconds;
        }
    }
}

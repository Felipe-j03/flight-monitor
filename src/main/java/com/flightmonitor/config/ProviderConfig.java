package com.flightmonitor.config;

/**
 * Per-provider transport and budget settings.
 *
 * <p>{@code maxQueriesPerRun} and {@code maxQueriesPerMonth} exist because the only usable free
 * flight-search tiers in 2026 are metered. The monthly figure is enforced against a persisted
 * counter so the monitor cannot silently walk off a free plan and start costing money.
 *
 * @param requestsPerMinute   client-side rate limit, to stay a good citizen regardless of the plan
 * @param maxOptionsPerSearch provider-specific: how many of the cheapest outbound options are
 *                            followed up to resolve their return flights. Each one costs an extra
 *                            API call, so on a metered free tier this stays at 1.
 */
public record ProviderConfig(
        boolean enabled,
        String baseUrl,
        String apiKey,
        int timeoutSeconds,
        int maxRetries,
        long retryBackoffMillis,
        int requestsPerMinute,
        int maxQueriesPerRun,
        int maxQueriesPerMonth,
        int maxOptionsPerSearch) {

    public ProviderConfig {
        // Same reasoning as TelegramConfig: API keys are pasted by hand, and a trailing space
        // would travel all the way into the query string and come back as an auth failure.
        apiKey = apiKey == null ? null : apiKey.strip();
        baseUrl = baseUrl == null ? null : baseUrl.strip();
        timeoutSeconds = timeoutSeconds <= 0 ? 20 : timeoutSeconds;
        maxRetries = maxRetries < 0 ? 2 : maxRetries;
        retryBackoffMillis = retryBackoffMillis <= 0 ? 800 : retryBackoffMillis;
        requestsPerMinute = requestsPerMinute <= 0 ? 20 : requestsPerMinute;
        maxQueriesPerRun = maxQueriesPerRun <= 0 ? 4 : maxQueriesPerRun;
        maxOptionsPerSearch = maxOptionsPerSearch <= 0 ? 1 : maxOptionsPerSearch;
    }

    public boolean hasApiKey() {
        return apiKey != null && !apiKey.isBlank();
    }

    /** A provider is only usable when switched on and, if it needs a key, actually given one. */
    public boolean usable(boolean requiresApiKey) {
        return enabled && (!requiresApiKey || hasApiKey());
    }
}

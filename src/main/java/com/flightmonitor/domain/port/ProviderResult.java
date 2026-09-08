package com.flightmonitor.domain.port;

import com.flightmonitor.domain.model.Itinerary;
import java.util.List;

/**
 * What a provider returned for one query. A failure is represented explicitly rather than as an
 * empty list, so "the provider is down" is never silently reported as "no flights found".
 *
 * @param errorMessage null on success; on failure the reason, which is logged and surfaced in the
 *                     status endpoint as {@code Provider unavailable}
 */
public record ProviderResult(
        String providerCode,
        SearchQuery query,
        List<Itinerary> offers,
        boolean success,
        String errorMessage,
        long elapsedMillis) {

    public static ProviderResult success(
            String providerCode, SearchQuery query, List<Itinerary> offers, long elapsedMillis) {
        return new ProviderResult(providerCode, query, List.copyOf(offers), true, null, elapsedMillis);
    }

    public static ProviderResult failure(
            String providerCode, SearchQuery query, String message, long elapsedMillis) {
        return new ProviderResult(providerCode, query, List.of(), false, message, elapsedMillis);
    }

    /** A provider that was deliberately not called, e.g. because its monthly budget is spent. */
    public static ProviderResult skipped(String providerCode, SearchQuery query, String reason) {
        return new ProviderResult(providerCode, query, List.of(), true, "SKIPPED: " + reason, 0L);
    }
}

package com.flightmonitor.providers;

import static org.assertj.core.api.Assertions.assertThat;

import com.flightmonitor.TestFixtures;
import com.flightmonitor.config.ProviderConfig;
import com.flightmonitor.domain.model.RouteDetailLevel;
import com.flightmonitor.domain.port.ProviderBudget;
import com.flightmonitor.domain.port.ProviderResult;
import com.flightmonitor.domain.port.SearchQuery;
import java.time.LocalDate;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;

/**
 * Every retry re-runs the whole search, and on a metered provider that means paying again. These
 * tests pin down which failures are worth paying for twice.
 */
class RetryPolicyTest {

    private static final SearchQuery QUERY = new SearchQuery(
            TestFixtures.TRIP_ID, "HND", "POA",
            LocalDate.of(2027, 1, 9), LocalDate.of(2027, 1, 21), 1, "ECONOMY", 0);

    private static final ProviderBudget UNLIMITED = new ProviderBudget() {
        @Override public boolean tryConsume(String code, int max) { return true; }
        @Override public int usedThisMonth(String code) { return 0; }
        @Override public int pacedAllowance(int max) { return max; }
    };

    /** Counts how many times the expensive part actually ran. */
    private static final class CountingProvider extends AbstractHttpFlightProvider {

        private final AtomicInteger attempts = new AtomicInteger();
        private final Exception failure;
        private final ProviderResult failureResult;

        CountingProvider(Exception failure, ProviderResult failureResult) {
            super(new ProviderConfig(true, null, "key", 5, 2, 1, 6000, 10, 0, 1), UNLIMITED);
            this.failure = failure;
            this.failureResult = failureResult;
        }

        @Override public String code() { return "counting"; }
        @Override public String displayName() { return "Counting"; }
        @Override public RouteDetailLevel routeDetailLevel() { return RouteDetailLevel.FULL_ITINERARY; }

        @Override
        protected ProviderResult performSearch(SearchQuery query) throws Exception {
            attempts.incrementAndGet();
            if (failure != null) {
                throw failure;
            }
            return failureResult;
        }
    }

    @Test
    @DisplayName("a malformed request is not retried — it would fail identically and cost again")
    void doesNotRetryProgrammingErrors() {
        CountingProvider provider = new CountingProvider(
                new IllegalArgumentException("Invalid character '=' for QUERY_PARAM"), null);

        ProviderResult result = provider.search(QUERY);

        assertThat(result.success()).isFalse();
        assertThat(provider.attempts).hasValue(1);
    }

    @Test
    @DisplayName("a rejected API key is not retried")
    void doesNotRetryAuthFailures() {
        CountingProvider provider = new CountingProvider(
                HttpClientErrorException.create(
                        HttpStatus.UNAUTHORIZED, "Unauthorized", null, null, null),
                null);

        provider.search(QUERY);

        assertThat(provider.attempts).hasValue(1);
    }

    @Test
    @DisplayName("rate limiting IS retried — that is what backoff is for")
    void retriesRateLimiting() {
        CountingProvider provider = new CountingProvider(
                HttpClientErrorException.create(
                        HttpStatus.TOO_MANY_REQUESTS, "Too Many Requests", null, null, null),
                null);

        provider.search(QUERY);

        assertThat(provider.attempts).hasValue(3);
    }

    @Test
    @DisplayName("a server-side blip IS retried")
    void retriesServerErrors() {
        CountingProvider provider = new CountingProvider(
                HttpServerErrorException.create(
                        HttpStatus.SERVICE_UNAVAILABLE, "Unavailable", null, null, null),
                null);

        provider.search(QUERY);

        assertThat(provider.attempts).hasValue(3);
    }

    @Test
    @DisplayName("a failure the provider reported in its payload is its answer, not a hiccup")
    void doesNotRetryProviderReportedFailures() {
        CountingProvider provider = new CountingProvider(
                null,
                ProviderResult.failure("counting", QUERY, "Invalid API key", 5));

        ProviderResult result = provider.search(QUERY);

        assertThat(result.success()).isFalse();
        assertThat(result.errorMessage()).contains("Invalid API key");
        assertThat(provider.attempts)
                .as("asking again gets the same answer and costs the same money")
                .hasValue(1);
    }
}

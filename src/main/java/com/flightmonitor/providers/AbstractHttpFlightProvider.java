package com.flightmonitor.providers;

import com.flightmonitor.config.ProviderConfig;
import com.flightmonitor.domain.port.FlightSearchProvider;
import com.flightmonitor.domain.port.ProviderBudget;
import com.flightmonitor.domain.port.ProviderResult;
import com.flightmonitor.domain.port.SearchQuery;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Shared plumbing every HTTP-backed provider needs: a client-side rate limit, bounded retries with
 * exponential backoff, a per-run call ceiling and the monthly budget guard.
 *
 * <p>Subclasses implement only {@link #performSearch(SearchQuery)} and never have to think about
 * throwing: {@link #search(SearchQuery)} converts anything that escapes into a
 * {@link ProviderResult#failure} so one broken source can never abort a whole search run.
 */
public abstract class AbstractHttpFlightProvider implements FlightSearchProvider {

    protected final Logger log = LoggerFactory.getLogger(getClass());

    protected final ProviderConfig config;
    private final ProviderBudget budget;
    private final AtomicInteger callsThisRun = new AtomicInteger();
    private final AtomicLong lastCallAtNanos = new AtomicLong(0);

    protected AbstractHttpFlightProvider(ProviderConfig config, ProviderBudget budget) {
        this.config = config;
        this.budget = budget;
    }

    /** Does the real work. May throw; the wrapper turns that into a failure result. */
    protected abstract ProviderResult performSearch(SearchQuery query) throws Exception;

    /** How many API calls one {@code performSearch} consumes; SerpApi round trips need two. */
    protected int callCostPerSearch(SearchQuery query) {
        return 1;
    }

    /** Whether this provider is useless without an API key. */
    protected boolean requiresApiKey() {
        return true;
    }

    @Override
    public boolean available() {
        return config.usable(requiresApiKey());
    }

    @Override
    public int remainingQueriesThisRun() {
        return Math.max(0, config.maxQueriesPerRun() - callsThisRun.get());
    }

    /** Called by the orchestrator at the start of each scheduled run. */
    public void resetRunCounters() {
        callsThisRun.set(0);
    }

    @Override
    public final ProviderResult search(SearchQuery query) {
        if (!available()) {
            return ProviderResult.skipped(code(), query, "provider disabled or missing credentials");
        }
        if (remainingQueriesThisRun() <= 0) {
            return ProviderResult.skipped(code(), query, "per-run query limit reached");
        }
        if (config.maxQueriesPerMonth() > 0) {
            for (int i = 0; i < callCostPerSearch(query); i++) {
                if (!budget.tryConsume(code(), config.maxQueriesPerMonth())) {
                    return ProviderResult.skipped(code(), query,
                            "monthly budget spent or paced out ("
                                    + budget.usedThisMonth(code()) + "/"
                                    + config.maxQueriesPerMonth() + " used this month)");
                }
            }
        }

        callsThisRun.incrementAndGet();
        long startedAt = System.nanoTime();
        Exception lastFailure = null;

        for (int attempt = 0; attempt <= config.maxRetries(); attempt++) {
            try {
                throttle();
                ProviderResult result = performSearch(query);
                if (result.success()) {
                    return result;
                }
                // A failure the provider reported in its own payload ("invalid API key", "no
                // results for this query") is its considered answer, not a hiccup. Asking again
                // gets the same answer and costs the same money.
                log.warn("PROVIDER_ERROR provider={} query={} reported={} not_retryable=true",
                        code(), query.describe(), result.errorMessage());
                return ProviderResult.failure(
                        code(), query, result.errorMessage(), elapsedMillis(startedAt));
            } catch (Exception e) {
                lastFailure = e;
                log.warn("PROVIDER_ERROR provider={} query={} attempt={}/{} error={}",
                        code(), query.describe(), attempt + 1, config.maxRetries() + 1, e.toString());
                if (!worthRetrying(e)) {
                    log.error("PROVIDER_ERROR provider={} query={} not_retryable=true error={}",
                            code(), query.describe(), e.toString());
                    break;
                }
            }
            if (attempt < config.maxRetries()) {
                backoff(attempt);
            }
        }

        long elapsed = elapsedMillis(startedAt);
        String message = lastFailure == null ? "unknown failure" : lastFailure.toString();
        log.error("PROVIDER_ERROR provider={} query={} giving_up_after={} attempts error={}",
                code(), query.describe(), config.maxRetries() + 1, message);
        return ProviderResult.failure(code(), query, "Provider unavailable: " + message, elapsed);
    }

    /**
     * Whether trying the same request again could plausibly succeed.
     *
     * <p>Retrying is not free: every attempt re-runs the whole search, which on a metered provider
     * means paying again. A malformed URL or a rejected API key will fail identically every time,
     * so retrying it just burns budget three times as fast — which is exactly what happened when a
     * Base64 token broke URL construction and each retry re-sent the first paid call.
     *
     * <p>A 429 is the exception among 4xx: rate limiting is precisely the case backoff exists for.
     */
    protected boolean worthRetrying(Exception failure) {
        if (failure instanceof IllegalArgumentException || failure instanceof IllegalStateException) {
            return false;
        }
        if (failure instanceof org.springframework.web.client.HttpClientErrorException clientError) {
            return clientError.getStatusCode().value() == 429;
        }
        return true;
    }

    protected long elapsedMillis(long startedAtNanos) {
        return Duration.ofNanos(System.nanoTime() - startedAtNanos).toMillis();
    }

    /** Simple spacing between calls so we never hammer a free endpoint. */
    private void throttle() throws InterruptedException {
        long minGapNanos = 60_000_000_000L / Math.max(1, config.requestsPerMinute());
        long previous = lastCallAtNanos.get();
        long now = System.nanoTime();
        long waitNanos = previous + minGapNanos - now;
        if (previous > 0 && waitNanos > 0) {
            Thread.sleep(Duration.ofNanos(waitNanos).toMillis() + 1);
        }
        lastCallAtNanos.set(System.nanoTime());
    }

    private void backoff(int attempt) {
        long delay = config.retryBackoffMillis() * (1L << attempt);
        try {
            Thread.sleep(Math.min(delay, 30_000L));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}

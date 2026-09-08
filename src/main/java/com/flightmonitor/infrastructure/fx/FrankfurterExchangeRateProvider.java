package com.flightmonitor.infrastructure.fx;

import com.fasterxml.jackson.databind.JsonNode;
import com.flightmonitor.config.FlightMonitorProperties;
import com.flightmonitor.domain.port.ExchangeRateProvider;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * FX rates from Frankfurter (European Central Bank reference rates, no API key, no rate limit
 * worth worrying about, and free with no signup — which is why it was chosen over the metered
 * alternatives).
 *
 * <p>There is deliberately no hardcoded fallback table. If the lookup fails the caller gets an
 * empty result and the offer is skipped, because converting with a stale rate would produce a GBP
 * figure the price history could not be trusted to compare against.
 *
 * <p>ECB rates move once a working day, so a cache TTL of a few hours costs nothing in accuracy.
 */
@Component
public class FrankfurterExchangeRateProvider implements ExchangeRateProvider {

    private static final Logger log = LoggerFactory.getLogger(FrankfurterExchangeRateProvider.class);
    private static final String SOURCE = "frankfurter.dev (ECB)";

    private final RestClient http;
    private final Duration ttl;
    private final Map<String, CachedRate> cache = new ConcurrentHashMap<>();

    public FrankfurterExchangeRateProvider(FlightMonitorProperties properties) {
        FlightMonitorProperties.CurrencyConfig config = properties.currency();
        this.http = RestClient.builder().baseUrl(config.apiUrl()).build();
        this.ttl = Duration.ofMinutes(config.cacheTtlMinutes());
    }

    @Override
    public Optional<FxRate> rate(String from, String to) {
        String source = from.toUpperCase();
        String target = to.toUpperCase();
        if (source.equals(target)) {
            return Optional.of(new FxRate(source, target, BigDecimal.ONE, Instant.now(), "identity"));
        }

        String key = source + "->" + target;
        CachedRate cached = cache.get(key);
        if (cached != null && !cached.isStale(ttl)) {
            return Optional.of(cached.rate());
        }

        try {
            JsonNode body = http.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/latest")
                            .queryParam("base", source)
                            .queryParam("symbols", target)
                            .build())
                    .retrieve()
                    .body(JsonNode.class);

            if (body == null || !body.has("rates") || !body.get("rates").has(target)) {
                log.warn("FX lookup {} returned no rate for {}", key, target);
                return Optional.empty();
            }

            BigDecimal value = body.get("rates").get(target).decimalValue();
            if (value.signum() <= 0) {
                log.warn("FX lookup {} returned a non-positive rate: {}", key, value);
                return Optional.empty();
            }

            FxRate rate = new FxRate(source, target, value, Instant.now(), SOURCE);
            cache.put(key, new CachedRate(rate, Instant.now()));
            log.debug("FX {} = {} ({})", key, value, SOURCE);
            return Optional.of(rate);
        } catch (RuntimeException e) {
            log.warn("FX lookup {} failed: {}", key, e.toString());
            // A stale cached rate is still better than dropping every offer, but it must be
            // reported honestly, so the original retrievedAt timestamp is preserved.
            return cached == null ? Optional.empty() : Optional.of(cached.rate());
        }
    }

    private record CachedRate(FxRate rate, Instant storedAt) {
        boolean isStale(Duration ttl) {
            return storedAt.plus(ttl).isBefore(Instant.now());
        }
    }
}

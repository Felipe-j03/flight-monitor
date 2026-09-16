package com.flightmonitor.providers.travelpayouts;

import static org.assertj.core.api.Assertions.assertThat;

import com.flightmonitor.TestFixtures;
import com.flightmonitor.application.QueryPlanner;
import com.flightmonitor.config.ProviderConfig;
import com.flightmonitor.domain.port.ExchangeRateProvider;
import com.flightmonitor.domain.port.ProviderBudget;
import com.flightmonitor.domain.port.ProviderResult;
import com.flightmonitor.domain.port.SearchQuery;
import com.flightmonitor.infrastructure.fx.CurrencyConverter;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.client.RestTemplateBuilder;

class TravelpayoutsOpenJawTest {

    @Test
    @DisplayName("cannot price a multi-city ticket, so it skips it without spending a call")
    void skipsOpenJaw() {
        AtomicInteger consumed = new AtomicInteger();
        ProviderBudget budget = new ProviderBudget() {
            @Override public boolean tryConsume(String code, int max) {
                consumed.incrementAndGet();
                return true;
            }
            @Override public int usedThisMonth(String code) { return consumed.get(); }
            @Override public int pacedAllowance(int max) { return max; }
        };
        CurrencyConverter fx = new CurrencyConverter(new ExchangeRateProvider() {
            @Override public Optional<FxRate> rate(String from, String to) {
                return Optional.empty();
            }
        });
        // Unreachable base URL: any real request would fail the test.
        TravelpayoutsProvider provider = new TravelpayoutsProvider(
                new ProviderConfig(true, "http://localhost:1", "token", 5, 0, 1, 1000, 10, 0, 1),
                budget, fx, new RestTemplateBuilder());
        SearchQuery query = new QueryPlanner(
                Clock.fixed(Instant.parse("2026-09-16T12:00:00Z"), ZoneOffset.UTC))
                .plan(TestFixtures.tokyoOpenJawTrip(), 12).get(0);

        ProviderResult result = provider.search(query);

        assertThat(result.offers()).isEmpty();
        assertThat(result.errorMessage()).startsWith("SKIPPED").contains("open-jaw");
        assertThat(consumed).hasValue(0);
    }
}

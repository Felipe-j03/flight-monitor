package com.flightmonitor.infrastructure;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.flightmonitor.config.FlightMonitorProperties;
import com.flightmonitor.domain.model.Money;
import com.flightmonitor.domain.port.ExchangeRateProvider;
import com.flightmonitor.infrastructure.fx.CurrencyConverter;
import com.flightmonitor.infrastructure.fx.FrankfurterExchangeRateProvider;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Currency conversion must be auditable and must never fall back to a stale or invented rate.
 */
class FrankfurterExchangeRateProviderTest {

    private WireMockServer fx;
    private FrankfurterExchangeRateProvider provider;

    @BeforeEach
    void startStub() {
        fx = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        fx.start();
        provider = new FrankfurterExchangeRateProvider(propertiesPointingAt(fx.port()));
    }

    @AfterEach
    void stopStub() {
        fx.stop();
    }

    private static FlightMonitorProperties propertiesPointingAt(int port) {
        return new FlightMonitorProperties(
                List.of(),
                null,
                null,
                null,
                null,
                new FlightMonitorProperties.CurrencyConfig(
                        "GBP", "http://localhost:" + port, 360, 5),
                Map.of());
    }

    @Test
    @DisplayName("reads the live rate and records where and when it came from")
    void readsLiveRate() {
        fx.stubFor(get(urlPathEqualTo("/latest")).willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"amount\":1.0,\"base\":\"JPY\",\"date\":\"2026-09-07\","
                        + "\"rates\":{\"GBP\":0.00478}}")));

        Optional<ExchangeRateProvider.FxRate> rate = provider.rate("JPY", "GBP");

        assertThat(rate).isPresent();
        assertThat(rate.get().rate()).isEqualByComparingTo("0.00478");
        assertThat(rate.get().retrievedAt()).isNotNull();
        assertThat(rate.get().source()).contains("ECB");
    }

    @Test
    @DisplayName("converts using the live rate and keeps the original amount and currency")
    void convertsAndKeepsAuditTrail() {
        fx.stubFor(get(urlPathEqualTo("/latest")).willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"base\":\"JPY\",\"rates\":{\"GBP\":0.00478}}")));

        var quote = new CurrencyConverter(provider).toGbp(Money.of("260000", "JPY")).orElseThrow();

        assertThat(quote.original().amount()).isEqualByComparingTo("260000");
        assertThat(quote.original().currency()).isEqualTo("JPY");
        assertThat(quote.gbpAmount()).isEqualByComparingTo("1242.80");
        assertThat(quote.rateToGbp()).isEqualByComparingTo("0.00478");
        assertThat(quote.convertedAt()).isNotNull();
    }

    @Test
    @DisplayName("returns nothing when the rate cannot be obtained, rather than guessing")
    void noRateMeansNoConversion() {
        fx.stubFor(get(urlPathEqualTo("/latest")).willReturn(aResponse().withStatus(503)));

        assertThat(provider.rate("JPY", "GBP")).isEmpty();
        assertThat(new CurrencyConverter(provider).toGbp(Money.of("260000", "JPY"))).isEmpty();
    }

    @Test
    @DisplayName("caches within the TTL so a run does not hammer the FX endpoint")
    void cachesWithinTtl() {
        fx.stubFor(get(urlPathEqualTo("/latest")).willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"base\":\"BRL\",\"rates\":{\"GBP\":0.14}}")));

        provider.rate("BRL", "GBP");
        provider.rate("BRL", "GBP");
        provider.rate("BRL", "GBP");

        fx.verify(1, getRequestedFor(urlPathEqualTo("/latest")));
    }

    @Test
    @DisplayName("an identical currency pair needs no lookup at all")
    void identityNeedsNoCall() {
        assertThat(provider.rate("GBP", "GBP")).isPresent();
        assertThat(fx.getAllServeEvents()).isEmpty();
    }
}

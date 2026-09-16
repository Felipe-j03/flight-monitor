package com.flightmonitor.providers.serpapi;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.matching;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.flightmonitor.TestFixtures;
import com.flightmonitor.application.QueryPlanner;
import com.flightmonitor.config.ProviderConfig;
import com.flightmonitor.domain.model.Itinerary;
import com.flightmonitor.domain.port.ExchangeRateProvider;
import com.flightmonitor.domain.port.ProviderBudget;
import com.flightmonitor.domain.port.ProviderResult;
import com.flightmonitor.domain.port.SearchQuery;
import com.flightmonitor.infrastructure.catalog.CsvAirportCatalog;
import com.flightmonitor.infrastructure.fx.CurrencyConverter;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.client.RestTemplateBuilder;

/**
 * The Rio query against a stubbed SerpApi, shaped like the real response captured on 2026-09-16:
 * the cheapest morning flight leaves at 08:25, the one actually wanted leaves at 05:10.
 */
class SerpApiRioQueryTest {

    private static final String OUTBOUND = """
            {"best_flights": [
              {"flights": [{"departure_airport": {"id": "POA", "time": "2027-01-11 08:25"},
                            "arrival_airport": {"id": "GIG", "time": "2027-01-11 10:20"},
                            "duration": 115, "airline": "Azul", "flight_number": "AD 4000"}],
               "total_duration": 115, "price": 711, "departure_token": "TOKEN_0825"},
              {"flights": [{"departure_airport": {"id": "POA", "time": "2027-01-11 05:10"},
                            "arrival_airport": {"id": "GIG", "time": "2027-01-11 07:05"},
                            "duration": 115, "airline": "Gol", "flight_number": "G3 1000"}],
               "total_duration": 115, "price": 1021, "departure_token": "TOKEN_0510"},
              {"flights": [{"departure_airport": {"id": "POA", "time": "2027-01-11 09:10"},
                            "arrival_airport": {"id": "SDU", "time": "2027-01-11 11:05"},
                            "duration": 115, "airline": "LATAM", "flight_number": "LA 3100"}],
               "total_duration": 115, "price": 2031, "departure_token": "TOKEN_0910"}
            ]}
            """;

    private static final String RETURN = """
            {"best_flights": [
              {"flights": [{"departure_airport": {"id": "GIG", "time": "2027-01-13 09:40"},
                            "arrival_airport": {"id": "POA", "time": "2027-01-13 11:50"},
                            "duration": 130, "airline": "Gol", "flight_number": "G3 1001"}],
               "total_duration": 130, "price": 1021, "booking_token": "B"}
            ]}
            """;

    private WireMockServer serpapi;

    @BeforeEach
    void start() {
        serpapi = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        serpapi.start();
        serpapi.stubFor(get(urlPathEqualTo("/search.json")).atPriority(1)
                .withQueryParam("departure_token", matching(".+"))
                .willReturn(aResponse().withHeader("Content-Type", "application/json").withBody(RETURN)));
        serpapi.stubFor(get(urlPathEqualTo("/search.json")).atPriority(10)
                .willReturn(aResponse().withHeader("Content-Type", "application/json").withBody(OUTBOUND)));
    }

    @AfterEach
    void stop() {
        serpapi.stop();
    }

    private final AtomicInteger consumed = new AtomicInteger();

    private SerpApiGoogleFlightsProvider provider() {
        ProviderConfig config = new ProviderConfig(
                true, "http://localhost:" + serpapi.port(), "key", 5, 0, 1, 6000, 10, 230, 1);
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
                return Optional.of(new FxRate(from, to, new BigDecimal("0.14442"), Instant.now(), "t"));
            }
        });
        return new SerpApiGoogleFlightsProvider(config, budget, new CsvAirportCatalog(), fx,
                airports -> true, new RestTemplateBuilder());
    }

    private static SearchQuery rioQuery() {
        return new QueryPlanner(Clock.fixed(Instant.parse("2026-09-16T12:00:00Z"), ZoneOffset.UTC))
                .plan(TestFixtures.rioTrip(), 12).get(0);
    }

    private List<LoggedRequest> requests() {
        return serpapi.findAll(com.github.tomakehurst.wiremock.client.WireMock
                .getRequestedFor(urlPathEqualTo("/search.json")));
    }

    @Test
    @DisplayName("asks for both Rio airports, in reais, morning departures only, in one call")
    void sendsTheRightFirstCall() {
        provider().search(rioQuery());

        LoggedRequest first = requests().stream()
                .filter(request -> !request.queryParameter("departure_token").isPresent())
                .findFirst()
                .orElseThrow();

        assertThat(first.queryParameter("arrival_id").firstValue()).isEqualTo("GIG,SDU");
        assertThat(first.queryParameter("currency").firstValue()).isEqualTo("BRL");
        assertThat(first.queryParameter("outbound_times").firstValue()).isEqualTo("0,11");
        assertThat(first.queryParameter("return_date").firstValue()).isEqualTo("2027-01-13");
    }

    @Test
    @DisplayName("resolves the cheapest flight AND the one closest to 05:00, not the next cheapest")
    void resolvesCheapestAndEarliest() {
        ProviderResult result = provider().search(rioQuery());

        List<String> resolvedTokens = requests().stream()
                .filter(request -> request.queryParameter("departure_token").isPresent())
                .map(request -> request.queryParameter("departure_token").firstValue())
                .toList();

        assertThat(resolvedTokens)
                .as("08:25 is cheapest; 05:10 is closest to the preferred time; 09:10 is neither")
                .containsExactlyInAnyOrder("TOKEN_0825", "TOKEN_0510");

        assertThat(result.offers()).isNotEmpty();
        Itinerary early = result.offers().stream()
                .filter(offer -> offer.departureAt().getHour() == 5)
                .findFirst()
                .orElseThrow(() -> new AssertionError("the 05:10 flight was never priced"));
        assertThat(early.price().original().currency()).isEqualTo("BRL");
        assertThat(early.price().original().amount()).isEqualByComparingTo("1021");
        assertThat(early.price().gbpAmount()).isEqualByComparingTo("147.45");
    }

    @Test
    @DisplayName("charges three calls to the monthly budget: one search plus two resolutions")
    void accountsForThreeCalls() {
        provider().search(rioQuery());

        assertThat(consumed).hasValue(3);
        assertThat(requests()).hasSize(3);
    }
}

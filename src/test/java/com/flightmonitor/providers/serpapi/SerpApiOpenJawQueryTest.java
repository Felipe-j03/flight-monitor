package com.flightmonitor.providers.serpapi;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.matching;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.client.RestTemplateBuilder;

/**
 * The Tokyo multi-city ticket against a stubbed SerpApi, shaped like the real response captured on
 * 2026-09-16: Ethiopian to Rio, Gol + Ethiopian home from Porto Alegre, GBP 1,608 in total.
 */
class SerpApiOpenJawQueryTest {

    private static final String FIRST_LEG = """
            {"best_flights": [
              {"flights": [{"departure_airport": {"id": "NRT", "time": "2027-01-09 22:30"},
                            "arrival_airport": {"id": "ADD", "time": "2027-01-10 05:30"},
                            "duration": 780, "airline": "Ethiopian", "flight_number": "ET 673"},
                           {"departure_airport": {"id": "ADD", "time": "2027-01-10 09:00"},
                            "arrival_airport": {"id": "GRU", "time": "2027-01-10 15:30"},
                            "duration": 810, "airline": "Ethiopian", "flight_number": "ET 506"},
                           {"departure_airport": {"id": "GRU", "time": "2027-01-10 18:00"},
                            "arrival_airport": {"id": "GIG", "time": "2027-01-10 19:05"},
                            "duration": 65, "airline": "Gol", "flight_number": "G3 1200"}],
               "total_duration": 2075, "price": 1608, "departure_token": "TOKEN_1608"},
              {"flights": [{"departure_airport": {"id": "HND", "time": "2027-01-09 11:00"},
                            "arrival_airport": {"id": "LHR", "time": "2027-01-09 16:00"},
                            "duration": 840, "airline": "British Airways", "flight_number": "BA 6"},
                           {"departure_airport": {"id": "LHR", "time": "2027-01-09 21:00"},
                            "arrival_airport": {"id": "GIG", "time": "2027-01-10 05:00"},
                            "duration": 720, "airline": "British Airways", "flight_number": "BA 249"}],
               "total_duration": 1620, "price": 2310, "departure_token": "TOKEN_2310"}
            ]}
            """;

    private static final String ALL_ABOVE_CAP = """
            {"best_flights": [
              {"flights": [{"departure_airport": {"id": "HND", "time": "2027-01-09 11:00"},
                            "arrival_airport": {"id": "GIG", "time": "2027-01-10 05:00"},
                            "duration": 1620, "airline": "X", "flight_number": "X 1"}],
               "total_duration": 1620, "price": 2049, "departure_token": "TOKEN_2049"}
            ]}
            """;

    private static final String SECOND_LEG = """
            {"best_flights": [
              {"flights": [{"departure_airport": {"id": "POA", "time": "2027-01-19 06:00"},
                            "arrival_airport": {"id": "GRU", "time": "2027-01-19 07:40"},
                            "duration": 100, "airline": "Gol", "flight_number": "G3 1300"},
                           {"departure_airport": {"id": "GRU", "time": "2027-01-19 11:00"},
                            "arrival_airport": {"id": "ADD", "time": "2027-01-20 04:00"},
                            "duration": 780, "airline": "Ethiopian", "flight_number": "ET 507"},
                           {"departure_airport": {"id": "ADD", "time": "2027-01-20 06:30"},
                            "arrival_airport": {"id": "NRT", "time": "2027-01-21 07:00"},
                            "duration": 810, "airline": "Ethiopian", "flight_number": "ET 672"}],
               "total_duration": 2280, "price": 1608, "booking_token": "B"}
            ]}
            """;

    private WireMockServer serpapi;
    private int consumed;

    @BeforeEach
    void start() {
        serpapi = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        serpapi.start();
        serpapi.stubFor(get(urlPathEqualTo("/search.json")).atPriority(1)
                .withQueryParam("departure_token", matching(".+"))
                .willReturn(json(SECOND_LEG)));
    }

    @AfterEach
    void stop() {
        serpapi.stop();
    }

    private void firstLegReturns(String body) {
        serpapi.stubFor(get(urlPathEqualTo("/search.json")).atPriority(10).willReturn(json(body)));
    }

    private static com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder json(String body) {
        return aResponse().withHeader("Content-Type", "application/json").withBody(body);
    }

    private SerpApiGoogleFlightsProvider provider() {
        ProviderConfig config = new ProviderConfig(
                true, "http://localhost:" + serpapi.port(), "key", 5, 0, 1, 6000, 10, 230, 1);
        ProviderBudget budget = new ProviderBudget() {
            @Override public boolean tryConsume(String code, int max) {
                consumed++;
                return true;
            }
            @Override public int usedThisMonth(String code) { return consumed; }
            @Override public int pacedAllowance(int max) { return max; }
        };
        CurrencyConverter fx = new CurrencyConverter(new ExchangeRateProvider() {
            @Override public Optional<FxRate> rate(String from, String to) {
                return Optional.of(new FxRate(from, to, BigDecimal.ONE, Instant.now(), "t"));
            }
        });
        return new SerpApiGoogleFlightsProvider(config, budget, new CsvAirportCatalog(), fx,
                airports -> true, new RestTemplateBuilder());
    }

    private static SearchQuery tokyoQuery() {
        return new QueryPlanner(Clock.fixed(Instant.parse("2026-09-16T12:00:00Z"), ZoneOffset.UTC))
                .plan(TestFixtures.tokyoOpenJawTrip(), 12).get(0);
    }

    private List<LoggedRequest> requests() {
        return serpapi.findAll(com.github.tomakehurst.wiremock.client.WireMock
                .getRequestedFor(urlPathEqualTo("/search.json")));
    }

    @Test
    @DisplayName("sends ONE multi-city search: HND/NRT -> GIG/SDU on 09/01, POA -> HND/NRT on 19/01")
    void sendsMultiCityRequest() throws Exception {
        firstLegReturns(FIRST_LEG);
        provider().search(tokyoQuery());

        LoggedRequest first = requests().stream()
                .filter(request -> !request.queryParameter("departure_token").isPresent())
                .findFirst()
                .orElseThrow();

        assertThat(first.queryParameter("type").firstValue()).isEqualTo("3");
        assertThat(first.queryParameter("currency").firstValue()).isEqualTo("GBP");
        assertThat(first.queryParameter("return_date").isPresent()).isFalse();
        assertThat(first.queryParameter("departure_id").isPresent()).isFalse();

        JsonNode legs = new ObjectMapper().readTree(
                first.queryParameter("multi_city_json").firstValue());
        assertThat(legs).hasSize(2);
        assertThat(legs.get(0).get("departure_id").asText()).isEqualTo("HND,NRT");
        assertThat(legs.get(0).get("arrival_id").asText()).isEqualTo("GIG,SDU");
        assertThat(legs.get(0).get("date").asText()).isEqualTo("2027-01-09");
        assertThat(legs.get(1).get("departure_id").asText()).isEqualTo("POA");
        assertThat(legs.get(1).get("arrival_id").asText()).isEqualTo("HND,NRT");
        assertThat(legs.get(1).get("date").asText()).isEqualTo("2027-01-19");
    }

    @Test
    @DisplayName("the GBP 1,608 ticket comes back whole: Rio outbound, POA return, total price")
    void buildsTheOpenJawItinerary() {
        firstLegReturns(FIRST_LEG);
        ProviderResult result = provider().search(tokyoQuery());

        assertThat(result.offers()).singleElement().satisfies(offer -> {
            Itinerary itinerary = offer;
            assertThat(itinerary.destinationAirport()).isEqualTo("GIG");
            assertThat(itinerary.inbound().originAirport()).isEqualTo("POA");
            assertThat(itinerary.price().gbpAmount()).isEqualByComparingTo("1608");
        });
        assertThat(requests()).as("one search plus one resolution").hasSize(2);
    }

    @Test
    @DisplayName("options above GBP 2,000 are never resolved: no paid call to confirm a discard")
    void doesNotResolveAboveTheCap() {
        firstLegReturns(ALL_ABOVE_CAP);
        ProviderResult result = provider().search(tokyoQuery());

        assertThat(result.offers()).isEmpty();
        assertThat(requests()).as("only the first search, nothing resolved").hasSize(1);
    }

}

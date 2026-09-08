package com.flightmonitor.providers.serpapi;

import static org.assertj.core.api.Assertions.assertThat;

import com.flightmonitor.TestFixtures;
import com.flightmonitor.config.ProviderConfig;
import com.flightmonitor.domain.model.Itinerary;
import com.flightmonitor.domain.model.Money;
import com.flightmonitor.domain.port.ExchangeRateProvider;
import com.flightmonitor.domain.port.ProviderBudget;
import com.flightmonitor.domain.port.ProviderResult;
import com.flightmonitor.domain.port.RoutePreFilter;
import com.flightmonitor.domain.port.SearchQuery;
import com.flightmonitor.infrastructure.catalog.CsvAirportCatalog;
import com.flightmonitor.infrastructure.fx.CurrencyConverter;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.client.RestTemplateBuilder;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;

/**
 * A round trip costs two SerpApi calls, and the second one is only worth paying for if the outbound
 * could actually be accepted. Out of Tokyo the cheapest option is routinely a Gulf carrier, so
 * without this the free tier is spent resolving itineraries that are then thrown away.
 */
class SerpApiPreFilterTest {

    private static final SearchQuery QUERY = new SearchQuery(
            TestFixtures.TRIP_ID, "HND", "POA",
            LocalDate.of(2027, 1, 9), LocalDate.of(2027, 1, 21), 1, "ECONOMY", 0);

    /** Two outbound options: the cheaper one via Dubai, the pricier one via Frankfurt. */
    private static final String OUTBOUND_RESPONSE = """
            {"best_flights": [
              {"flights": [
                 {"departure_airport": {"id": "HND", "time": "2027-01-09 22:00"},
                  "arrival_airport":   {"id": "DXB", "time": "2027-01-10 05:00"},
                  "duration": 660, "airline": "Emirates", "flight_number": "EK 313"},
                 {"departure_airport": {"id": "DXB", "time": "2027-01-10 08:00"},
                  "arrival_airport":   {"id": "POA", "time": "2027-01-10 20:00"},
                  "duration": 900, "airline": "Emirates", "flight_number": "EK 261"}],
               "layovers": [{"duration": 180, "id": "DXB"}],
               "total_duration": 1740, "price": 1200,
               "departure_token": "TOKEN_DXB"},
              {"flights": [
                 {"departure_airport": {"id": "HND", "time": "2027-01-09 09:55"},
                  "arrival_airport":   {"id": "FRA", "time": "2027-01-09 15:20"},
                  "duration": 805, "airline": "ANA", "flight_number": "NH 203"},
                 {"departure_airport": {"id": "FRA", "time": "2027-01-09 17:30"},
                  "arrival_airport":   {"id": "POA", "time": "2027-01-10 05:05"},
                  "duration": 810, "airline": "Lufthansa", "flight_number": "LH 500"}],
               "layovers": [{"duration": 130, "id": "FRA"}],
               "total_duration": 1870, "price": 1450,
               "departure_token": "TOKEN_FRA"}
            ]}
            """;

    private static final String RETURN_RESPONSE = """
            {"best_flights": [
              {"flights": [
                 {"departure_airport": {"id": "POA", "time": "2027-01-21 06:10"},
                  "arrival_airport":   {"id": "FRA", "time": "2027-01-22 04:35"},
                  "duration": 920, "airline": "Lufthansa", "flight_number": "LH 507"},
                 {"departure_airport": {"id": "FRA", "time": "2027-01-22 07:00"},
                  "arrival_airport":   {"id": "HND", "time": "2027-01-23 03:05"},
                  "duration": 725, "airline": "ANA", "flight_number": "NH 204"}],
               "layovers": [{"duration": 145, "id": "FRA"}],
               "total_duration": 1975, "price": 1450,
               "booking_token": "BOOK_FRA"}
            ]}
            """;

    private WireMockServer serpapi;

    @BeforeEach
    void startStub() {
        serpapi = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        serpapi.start();
    }

    @AfterEach
    void stopStub() {
        serpapi.stop();
    }

    private SerpApiGoogleFlightsProvider providerWith(RoutePreFilter preFilter, int maxOptions) {
        ProviderConfig config = new ProviderConfig(
                true, "http://localhost:" + serpapi.port(), "test-key",
                5, 0, 1, 600, 10, 0, maxOptions);
        ProviderBudget unlimited = new ProviderBudget() {
            @Override public boolean tryConsume(String code, int max) { return true; }
            @Override public int usedThisMonth(String code) { return 0; }
            @Override public int pacedAllowance(int max) { return max; }
        };
        CurrencyConverter gbp = new CurrencyConverter(new ExchangeRateProvider() {
            @Override public Optional<FxRate> rate(String from, String to) {
                return Optional.of(new FxRate(from, to, BigDecimal.ONE, Instant.now(), "test"));
            }
        });
        return new SerpApiGoogleFlightsProvider(
                config, unlimited, new CsvAirportCatalog(), gbp, preFilter,
                new RestTemplateBuilder());
    }

    /**
     * Both stubs match a phase-two request, so the priorities are explicit: the token-bearing one
     * must win, otherwise phase two is answered with the phase-one payload and the test silently
     * measures nothing.
     */
    private void stubBothPhases() {
        serpapi.stubFor(get(urlPathEqualTo("/search.json"))
                .atPriority(1)
                .withQueryParam("departure_token", com.github.tomakehurst.wiremock.client.WireMock
                        .matching(".+"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(RETURN_RESPONSE)));
        serpapi.stubFor(get(urlPathEqualTo("/search.json"))
                .atPriority(10)
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(OUTBOUND_RESPONSE)));
    }

    /** Blocks the United Arab Emirates, exactly as the real configuration does. */
    private static final RoutePreFilter BLOCKS_UAE = airports ->
            airports.stream().noneMatch(iata -> "DXB".equals(iata));

    private static final RoutePreFilter ALLOWS_EVERYTHING = airports -> true;

    @Test
    @DisplayName("skips the cheaper blocked outbound and resolves the acceptable one instead")
    void resolvesTheCheapestAcceptableOption() {
        stubBothPhases();

        ProviderResult result = providerWith(BLOCKS_UAE, 1).search(QUERY);

        assertThat(result.success()).isTrue();
        assertThat(result.offers())
                .as("the Dubai option is cheaper but disqualified; Frankfurt must be resolved")
                .hasSize(1);
        Itinerary offer = result.offers().get(0);
        assertThat(offer.connectionAirports()).contains("FRA").doesNotContain("DXB");

        serpapi.verify(1, getRequestedFor(urlPathEqualTo("/search.json"))
                .withQueryParam("departure_token",
                        com.github.tomakehurst.wiremock.client.WireMock.equalTo("TOKEN_FRA")));
        serpapi.verify(0, getRequestedFor(urlPathEqualTo("/search.json"))
                .withQueryParam("departure_token",
                        com.github.tomakehurst.wiremock.client.WireMock.equalTo("TOKEN_DXB")));
    }

    @Test
    @DisplayName("without the pre-filter the budget goes on the blocked route, as it used to")
    void withoutPreFilterTheCheapestWins() {
        stubBothPhases();

        ProviderResult result = providerWith(ALLOWS_EVERYTHING, 1).search(QUERY);

        assertThat(result.offers()).hasSize(1);
        assertThat(result.offers().get(0).connectionAirports())
                .as("this is the behaviour the pre-filter exists to avoid")
                .contains("DXB");
    }

    @Test
    @DisplayName("spends nothing on phase two when every outbound is blocked")
    void spendsNothingWhenEverythingIsBlocked() {
        stubBothPhases();
        RoutePreFilter blocksEverything = airports -> false;

        ProviderResult result = providerWith(blocksEverything, 1).search(QUERY);

        assertThat(result.success()).isTrue();
        assertThat(result.offers()).isEmpty();
        serpapi.verify(0, getRequestedFor(urlPathEqualTo("/search.json"))
                .withQueryParam("departure_token",
                        com.github.tomakehurst.wiremock.client.WireMock.matching(".+")));
    }

    @Test
    @DisplayName("sends the Base64 departure_token without double-encoding it")
    void doesNotDoubleEncodeTheToken() {
        // A real token ends in '=' padding. Encoding it and then handing RestTemplate a String
        // makes it re-encode the '%', turning "%3D" into "%253D" and earning a 400 from SerpApi.
        String paddedToken = "WyJDalJJTTNaSFJUWTNSVWR0TWxWQlFscFlSWGRDUnkwdCJd==";
        String outbound = OUTBOUND_RESPONSE.replace("TOKEN_FRA", paddedToken);

        serpapi.stubFor(get(urlPathEqualTo("/search.json"))
                .atPriority(1)
                .withQueryParam("departure_token",
                        com.github.tomakehurst.wiremock.client.WireMock.matching(".+"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(RETURN_RESPONSE)));
        serpapi.stubFor(get(urlPathEqualTo("/search.json"))
                .atPriority(10)
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(outbound)));

        providerWith(BLOCKS_UAE, 1).search(QUERY);

        String phaseTwoUrl = serpapi.getAllServeEvents().stream()
                .map(event -> event.getRequest().getUrl())
                .filter(url -> url.contains("departure_token"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("phase two was never requested"));

        assertThat(phaseTwoUrl)
                .as("'%%25' means the escape character itself got escaped again")
                .doesNotContain("%25");
        assertThat(serpapi.getAllServeEvents().stream()
                .filter(event -> event.getRequest().getUrl().contains("departure_token"))
                .findFirst()
                .orElseThrow()
                .getRequest()
                .queryParameter("departure_token")
                .firstValue())
                .as("the server must receive exactly the token the payload gave us")
                .isEqualTo(paddedToken);
    }

    @Test
    @DisplayName("the pre-filter only reorders spending; it never widens what is accepted")
    void preFilterIsNotTheAuthority() {
        Collection<String> viaDubai = java.util.List.of("HND", "DXB", "POA");

        assertThat(BLOCKS_UAE.plausible(viaDubai)).isFalse();
        assertThat(ALLOWS_EVERYTHING.plausible(viaDubai))
                .as("a permissive pre-filter still leaves SafetyRule to reject it downstream")
                .isTrue();
    }
}

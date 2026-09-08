package com.flightmonitor.providers.serpapi;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flightmonitor.domain.model.FlightSegment;
import com.flightmonitor.infrastructure.catalog.CsvAirportCatalog;
import com.flightmonitor.providers.serpapi.SerpApiResponseParser.ParsedOption;
import java.io.InputStream;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Parsing is the layer where a wrong assumption becomes a wrong alert, so it is tested against a
 * payload in the real response shape rather than through the HTTP client.
 */
class SerpApiResponseParserTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final SerpApiResponseParser parser = new SerpApiResponseParser(new CsvAirportCatalog());

    private JsonNode payload;

    @BeforeEach
    void loadPayload() throws Exception {
        try (InputStream in = getClass().getClassLoader()
                .getResourceAsStream("fixtures/sample-google-flights.json")) {
            payload = mapper.readTree(in);
        }
    }

    @Test
    @DisplayName("reads every option with its segments, layovers, duration and price")
    void parsesOptions() {
        List<ParsedOption> options = parser.parseOptions(payload, "GBP");

        assertThat(options).hasSize(2);

        ParsedOption viaFrankfurt = options.get(0);
        assertThat(viaFrankfurt.segments()).hasSize(3);
        assertThat(viaFrankfurt.layovers()).extracting(layover -> layover.airport())
                .containsExactly("FRA", "GRU");
        assertThat(viaFrankfurt.totalDuration()).isEqualTo(Duration.ofMinutes(1870));
        assertThat(viaFrankfurt.price().amount()).isEqualByComparingTo("1387");
        assertThat(viaFrankfurt.price().currency()).isEqualTo("GBP");
        assertThat(viaFrankfurt.departureToken()).isEqualTo("FIXTURE_DEPARTURE_TOKEN_FRA");
    }

    @Test
    @DisplayName("resolves naive local times against the departure airport's own timezone")
    void resolvesTimezones() {
        ParsedOption viaFrankfurt = parser.parseOptions(payload, "GBP").get(0);
        FlightSegment first = viaFrankfurt.segments().get(0);

        // "2027-01-09 09:55" at HND is Japan Standard Time, not UTC.
        assertThat(first.departureAt())
                .isEqualTo(OffsetDateTime.of(2027, 1, 9, 9, 55, 0, 0, ZoneOffset.ofHours(9)));
        // "2027-01-09 15:20" at FRA is Central European Time in January.
        assertThat(first.arrivalAt())
                .isEqualTo(OffsetDateTime.of(2027, 1, 9, 15, 20, 0, 0, ZoneOffset.ofHours(1)));
    }

    @Test
    @DisplayName("keeps the connecting airports the safety rule needs")
    void exposesConnectingAirports() {
        ParsedOption viaDoha = parser.parseOptions(payload, "GBP").get(1);

        assertThat(viaDoha.segments())
                .extracting(FlightSegment::departureAirport)
                .containsExactly("HND", "DOH", "GRU");
        assertThat(viaDoha.layovers()).extracting(layover -> layover.airport()).contains("DOH");
    }

    @Test
    @DisplayName("reads baggage only when the payload states it")
    void readsBaggageWhenStated() {
        List<ParsedOption> options = parser.parseOptions(payload, "GBP");

        assertThat(options.get(0).baggage().known()).isTrue();
        assertThat(options.get(0).baggage().checkedPieces()).isEqualTo(1);
        assertThat(options.get(0).baggage().cabinIncluded()).isTrue();

        // The Doha option says nothing about bags, so nothing is claimed about them.
        assertThat(options.get(1).baggage().known()).isFalse();
    }

    @Test
    @DisplayName("surfaces SerpApi's own error field, which arrives with HTTP 200")
    void readsErrorField() throws Exception {
        JsonNode error = mapper.readTree("{\"error\": \"Invalid API key\"}");

        assertThat(parser.readError(error)).contains("Invalid API key");
        assertThat(parser.readError(payload)).isEmpty();
    }

    @Test
    @DisplayName("skips an unparseable option instead of failing the whole response")
    void skipsBrokenOption() throws Exception {
        JsonNode mixed = mapper.readTree("""
                {"best_flights": [
                  {"flights": [{"departure_airport": {"id": "HND", "time": "2027-01-09 09:55"}}],
                   "price": 1000},
                  {"flights": [], "price": 900}
                ]}
                """);

        // The first has no arrival airport and the second has no segments: both are dropped, and
        // nothing is invented to fill the gaps.
        assertThat(parser.parseOptions(mixed, "GBP")).isEmpty();
    }

    @Test
    @DisplayName("leaves a time unset rather than assuming UTC for an unknown airport")
    void unknownAirportLeavesTimeNull() {
        assertThat(parser.toOffsetDateTime("2027-01-09 09:55", "ZZZ")).isNull();
        assertThat(parser.toOffsetDateTime(null, "HND")).isNull();
    }
}

package com.flightmonitor.providers.travelpayouts;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flightmonitor.TestFixtures;
import com.flightmonitor.domain.model.Itinerary;
import com.flightmonitor.domain.model.Money;
import com.flightmonitor.domain.model.PriceQuote;
import com.flightmonitor.domain.model.RouteDetailLevel;
import com.flightmonitor.domain.port.ExchangeRateProvider;
import com.flightmonitor.domain.port.SearchQuery;
import com.flightmonitor.infrastructure.fx.CurrencyConverter;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The Travelpayouts cache is the source most likely to be over-trusted: it looks like a normal
 * flight result but omits the connections. These tests pin down that its output is honest about
 * what it does not know.
 */
class TravelpayoutsResponseParserTest {

    private static final SearchQuery QUERY = new SearchQuery(
            TestFixtures.TRIP_ID, "HND", "POA",
            LocalDate.of(2027, 1, 9), LocalDate.of(2027, 1, 21), 1, "ECONOMY", 0);

    private final ObjectMapper mapper = new ObjectMapper();

    /** Rates are irrelevant here: the fixture is already priced in GBP. */
    private final CurrencyConverter currency = new CurrencyConverter(new ExchangeRateProvider() {
        @Override
        public Optional<FxRate> rate(String from, String to) {
            return Optional.of(new FxRate(from, to, BigDecimal.ONE, Instant.now(), "test"));
        }
    });

    private final TravelpayoutsResponseParser parser =
            new TravelpayoutsResponseParser(currency, "https://www.aviasales.com");

    private JsonNode payload;

    @BeforeEach
    void loadPayload() throws Exception {
        try (InputStream in = getClass().getClassLoader()
                .getResourceAsStream("fixtures/travelpayouts-prices-for-dates.json")) {
            payload = mapper.readTree(in);
        }
    }

    @Test
    @DisplayName("parses price, airline, dates and durations from the cache rows")
    void parsesRows() {
        List<Itinerary> offers = parser.parse(payload, QUERY, "travelpayouts");

        assertThat(offers).hasSize(3);

        Itinerary first = offers.get(0);
        assertThat(first.price().gbpAmount()).isEqualByComparingTo("1412");
        assertThat(first.price().original().currency()).isEqualTo("GBP");
        assertThat(first.originAirport()).isEqualTo("HND");
        assertThat(first.destinationAirport()).isEqualTo("POA");
        assertThat(first.departureAt())
                .isEqualTo(OffsetDateTime.of(2027, 1, 9, 9, 55, 0, 0, ZoneOffset.ofHours(9)));
        assertThat(first.airlines()).containsExactly("NH");
    }

    @Test
    @DisplayName("always reports COUNTS_ONLY, because connections are never disclosed")
    void alwaysCountsOnly() {
        List<Itinerary> offers = parser.parse(payload, QUERY, "travelpayouts");

        assertThat(offers).allSatisfy(offer ->
                assertThat(offer.detailLevel()).isEqualTo(RouteDetailLevel.COUNTS_ONLY));
        assertThat(offers.get(0).connectionAirports()).isEmpty();
    }

    @Test
    @DisplayName("reports the real stop count even though only one segment is modelled")
    void reportsDeclaredStops() {
        Itinerary first = parser.parse(payload, QUERY, "travelpayouts").get(0);

        assertThat(first.outbound().segments()).hasSize(1);
        assertThat(first.outbound().stops()).isEqualTo(2);
        assertThat(first.inbound().stops()).isEqualTo(2);
        assertThat(first.totalStops()).isEqualTo(4);
    }

    @Test
    @DisplayName("derives arrival from the reported duration in minutes")
    void derivesArrivalFromDuration() {
        Itinerary first = parser.parse(payload, QUERY, "travelpayouts").get(0);

        assertThat(first.outbound().totalDuration()).isEqualTo(Duration.ofMinutes(1870));
        assertThat(first.arrivalAt())
                .isEqualTo(first.departureAt().plusMinutes(1870));
    }

    @Test
    @DisplayName("turns the relative link into a usable booking url")
    void buildsBookingUrl() {
        Itinerary first = parser.parse(payload, QUERY, "travelpayouts").get(0);

        assertThat(first.bookingUrl())
                .isEqualTo("https://www.aviasales.com/search/HND0901POA2101?t=NH203&marker=test");
    }

    @Test
    @DisplayName("leaves the booking url null when the row has no link, rather than inventing one")
    void noLinkMeansNullUrl() {
        Itinerary withoutLink = parser.parse(payload, QUERY, "travelpayouts").get(2);

        assertThat(withoutLink.bookingUrl()).isNull();
        assertThat(withoutLink.isRoundTrip()).isFalse();
    }

    @Test
    @DisplayName("never claims baggage information it was not given")
    void baggageAlwaysUnknown() {
        assertThat(parser.parse(payload, QUERY, "travelpayouts"))
                .allSatisfy(offer -> assertThat(offer.baggage().known()).isFalse());
    }

    @Test
    @DisplayName("skips an offer when no live FX rate is available rather than guessing one")
    void skipsWhenNoFxRate() throws Exception {
        CurrencyConverter noRates = new CurrencyConverter((from, to) -> Optional.empty());
        TravelpayoutsResponseParser strict =
                new TravelpayoutsResponseParser(noRates, "https://www.aviasales.com");
        JsonNode inYen = mapper.readTree("""
                {"success": true, "currency": "jpy", "data": [
                  {"origin":"HND","destination":"POA","price":260000,
                   "departure_at":"2027-01-09T09:55:00+09:00","transfers":2,"duration_to":1870}
                ]}
                """);

        assertThat(strict.parse(inYen, QUERY, "travelpayouts")).isEmpty();
    }

    @Test
    @DisplayName("surfaces success=false as an error")
    void readsErrorResponse() throws Exception {
        JsonNode failure = mapper.readTree("{\"success\": false, \"error\": \"invalid token\"}");

        assertThat(parser.readError(failure)).contains("invalid token");
        assertThat(parser.readError(payload)).isEmpty();
    }

    @Test
    @DisplayName("a quote already in GBP records a rate of exactly 1")
    void gbpNeedsNoConversion() {
        PriceQuote quote = currency.toGbp(Money.of("1412", "GBP")).orElseThrow();

        assertThat(quote.rateToGbp()).isEqualByComparingTo("1");
        assertThat(quote.gbpAmount()).isEqualByComparingTo("1412");
    }
}

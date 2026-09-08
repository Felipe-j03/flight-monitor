package com.flightmonitor.providers.fixture;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flightmonitor.config.FlightMonitorProperties;
import com.flightmonitor.config.ProviderConfig;
import com.flightmonitor.domain.model.Itinerary;
import com.flightmonitor.domain.model.ItineraryLeg;
import com.flightmonitor.domain.model.PriceQuote;
import com.flightmonitor.domain.model.RouteDetailLevel;
import com.flightmonitor.domain.port.AirportCatalog;
import com.flightmonitor.domain.port.FlightSearchProvider;
import com.flightmonitor.domain.port.ProviderResult;
import com.flightmonitor.domain.port.SearchQuery;
import com.flightmonitor.infrastructure.fx.CurrencyConverter;
import com.flightmonitor.providers.serpapi.SerpApiResponseParser;
import com.flightmonitor.providers.serpapi.SerpApiResponseParser.ParsedOption;
import java.io.InputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * Replays a canned Google-Flights-shaped payload so the full pipeline — search, filter, store,
 * compare, notify — can be exercised end to end without any API key.
 *
 * <p><b>This provider never produces real prices and must never be mistaken for one.</b> It is
 * disabled by default, it logs a warning on every call, and every offer it emits carries the source
 * code {@value #CODE}, which the notifier and the dashboard render with an explicit
 * "SIMULATED DATA" banner. It exists for development and for verifying the plumbing, nothing else.
 */
@Component
public class FixtureFlightProvider implements FlightSearchProvider {

    public static final String CODE = "fixture";

    private static final Logger log = LoggerFactory.getLogger(FixtureFlightProvider.class);
    private static final String RESOURCE = "fixtures/sample-google-flights.json";

    private final ProviderConfig config;
    private final SerpApiResponseParser parser;
    private final CurrencyConverter currency;
    private final ObjectMapper mapper = new ObjectMapper();

    public FixtureFlightProvider(
            FlightMonitorProperties properties,
            AirportCatalog catalog,
            CurrencyConverter currency) {
        this.config = properties.provider(CODE);
        this.parser = new SerpApiResponseParser(catalog);
        this.currency = currency;
    }

    @Override
    public String code() {
        return CODE;
    }

    @Override
    public String displayName() {
        return "Local fixture (SIMULATED DATA - not real prices)";
    }

    @Override
    public boolean available() {
        return config.enabled();
    }

    @Override
    public RouteDetailLevel routeDetailLevel() {
        return RouteDetailLevel.FULL_ITINERARY;
    }

    @Override
    public int remainingQueriesThisRun() {
        return Integer.MAX_VALUE;
    }

    @Override
    public ProviderResult search(SearchQuery query) {
        log.warn("FIXTURE PROVIDER ACTIVE - returning SIMULATED offers for {}. "
                + "Disable flight-monitor.providers.fixture.enabled before trusting any alert.",
                query.describe());

        long startedAt = System.nanoTime();
        try (InputStream in = new ClassPathResource(RESOURCE).getInputStream()) {
            JsonNode root = mapper.readTree(in);
            List<ParsedOption> options = parser.parseOptions(root, "GBP");
            List<Itinerary> offers = new ArrayList<>();

            for (ParsedOption option : options) {
                Optional<PriceQuote> quote = currency.toGbp(option.price());
                if (quote.isEmpty()) {
                    continue;
                }
                ItineraryLeg outbound = new ItineraryLeg(
                        option.segments(), option.layovers(), option.totalDuration(), null)
                        .withDerivedDurationIfMissing();
                offers.add(Itinerary.builder()
                        .tripId(query.tripId())
                        .source(CODE)
                        .providerOfferId(option.bookingToken())
                        .observedAt(Instant.now())
                        .outbound(outbound)
                        .inbound(returnLegFor(root, option))
                        .price(quote.get())
                        .baggage(option.baggage())
                        .fareClass(option.travelClass())
                        .bookingUrl(null)
                        .searchUrl(null)
                        .detailLevel(RouteDetailLevel.FULL_ITINERARY)
                        .build());
            }

            long elapsed = (System.nanoTime() - startedAt) / 1_000_000L;
            return ProviderResult.success(CODE, query, offers, elapsed);
        } catch (Exception e) {
            long elapsed = (System.nanoTime() - startedAt) / 1_000_000L;
            return ProviderResult.failure(CODE, query, "fixture unreadable: " + e, elapsed);
        }
    }

    /** The fixture keeps return options in a sibling array so round trips can be exercised too. */
    private ItineraryLeg returnLegFor(JsonNode root, ParsedOption outbound) {
        List<ParsedOption> returns = parser.parseOptions(root.path("return_flights_fixture"), "GBP");
        if (returns.isEmpty()) {
            return null;
        }
        ParsedOption chosen = returns.get(0);
        return new ItineraryLeg(chosen.segments(), chosen.layovers(), chosen.totalDuration(), null)
                .withDerivedDurationIfMissing();
    }
}

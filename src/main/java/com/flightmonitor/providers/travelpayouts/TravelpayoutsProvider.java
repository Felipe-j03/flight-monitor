package com.flightmonitor.providers.travelpayouts;

import com.fasterxml.jackson.databind.JsonNode;
import com.flightmonitor.config.FlightMonitorProperties;
import com.flightmonitor.config.ProviderConfig;
import com.flightmonitor.domain.model.Itinerary;
import com.flightmonitor.domain.model.RouteDetailLevel;
import com.flightmonitor.domain.port.ProviderBudget;
import com.flightmonitor.domain.port.ProviderResult;
import com.flightmonitor.domain.port.SearchQuery;
import com.flightmonitor.infrastructure.fx.CurrencyConverter;
import com.flightmonitor.providers.AbstractHttpFlightProvider;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Travelpayouts (Aviasales) flight-data cache.
 *
 * <p>Its role is breadth, not precision. Registration is free and self-serve, there is no per-call
 * charge, and the rate limits are generous, so this provider sweeps every date and airport
 * combination on every run and keeps the price history populated even when the metered primary
 * source has nothing left in its monthly budget.
 *
 * <p>The trade-off is disclosure: the cache reports a transfer <em>count</em> and never the
 * connecting airports, so its offers cannot clear the blocked-country rule and are rejected by
 * default with {@code ROUTE_UNVERIFIABLE}. They still enter the price history as a trend signal.
 * Set {@code flight-monitor.safety.reject-unverifiable-routes=false} to accept them anyway —
 * that is an explicit decision to trade the routing guarantee for coverage.
 */
@Component
public class TravelpayoutsProvider extends AbstractHttpFlightProvider {

    public static final String CODE = "travelpayouts";

    private static final String DEFAULT_BASE_URL = "https://api.travelpayouts.com";
    private static final String BOOKING_BASE_URL = "https://www.aviasales.com";
    private static final String CURRENCY = "gbp";

    private final RestTemplate http;
    private final TravelpayoutsResponseParser parser;
    private final String baseUrl;

    @org.springframework.beans.factory.annotation.Autowired
    public TravelpayoutsProvider(
            FlightMonitorProperties properties,
            ProviderBudget budget,
            CurrencyConverter currency,
            RestTemplateBuilder restTemplateBuilder) {
        this(properties.provider(CODE), budget, currency, restTemplateBuilder);
    }

    TravelpayoutsProvider(
            ProviderConfig config,
            ProviderBudget budget,
            CurrencyConverter currency,
            RestTemplateBuilder restTemplateBuilder) {
        super(config, budget);
        this.parser = new TravelpayoutsResponseParser(currency, BOOKING_BASE_URL);
        this.baseUrl = config.baseUrl() == null || config.baseUrl().isBlank()
                ? DEFAULT_BASE_URL
                : config.baseUrl();
        this.http = restTemplateBuilder
                .setConnectTimeout(Duration.ofSeconds(config.timeoutSeconds()))
                .setReadTimeout(Duration.ofSeconds(config.timeoutSeconds()))
                .additionalMessageConverters(new MappingJackson2HttpMessageConverter())
                .build();
    }

    @Override
    public String code() {
        return CODE;
    }

    @Override
    public String displayName() {
        return "Aviasales cache (via Travelpayouts)";
    }

    @Override
    public RouteDetailLevel routeDetailLevel() {
        return RouteDetailLevel.COUNTS_ONLY;
    }

    @Override
    protected ProviderResult performSearch(SearchQuery query) {
        long startedAt = System.nanoTime();

        // The cache takes a single destination per call, so a combined query ("GIG,SDU") is asked
        // airport by airport. The calls are free, so this costs nothing but a few milliseconds.
        List<Itinerary> offers = new java.util.ArrayList<>();
        for (String destination : query.destinations()) {
            SearchQuery single = query.forDestination(destination);
            JsonNode body = http.getForObject(buildUrl(single), JsonNode.class);
            Optional<String> error = parser.readError(body);
            if (error.isPresent()) {
                return ProviderResult.failure(CODE, query, error.get(), elapsedMillis(startedAt));
            }
            offers.addAll(parser.parse(body, single, CODE));
        }
        log.info("SEARCH_COMPLETED provider={} query={} offers={} elapsed={}ms",
                CODE, query.describe(), offers.size(), elapsedMillis(startedAt));
        return ProviderResult.success(CODE, query, offers, elapsedMillis(startedAt));
    }

    private URI buildUrl(SearchQuery query) {
        UriComponentsBuilder builder = UriComponentsBuilder
                .fromHttpUrl(baseUrl + "/aviasales/v3/prices_for_dates")
                .queryParam("origin", query.origin())
                .queryParam("destination", query.destination())
                .queryParam("departure_at", query.departureDate())
                .queryParam("currency", CURRENCY)
                .queryParam("sorting", "price")
                .queryParam("direct", false)
                .queryParam("limit", 30)
                .queryParam("one_way", !query.isRoundTrip())
                .queryParam("token", config.apiKey());
        if (query.isRoundTrip()) {
            builder.queryParam("return_at", query.returnDate());
        }
        // A URI, not a String: RestTemplate treats a String as a URI template and would encode it a
        // second time, turning an already-escaped "%3D" into "%253D".

        return builder.build().encode().toUri();
    }
}

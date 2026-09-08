package com.flightmonitor.providers.serpapi;

import com.fasterxml.jackson.databind.JsonNode;
import com.flightmonitor.config.FlightMonitorProperties;
import com.flightmonitor.config.ProviderConfig;
import com.flightmonitor.domain.model.Itinerary;
import com.flightmonitor.domain.model.ItineraryLeg;
import com.flightmonitor.domain.model.Money;
import com.flightmonitor.domain.model.PriceQuote;
import com.flightmonitor.domain.model.RouteDetailLevel;
import com.flightmonitor.domain.port.AirportCatalog;
import com.flightmonitor.domain.port.ProviderBudget;
import com.flightmonitor.domain.port.ProviderResult;
import com.flightmonitor.domain.port.RoutePreFilter;
import com.flightmonitor.domain.port.SearchQuery;
import com.flightmonitor.infrastructure.fx.CurrencyConverter;
import com.flightmonitor.providers.AbstractHttpFlightProvider;
import com.flightmonitor.providers.serpapi.SerpApiResponseParser.ParsedOption;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Google Flights data through SerpApi.
 *
 * <p>Chosen as the primary source because, of everything still self-serve in 2026, it is the only
 * free option that returns a <em>full itinerary</em>: every segment and every connecting airport.
 * That is what makes the blocked-country rule enforceable rather than advisory.
 *
 * <p><b>A round-trip search costs two API calls.</b> SerpApi's first response lists outbound
 * options carrying a {@code departure_token}; the matching return flights only exist in a second
 * call made with that token. The provider therefore reports a call cost of
 * {@code 1 + maxOptionsPerSearch} and the monthly budget guard accounts for it, so the free tier
 * cannot be overrun by accident.
 *
 * <p><b>Booking links.</b> SerpApi does not return a direct deep link to a specific fare, so
 * {@code bookingUrl} is left null rather than fabricated. A plain Google Flights <em>search</em>
 * URL for the same route and dates is exposed separately as {@code searchUrl} and is labelled as
 * such in notifications.
 */
@Component
public class SerpApiGoogleFlightsProvider extends AbstractHttpFlightProvider {

    public static final String CODE = "serpapi";

    private static final String DEFAULT_BASE_URL = "https://serpapi.com";
    private static final String CURRENCY = "GBP";

    private final RestTemplate http;
    private final SerpApiResponseParser parser;
    private final CurrencyConverter currency;
    private final String baseUrl;
    private final RoutePreFilter preFilter;

    @org.springframework.beans.factory.annotation.Autowired
    public SerpApiGoogleFlightsProvider(
            FlightMonitorProperties properties,
            ProviderBudget budget,
            AirportCatalog catalog,
            CurrencyConverter currency,
            RoutePreFilter preFilter,
            RestTemplateBuilder restTemplateBuilder) {
        this(properties.provider(CODE), budget, catalog, currency, preFilter, restTemplateBuilder);
    }

    SerpApiGoogleFlightsProvider(
            ProviderConfig config,
            ProviderBudget budget,
            AirportCatalog catalog,
            CurrencyConverter currency,
            RoutePreFilter preFilter,
            RestTemplateBuilder restTemplateBuilder) {
        super(config, budget);
        this.parser = new SerpApiResponseParser(catalog);
        this.currency = currency;
        this.preFilter = preFilter;
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
        return "Google Flights (via SerpApi)";
    }

    @Override
    public RouteDetailLevel routeDetailLevel() {
        return RouteDetailLevel.FULL_ITINERARY;
    }

    @Override
    protected int callCostPerSearch() {
        return 1 + config.maxOptionsPerSearch();
    }

    @Override
    protected ProviderResult performSearch(SearchQuery query) {
        long startedAt = System.nanoTime();

        JsonNode outboundResponse = call(buildUrl(query, null));
        Optional<String> error = parser.readError(outboundResponse);
        if (error.isPresent()) {
            return ProviderResult.failure(CODE, query, error.get(), elapsedMillis(startedAt));
        }

        List<ParsedOption> outboundOptions = parser.parseOptions(outboundResponse, CURRENCY);
        if (outboundOptions.isEmpty()) {
            log.info("SEARCH_COMPLETED provider={} query={} offers=0 (no options returned)",
                    CODE, query.describe());
            return ProviderResult.success(CODE, query, List.of(), elapsedMillis(startedAt));
        }

        if (!query.isRoundTrip()) {
            List<Itinerary> oneWays = new ArrayList<>();
            for (ParsedOption option : outboundOptions) {
                buildItinerary(query, option, null, option).ifPresent(oneWays::add);
            }
            return ProviderResult.success(CODE, query, oneWays, elapsedMillis(startedAt));
        }

        // Drop outbound options that a blocked country already disqualifies, BEFORE paying for the
        // second call that resolves their return legs. Out of Tokyo the cheapest option is very
        // often a Gulf carrier, so without this the budget is spent resolving itineraries that the
        // safety rule then discards, and the search comes back empty having cost full price.
        List<ParsedOption> withToken = outboundOptions.stream()
                .filter(option -> option.departureToken() != null)
                .sorted(Comparator.comparing(option -> option.price().amount()))
                .toList();

        List<ParsedOption> cheapestFirst = withToken.stream()
                .filter(option -> preFilter.plausible(airportsOf(option)))
                .limit(config.maxOptionsPerSearch())
                .toList();

        int skipped = withToken.size() - (int) withToken.stream()
                .filter(option -> preFilter.plausible(airportsOf(option)))
                .count();
        if (skipped > 0) {
            log.info("OFFER_REJECTED provider={} query={} reason=BLOCKED_COUNTRY_PREFILTER "
                            + "outbound_options_skipped={} (not resolved, no API call spent)",
                    CODE, query.describe(), skipped);
        }

        if (withToken.isEmpty()) {
            return ProviderResult.failure(CODE, query,
                    "round-trip search returned no departure_token; cannot resolve return flights",
                    elapsedMillis(startedAt));
        }
        if (cheapestFirst.isEmpty()) {
            log.info("SEARCH_COMPLETED provider={} query={} offers=0 "
                            + "(every outbound option routes through a blocked country)",
                    CODE, query.describe());
            return ProviderResult.success(CODE, query, List.of(), elapsedMillis(startedAt));
        }

        List<Itinerary> itineraries = new ArrayList<>();
        for (ParsedOption outbound : cheapestFirst) {
            JsonNode returnResponse = call(buildUrl(query, outbound.departureToken()));
            Optional<String> returnError = parser.readError(returnResponse);
            if (returnError.isPresent()) {
                log.warn("PROVIDER_ERROR provider={} phase=return query={} error={}",
                        CODE, query.describe(), returnError.get());
                continue;
            }
            for (ParsedOption combined : parser.parseOptions(returnResponse, CURRENCY)) {
                ParsedOption inbound = stripOutboundPrefix(combined, outbound);
                buildItinerary(query, outbound, inbound, combined).ifPresent(itineraries::add);
            }
        }

        log.info("SEARCH_COMPLETED provider={} query={} offers={} elapsed={}ms",
                CODE, query.describe(), itineraries.size(), elapsedMillis(startedAt));
        return ProviderResult.success(CODE, query, itineraries, elapsedMillis(startedAt));
    }

    /**
     * SerpApi's second call sometimes echoes the chosen outbound segments before the return ones
     * and sometimes returns the return leg alone. Both shapes are handled by dropping a leading
     * run of segments whose flight numbers match the outbound that was selected.
     */
    static ParsedOption stripOutboundPrefix(ParsedOption combined, ParsedOption outbound) {
        List<String> outboundNumbers = outbound.flightNumbers();
        List<String> combinedNumbers = combined.flightNumbers();
        if (outboundNumbers.isEmpty()
                || combinedNumbers.size() <= outboundNumbers.size()
                || !combinedNumbers.subList(0, outboundNumbers.size()).equals(outboundNumbers)) {
            return combined;
        }
        int drop = outbound.segments().size();
        int layoversToDrop = Math.min(Math.max(drop - 1, 0), combined.layovers().size());
        return new ParsedOption(
                combined.segments().subList(drop, combined.segments().size()),
                combined.layovers().subList(layoversToDrop, combined.layovers().size()),
                null,
                combined.price(),
                combined.departureToken(),
                combined.bookingToken(),
                combined.baggage(),
                combined.travelClass());
    }

    private Optional<Itinerary> buildItinerary(
            SearchQuery query, ParsedOption outbound, ParsedOption inbound, ParsedOption priced) {
        Money price = priced.price();
        Optional<PriceQuote> quote = currency.toGbp(price);
        if (quote.isEmpty()) {
            log.warn("Skipping offer: no live {}->GBP rate available", price.currency());
            return Optional.empty();
        }

        ItineraryLeg outboundLeg = new ItineraryLeg(
                outbound.segments(), outbound.layovers(), outbound.totalDuration(), null)
                .withDerivedDurationIfMissing();

        ItineraryLeg inboundLeg = inbound == null
                ? null
                : new ItineraryLeg(
                        inbound.segments(), inbound.layovers(), inbound.totalDuration(), null)
                        .withDerivedDurationIfMissing();

        return Optional.of(Itinerary.builder()
                .tripId(query.tripId())
                .source(CODE)
                .providerOfferId(priced.bookingToken())
                .observedAt(Instant.now())
                .outbound(outboundLeg)
                .inbound(inboundLeg)
                .price(quote.get())
                .baggage(priced.baggage())
                .fareClass(priced.travelClass())
                .bookingUrl(null)
                .searchUrl(googleFlightsSearchUrl(query))
                .detailLevel(RouteDetailLevel.FULL_ITINERARY)
                .build());
    }

    /** Every airport an outbound option touches, including the layovers the source listed. */
    private static java.util.Set<String> airportsOf(ParsedOption option) {
        java.util.Set<String> airports = new java.util.LinkedHashSet<>();
        option.segments().forEach(segment -> {
            airports.add(segment.departureAirport());
            airports.add(segment.arrivalAirport());
        });
        option.layovers().forEach(layover -> airports.add(layover.airport()));
        return airports;
    }

    private URI buildUrl(SearchQuery query, String departureToken) {
        UriComponentsBuilder builder = UriComponentsBuilder.fromHttpUrl(baseUrl + "/search.json")
                .queryParam("engine", "google_flights")
                .queryParam("departure_id", query.origin())
                .queryParam("arrival_id", query.destination())
                .queryParam("outbound_date", query.departureDate())
                .queryParam("currency", CURRENCY)
                .queryParam("hl", "en")
                .queryParam("adults", query.adults())
                .queryParam("travel_class", travelClassCode(query.cabinClass()))
                .queryParam("type", query.isRoundTrip() ? 1 : 2)
                .queryParam("api_key", config.apiKey());
        if (query.isRoundTrip()) {
            builder.queryParam("return_date", query.returnDate());
        }
        if (departureToken != null) {
            builder.queryParam("departure_token", departureToken);
        }
        // encode(), not build(true): departure_token is Base64 and carries '=' (and can carry '+'
        // and '/'), which are not valid raw in a query string. Declaring the components
        // "already encoded" made the whole request fail before it was even sent.
        return builder.build().encode().toUri();
    }

    private static int travelClassCode(String cabinClass) {
        return switch (cabinClass == null ? "ECONOMY" : cabinClass.toUpperCase()) {
            case "PREMIUM_ECONOMY" -> 2;
            case "BUSINESS" -> 3;
            case "FIRST" -> 4;
            default -> 1;
        };
    }

    /** A search URL, not an offer URL — labelled as such everywhere it is shown. */
    private static String googleFlightsSearchUrl(SearchQuery query) {
        String q = "Flights from " + query.origin() + " to " + query.destination()
                + " on " + query.departureDate()
                + (query.returnDate() == null ? "" : " through " + query.returnDate());
        return "https://www.google.com/travel/flights?q="
                + URLEncoder.encode(q, StandardCharsets.UTF_8);
    }

    private JsonNode call(URI url) {
        return http.getForObject(url, JsonNode.class);
    }
}

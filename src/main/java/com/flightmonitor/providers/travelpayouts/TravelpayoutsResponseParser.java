package com.flightmonitor.providers.travelpayouts;

import com.fasterxml.jackson.databind.JsonNode;
import com.flightmonitor.domain.model.BaggageAllowance;
import com.flightmonitor.domain.model.FlightSegment;
import com.flightmonitor.domain.model.Itinerary;
import com.flightmonitor.domain.model.ItineraryLeg;
import com.flightmonitor.domain.model.Money;
import com.flightmonitor.domain.model.PriceQuote;
import com.flightmonitor.domain.model.RouteDetailLevel;
import com.flightmonitor.domain.port.SearchQuery;
import com.flightmonitor.infrastructure.fx.CurrencyConverter;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Parses the Travelpayouts {@code aviasales/v3/prices_for_dates} payload.
 *
 * <p>This source is a <b>cache of fares Aviasales users actually saw</b>, not a live shopping
 * query, and it reports only how many transfers a trip has — never which airports they pass
 * through. Every itinerary built here is therefore tagged {@link RouteDetailLevel#COUNTS_ONLY},
 * which makes the safety filter reject it by default rather than assume the connections are fine.
 *
 * <p>The single origin-to-destination segment is not a claim that the flight is non-stop: the real
 * stop count is carried in {@code declaredStops}, and {@code stops()} reports it faithfully.
 *
 * <p>Durations in this API are minutes: {@code duration_to} outbound, {@code duration_back} inbound.
 * Timestamps are ISO-8601 with a real offset, so no timezone inference is needed.
 */
public final class TravelpayoutsResponseParser {

    private static final Logger log = LoggerFactory.getLogger(TravelpayoutsResponseParser.class);

    private final CurrencyConverter currency;
    private final String bookingBaseUrl;

    public TravelpayoutsResponseParser(CurrencyConverter currency, String bookingBaseUrl) {
        this.currency = currency;
        this.bookingBaseUrl = bookingBaseUrl;
    }

    public Optional<String> readError(JsonNode root) {
        if (root == null || root.isMissingNode()) {
            return Optional.of("empty response body");
        }
        if (root.has("success") && !root.get("success").asBoolean(false)) {
            JsonNode error = root.get("error");
            return Optional.of(error == null ? "success=false" : error.asText());
        }
        return Optional.empty();
    }

    public List<Itinerary> parse(JsonNode root, SearchQuery query, String providerCode) {
        List<Itinerary> itineraries = new ArrayList<>();
        String responseCurrency = root.path("currency").asText("GBP").toUpperCase();

        for (JsonNode node : root.path("data")) {
            try {
                toItinerary(node, query, providerCode, responseCurrency).ifPresent(itineraries::add);
            } catch (RuntimeException e) {
                log.warn("Skipping unparseable Travelpayouts row: {}", e.toString());
            }
        }
        return itineraries;
    }

    private Optional<Itinerary> toItinerary(
            JsonNode node, SearchQuery query, String providerCode, String responseCurrency) {

        String origin = text(node, "origin_airport", text(node, "origin", query.origin()));
        String destination =
                text(node, "destination_airport", text(node, "destination", query.destination()));

        OffsetDateTime departureAt = offsetDateTime(node, "departure_at");
        if (departureAt == null) {
            return Optional.empty();
        }

        JsonNode priceNode = node.get("price");
        if (priceNode == null || priceNode.isNull()) {
            return Optional.empty();
        }
        Optional<PriceQuote> quote =
                currency.toGbp(new Money(new BigDecimal(priceNode.asText()), responseCurrency));
        if (quote.isEmpty()) {
            log.warn("Skipping offer: no live {}->GBP rate available", responseCurrency);
            return Optional.empty();
        }

        String airline = text(node, "airline", null);
        String flightNumber = flightNumber(airline, node);

        Duration outboundDuration = minutes(node, "duration_to", minutes(node, "duration", null));
        ItineraryLeg outbound = leg(
                origin, destination, departureAt, outboundDuration, airline, flightNumber,
                intOrNull(node, "transfers"));

        ItineraryLeg inbound = null;
        OffsetDateTime returnAt = offsetDateTime(node, "return_at");
        if (returnAt != null) {
            inbound = leg(
                    destination, origin, returnAt, minutes(node, "duration_back", null),
                    airline, null, intOrNull(node, "return_transfers"));
        }

        return Optional.of(Itinerary.builder()
                .tripId(query.tripId())
                .source(providerCode)
                .providerOfferId(null)
                .observedAt(Instant.now())
                .outbound(outbound)
                .inbound(inbound)
                .price(quote.get())
                .baggage(BaggageAllowance.unknown())
                .fareClass(null)
                .bookingUrl(bookingUrl(node))
                .searchUrl(null)
                .detailLevel(RouteDetailLevel.COUNTS_ONLY)
                .build());
    }

    private static ItineraryLeg leg(
            String from,
            String to,
            OffsetDateTime departureAt,
            Duration duration,
            String airline,
            String flightNumber,
            Integer declaredStops) {

        OffsetDateTime arrivalAt = duration == null ? null : departureAt.plus(duration);
        FlightSegment segment = new FlightSegment(
                from, departureAt, to, arrivalAt, duration, airline, null, flightNumber, null, null);
        return new ItineraryLeg(List.of(segment), List.of(), duration, declaredStops);
    }

    /** The API returns a relative path; it only becomes usable with the Aviasales host prefix. */
    private String bookingUrl(JsonNode node) {
        String link = text(node, "link", null);
        if (link == null) {
            return null;
        }
        return link.startsWith("http") ? link : bookingBaseUrl + link;
    }

    private static String flightNumber(String airline, JsonNode node) {
        String number = text(node, "flight_number", null);
        if (number == null) {
            return null;
        }
        return airline == null || number.startsWith(airline) ? number : airline + number;
    }

    private static OffsetDateTime offsetDateTime(JsonNode node, String field) {
        String value = text(node, field, null);
        if (value == null) {
            return null;
        }
        try {
            return OffsetDateTime.parse(value);
        } catch (RuntimeException e) {
            log.warn("Unparseable timestamp '{}' in field {}", value, field);
            return null;
        }
    }

    private static Duration minutes(JsonNode node, String field, Duration fallback) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull() || !value.isNumber() || value.asLong() <= 0) {
            return fallback;
        }
        return Duration.ofMinutes(value.asLong());
    }

    private static Integer intOrNull(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() || !value.isNumber() ? null : value.asInt();
    }

    private static String text(JsonNode node, String field, String fallback) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return fallback;
        }
        String asText = value.asText();
        return asText.isBlank() ? fallback : asText;
    }
}

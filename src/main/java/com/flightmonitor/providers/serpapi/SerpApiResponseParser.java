package com.flightmonitor.providers.serpapi;

import com.fasterxml.jackson.databind.JsonNode;
import com.flightmonitor.domain.model.Airport;
import com.flightmonitor.domain.model.BaggageAllowance;
import com.flightmonitor.domain.model.FlightSegment;
import com.flightmonitor.domain.model.Layover;
import com.flightmonitor.domain.model.Money;
import com.flightmonitor.domain.port.AirportCatalog;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Turns a SerpApi {@code engine=google_flights} payload into domain objects. Pure and free of HTTP,
 * so the whole parsing contract is covered by fixture-driven unit tests.
 *
 * <p>Two details matter more than the rest:
 *
 * <ul>
 *   <li><b>Times have no offset.</b> Google Flights reports {@code "2027-01-09 10:30"} in the
 *       airport's own local time. The offset is resolved from the local airport catalogue, which is
 *       also why an airport missing from the catalogue makes a segment unparseable rather than
 *       silently UTC.
 *   <li><b>Nothing is invented.</b> Baggage is only reported when an extension string states it;
 *       otherwise the allowance is {@link BaggageAllowance#unknown()}.
 * </ul>
 */
public final class SerpApiResponseParser {

    private static final Logger log = LoggerFactory.getLogger(SerpApiResponseParser.class);

    private static final DateTimeFormatter GOOGLE_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT);

    private final AirportCatalog catalog;

    public SerpApiResponseParser(AirportCatalog catalog) {
        this.catalog = catalog;
    }

    /** Reads {@code best_flights} followed by {@code other_flights}, in that order. */
    public List<ParsedOption> parseOptions(JsonNode root, String currency) {
        List<ParsedOption> options = new ArrayList<>();
        collect(root.path("best_flights"), currency, options);
        collect(root.path("other_flights"), currency, options);
        return options;
    }

    /** SerpApi reports its own errors in an {@code error} field with HTTP 200. */
    public Optional<String> readError(JsonNode root) {
        if (root == null || root.isMissingNode()) {
            return Optional.of("empty response body");
        }
        JsonNode error = root.get("error");
        return error == null || error.isNull() ? Optional.empty() : Optional.of(error.asText());
    }

    private void collect(JsonNode array, String currency, List<ParsedOption> into) {
        if (!array.isArray()) {
            return;
        }
        for (JsonNode node : array) {
            try {
                parseOption(node, currency).ifPresent(into::add);
            } catch (RuntimeException e) {
                log.warn("Skipping unparseable SerpApi option: {}", e.toString());
            }
        }
    }

    private Optional<ParsedOption> parseOption(JsonNode node, String currency) {
        List<FlightSegment> segments = new ArrayList<>();
        for (JsonNode flight : node.path("flights")) {
            Optional<FlightSegment> segment = parseSegment(flight);
            if (segment.isEmpty()) {
                return Optional.empty();
            }
            segments.add(segment.get());
        }
        if (segments.isEmpty()) {
            return Optional.empty();
        }

        List<Layover> layovers = new ArrayList<>();
        for (JsonNode layover : node.path("layovers")) {
            layovers.add(new Layover(
                    text(layover, "id"),
                    minutes(layover, "duration"),
                    layover.path("overnight").asBoolean(false)));
        }

        JsonNode priceNode = node.get("price");
        if (priceNode == null || priceNode.isNull()) {
            return Optional.empty();
        }
        Money price = new Money(new BigDecimal(priceNode.asText()), currency);

        return Optional.of(new ParsedOption(
                segments,
                layovers,
                minutes(node, "total_duration"),
                price,
                text(node, "departure_token"),
                text(node, "booking_token"),
                baggageFrom(node, segments),
                travelClassOf(segments)));
    }

    private Optional<FlightSegment> parseSegment(JsonNode flight) {
        String departureAirport = text(flight.path("departure_airport"), "id");
        String arrivalAirport = text(flight.path("arrival_airport"), "id");
        if (departureAirport == null || arrivalAirport == null) {
            return Optional.empty();
        }

        OffsetDateTime departureAt =
                toOffsetDateTime(text(flight.path("departure_airport"), "time"), departureAirport);
        OffsetDateTime arrivalAt =
                toOffsetDateTime(text(flight.path("arrival_airport"), "time"), arrivalAirport);

        return Optional.of(new FlightSegment(
                departureAirport,
                departureAt,
                arrivalAirport,
                arrivalAt,
                minutes(flight, "duration"),
                text(flight, "airline"),
                text(flight, "operated_by"),
                text(flight, "flight_number"),
                text(flight, "airplane"),
                text(flight, "travel_class")));
    }

    /**
     * Resolves a naive Google Flights timestamp against the airport's own zone. Returns null when
     * the airport is unknown — better an absent time than a time silently assumed to be UTC.
     */
    OffsetDateTime toOffsetDateTime(String localTime, String iata) {
        if (localTime == null || localTime.isBlank()) {
            return null;
        }
        Optional<Airport> airport = catalog.find(iata);
        if (airport.isEmpty()) {
            log.warn("Cannot resolve timezone for unknown airport {}; leaving time unset", iata);
            return null;
        }
        ZoneId zone = airport.get().zone();
        try {
            LocalDateTime naive = LocalDateTime.parse(localTime.trim(), GOOGLE_TIME);
            return naive.atZone(zone).toOffsetDateTime();
        } catch (RuntimeException e) {
            log.warn("Unparseable time '{}' for {}: {}", localTime, iata, e.toString());
            return null;
        }
    }

    /**
     * Google Flights only mentions baggage when it has something to say. Anything not stated stays
     * unknown; the phrases matched here are the ones Google actually emits.
     */
    private BaggageAllowance baggageFrom(JsonNode node, List<FlightSegment> segments) {
        List<String> extensions = new ArrayList<>();
        for (JsonNode ext : node.path("extensions")) {
            extensions.add(ext.asText().toLowerCase(Locale.ROOT));
        }
        for (JsonNode flight : node.path("flights")) {
            for (JsonNode ext : flight.path("extensions")) {
                extensions.add(ext.asText().toLowerCase(Locale.ROOT));
            }
        }

        Boolean carryOn = null;
        Integer checkedPieces = null;
        String description = null;

        for (String extension : extensions) {
            if (!extension.contains("bag")) {
                continue;
            }
            description = description == null ? extension : description + "; " + extension;
            if (extension.contains("carry-on") || extension.contains("carry on")) {
                carryOn = !extension.contains("not included") && !extension.contains("fee");
            }
            if (extension.contains("checked")) {
                if (extension.contains("1 checked")) {
                    checkedPieces = 1;
                } else if (extension.contains("2 checked")) {
                    checkedPieces = 2;
                } else if (extension.contains("no checked") || extension.contains("not included")) {
                    checkedPieces = 0;
                }
            }
        }

        if (description == null) {
            return BaggageAllowance.unknown();
        }
        return new BaggageAllowance(true, null, carryOn, checkedPieces, null, description);
    }

    private static String travelClassOf(List<FlightSegment> segments) {
        return segments.stream()
                .map(FlightSegment::travelClass)
                .filter(java.util.Objects::nonNull)
                .findFirst()
                .orElse(null);
    }

    private static Duration minutes(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull() || !value.isNumber()) {
            return null;
        }
        return Duration.ofMinutes(value.asLong());
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        String asText = value.asText();
        return asText.isBlank() ? null : asText;
    }

    /**
     * One flight option straight out of the payload, before it is assembled into a round trip.
     *
     * @param departureToken present on outbound options of a round-trip search; it is the handle
     *                       SerpApi requires to retrieve the matching return flights
     */
    public record ParsedOption(
            List<FlightSegment> segments,
            List<Layover> layovers,
            Duration totalDuration,
            Money price,
            String departureToken,
            String bookingToken,
            BaggageAllowance baggage,
            String travelClass) {

        public List<String> flightNumbers() {
            return segments.stream()
                    .map(FlightSegment::flightNumber)
                    .filter(java.util.Objects::nonNull)
                    .toList();
        }
    }
}

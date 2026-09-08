package com.flightmonitor.domain.model;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * One direction of travel (outbound or inbound), made of one or more {@link FlightSegment}s.
 *
 * <p>{@code totalDuration} is the elapsed door-to-door time for the leg. When the source reports it
 * we keep the source value; otherwise it is derived from the first departure and last arrival,
 * which are absolute instants ({@link OffsetDateTime}) so the arithmetic is timezone-correct.
 *
 * <p>{@code declaredStops} exists for sources that report a stop count without naming the
 * connecting airports (e.g. the Travelpayouts cache). In that case the leg holds a single
 * origin-to-destination segment but {@code stops()} still reports the truth the source gave us.
 * Such offers are always tagged {@link RouteDetailLevel#COUNTS_ONLY}.
 */
public record ItineraryLeg(
        List<FlightSegment> segments,
        List<Layover> layovers,
        Duration totalDuration,
        Integer declaredStops) {

    public ItineraryLeg {
        Objects.requireNonNull(segments, "segments");
        if (segments.isEmpty()) {
            throw new IllegalArgumentException("a leg needs at least one segment");
        }
        segments = List.copyOf(segments);
        layovers = layovers == null ? List.of() : List.copyOf(layovers);
    }

    /** Builds a leg from fully-known segments, deriving the duration when not supplied. */
    public static ItineraryLeg of(List<FlightSegment> segments, List<Layover> layovers) {
        return new ItineraryLeg(segments, layovers, null, null).withDerivedDurationIfMissing();
    }

    public ItineraryLeg withDerivedDurationIfMissing() {
        if (totalDuration != null) {
            return this;
        }
        OffsetDateTime start = departureAt();
        OffsetDateTime end = arrivalAt();
        Duration derived = (start != null && end != null) ? Duration.between(start, end) : null;
        return new ItineraryLeg(segments, layovers, derived, declaredStops);
    }

    public FlightSegment first() {
        return segments.get(0);
    }

    public FlightSegment last() {
        return segments.get(segments.size() - 1);
    }

    public OffsetDateTime departureAt() {
        return first().departureAt();
    }

    public OffsetDateTime arrivalAt() {
        return last().arrivalAt();
    }

    public String originAirport() {
        return first().departureAirport();
    }

    public String destinationAirport() {
        return last().arrivalAirport();
    }

    /** Number of connections. Prefers the count the source declared over the segment count. */
    public int stops() {
        return declaredStops != null ? declaredStops : segments.size() - 1;
    }

    /** Every airport this leg is known to touch, in order. */
    public Set<String> airportsVisited() {
        Set<String> airports = new LinkedHashSet<>();
        for (FlightSegment segment : segments) {
            if (segment.departureAirport() != null) {
                airports.add(segment.departureAirport());
            }
            if (segment.arrivalAirport() != null) {
                airports.add(segment.arrivalAirport());
            }
        }
        for (Layover layover : layovers) {
            if (layover.airport() != null) {
                airports.add(layover.airport());
            }
        }
        return airports;
    }

    /** Only the intermediate airports — what the safety filter cares most about. */
    public Set<String> connectionAirports() {
        Set<String> connections = new LinkedHashSet<>();
        for (int i = 0; i < segments.size(); i++) {
            if (i > 0 && segments.get(i).departureAirport() != null) {
                connections.add(segments.get(i).departureAirport());
            }
            if (i < segments.size() - 1 && segments.get(i).arrivalAirport() != null) {
                connections.add(segments.get(i).arrivalAirport());
            }
        }
        for (Layover layover : layovers) {
            if (layover.airport() != null) {
                connections.add(layover.airport());
            }
        }
        return connections;
    }

    public List<String> airlines() {
        return segments.stream()
                .map(FlightSegment::marketingAirline)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
    }

    public List<String> flightNumbers() {
        return segments.stream()
                .map(FlightSegment::flightNumber)
                .filter(Objects::nonNull)
                .toList();
    }
}

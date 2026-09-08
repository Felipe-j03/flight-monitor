package com.flightmonitor.domain.model;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * A complete round trip (or one way, when {@code inbound} is null) as returned by one provider.
 *
 * <p>Everything here reflects what a source actually said. Fields the source omitted stay null and
 * are surfaced as "unavailable" downstream — the system never substitutes a plausible value.
 */
public record Itinerary(
        String tripId,
        String source,
        String providerOfferId,
        Instant observedAt,
        ItineraryLeg outbound,
        ItineraryLeg inbound,
        PriceQuote price,
        BaggageAllowance baggage,
        String fareClass,
        String bookingUrl,
        String searchUrl,
        RouteDetailLevel detailLevel) {

    public Itinerary {
        Objects.requireNonNull(tripId, "tripId");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(observedAt, "observedAt");
        Objects.requireNonNull(outbound, "outbound");
        Objects.requireNonNull(price, "price");
        Objects.requireNonNull(detailLevel, "detailLevel");
        baggage = baggage == null ? BaggageAllowance.unknown() : baggage;
    }

    public boolean isRoundTrip() {
        return inbound != null;
    }

    public Optional<ItineraryLeg> inboundLeg() {
        return Optional.ofNullable(inbound);
    }

    public String originAirport() {
        return outbound.originAirport();
    }

    public String destinationAirport() {
        return outbound.destinationAirport();
    }

    public OffsetDateTime departureAt() {
        return outbound.departureAt();
    }

    public OffsetDateTime arrivalAt() {
        return outbound.arrivalAt();
    }

    public OffsetDateTime returnDepartureAt() {
        return inbound == null ? null : inbound.departureAt();
    }

    /** When the traveller is actually back — the field the "home before 23 Jan" rule depends on. */
    public OffsetDateTime returnArrivalAt() {
        return inbound == null ? null : inbound.arrivalAt();
    }

    public int totalStops() {
        return outbound.stops() + (inbound == null ? 0 : inbound.stops());
    }

    /** The longest single leg — the duration cap applies per leg, not to the round trip. */
    public Duration longestLegDuration() {
        Duration out = outbound.totalDuration();
        Duration in = inbound == null ? null : inbound.totalDuration();
        if (out == null) {
            return in;
        }
        if (in == null) {
            return out;
        }
        return out.compareTo(in) >= 0 ? out : in;
    }

    public Set<String> airportsVisited() {
        Set<String> airports = new LinkedHashSet<>(outbound.airportsVisited());
        if (inbound != null) {
            airports.addAll(inbound.airportsVisited());
        }
        return airports;
    }

    public Set<String> connectionAirports() {
        Set<String> airports = new LinkedHashSet<>(outbound.connectionAirports());
        if (inbound != null) {
            airports.addAll(inbound.connectionAirports());
        }
        return airports;
    }

    public List<String> airlines() {
        List<String> all = new ArrayList<>(outbound.airlines());
        if (inbound != null) {
            for (String airline : inbound.airlines()) {
                if (!all.contains(airline)) {
                    all.add(airline);
                }
            }
        }
        return all;
    }

    public List<String> flightNumbers() {
        List<String> all = new ArrayList<>(outbound.flightNumbers());
        if (inbound != null) {
            all.addAll(inbound.flightNumbers());
        }
        return all;
    }

    /**
     * A deterministic id for "the same trip", independent of which provider found it and of the
     * price. Two providers quoting the same flights collapse onto one fingerprint, which is what
     * de-duplication and the price history are keyed on.
     *
     * <p>Deliberately excludes price, currency, booking url and provider. Deliberately includes
     * airports, calendar dates, airlines, flight numbers, stop counts and connection airports.
     */
    public String fingerprint() {
        StringBuilder raw = new StringBuilder(tripId).append("|");
        appendLeg(raw, outbound);
        raw.append("||");
        if (inbound != null) {
            appendLeg(raw, inbound);
        }
        return sha256Hex(raw.toString());
    }

    private static void appendLeg(StringBuilder raw, ItineraryLeg leg) {
        raw.append(leg.originAirport()).append(">").append(leg.destinationAirport()).append("|");
        raw.append(leg.departureAt() == null ? "-" : leg.departureAt().toLocalDate()).append("|");
        raw.append(leg.arrivalAt() == null ? "-" : leg.arrivalAt().toLocalDate()).append("|");
        raw.append(String.join(",", leg.airlines())).append("|");
        raw.append(String.join(",", leg.flightNumbers())).append("|");
        raw.append(leg.stops()).append("|");
        raw.append(String.join(",", leg.connectionAirports()));
    }

    private static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash).substring(0, 32);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 must be available", e);
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Mutable assembler for providers; keeps the record itself immutable and validated. */
    public static final class Builder {
        private String tripId;
        private String source;
        private String providerOfferId;
        private Instant observedAt = Instant.now();
        private ItineraryLeg outbound;
        private ItineraryLeg inbound;
        private PriceQuote price;
        private BaggageAllowance baggage = BaggageAllowance.unknown();
        private String fareClass;
        private String bookingUrl;
        private String searchUrl;
        private RouteDetailLevel detailLevel = RouteDetailLevel.PARTIAL;

        public Builder tripId(String v) { this.tripId = v; return this; }
        public Builder source(String v) { this.source = v; return this; }
        public Builder providerOfferId(String v) { this.providerOfferId = v; return this; }
        public Builder observedAt(Instant v) { this.observedAt = v; return this; }
        public Builder outbound(ItineraryLeg v) { this.outbound = v; return this; }
        public Builder inbound(ItineraryLeg v) { this.inbound = v; return this; }
        public Builder price(PriceQuote v) { this.price = v; return this; }
        public Builder baggage(BaggageAllowance v) { this.baggage = v; return this; }
        public Builder fareClass(String v) { this.fareClass = v; return this; }
        public Builder bookingUrl(String v) { this.bookingUrl = v; return this; }
        public Builder searchUrl(String v) { this.searchUrl = v; return this; }
        public Builder detailLevel(RouteDetailLevel v) { this.detailLevel = v; return this; }

        public Itinerary build() {
            return new Itinerary(tripId, source, providerOfferId, observedAt, outbound, inbound,
                    price, baggage, fareClass, bookingUrl, searchUrl, detailLevel);
        }
    }
}

package com.flightmonitor;

import com.flightmonitor.config.AlertConfig;
import com.flightmonitor.config.SafetyConfig;
import com.flightmonitor.config.ScoringConfig;
import com.flightmonitor.config.TripConfig;
import com.flightmonitor.domain.model.BaggageAllowance;
import com.flightmonitor.domain.model.FlightSegment;
import com.flightmonitor.domain.model.Itinerary;
import com.flightmonitor.domain.model.ItineraryLeg;
import com.flightmonitor.domain.model.Layover;
import com.flightmonitor.domain.model.Money;
import com.flightmonitor.domain.model.PriceQuote;
import com.flightmonitor.domain.model.RouteDetailLevel;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

/**
 * Builders for the objects the rules operate on, so each test states only the one thing it is
 * about (a Doha connection, a 50-hour leg, a late return) and inherits sane values for the rest.
 */
public final class TestFixtures {

    public static final String TRIP_ID = "tokyo-poa-2027";

    public static final ZoneOffset JAPAN = ZoneOffset.ofHours(9);
    public static final ZoneOffset BRAZIL = ZoneOffset.ofHours(-3);
    public static final ZoneOffset GERMANY = ZoneOffset.ofHours(1);
    public static final ZoneOffset QATAR = ZoneOffset.ofHours(3);

    private TestFixtures() {}

    public static TripConfig trip() {
        return new TripConfig(
                TRIP_ID,
                "Tokyo -> Porto Alegre (Jan 2027)",
                true,
                List.of("HND", "NRT"),
                List.of("POA", "FLN", "CWB", "GRU"),
                List.of("POA"),
                LocalDate.of(2027, 1, 9),
                2,
                LocalDate.of(2027, 1, 16),
                LocalDate.of(2027, 1, 21),
                OffsetDateTime.of(2027, 1, 23, 23, 59, 0, 0, JAPAN),
                12,
                new BigDecimal("1300"),
                new BigDecimal("1500"),
                "GBP",
                30,
                36,
                42,
                48,
                1,
                "ECONOMY",
                null,
                null,
                null,
                null,
                false,
                null);
    }

    /** The domestic Rio trip as configured: reais, morning outbound, both Rio airports at once. */
    public static TripConfig rioTrip() {
        return new TripConfig(
                "poa-rio-2027",
                "Porto Alegre -> Rio de Janeiro (Jan 2027)",
                true,
                List.of("POA"),
                List.of("GIG", "SDU"),
                List.of("GIG", "SDU"),
                LocalDate.of(2027, 1, 11),
                0,
                LocalDate.of(2027, 1, 13),
                LocalDate.of(2027, 1, 13),
                OffsetDateTime.of(2027, 1, 13, 23, 59, 0, 0, BRAZIL),
                0,
                new BigDecimal("650"),
                new BigDecimal("800"),
                "BRL",
                4,
                6,
                8,
                12,
                1,
                "ECONOMY",
                java.time.LocalTime.of(0, 0),
                java.time.LocalTime.of(11, 59),
                java.time.LocalTime.of(5, 0),
                java.time.LocalTime.of(11, 0),
                true,
                2);
    }

    /** Blocks the Gulf states used in the routing tests; nothing else. */
    public static SafetyConfig safety() {
        return new SafetyConfig(
                java.util.Set.of("QA", "AE", "SA"), java.util.Set.of(), true, true);
    }

    public static SafetyConfig safetyBlockingAirport(String iata) {
        return new SafetyConfig(java.util.Set.of(), java.util.Set.of(iata), true, true);
    }

    public static SafetyConfig safetyAllowingUnverifiedRoutes() {
        return new SafetyConfig(java.util.Set.of("QA"), java.util.Set.of(), false, false);
    }

    public static ScoringConfig scoring() {
        return new ScoringConfig(45, 25, 15, 5, 10, 4);
    }

    public static AlertConfig alerts() {
        return new AlertConfig(
                new BigDecimal("30"), new BigDecimal("3"), 12, false, true, 5,
                new BigDecimal("150"));
    }

    public static PriceQuote gbp(String amount) {
        return PriceQuote.alreadyGbp(Money.of(amount, "GBP"), Instant.parse("2026-09-07T10:00:00Z"));
    }

    /** The reference acceptable itinerary: HND -> FRA -> GRU -> POA and back, all in budget. */
    public static ItineraryBuilder acceptableItinerary() {
        return new ItineraryBuilder();
    }

    /** Fluent builder that starts from a valid itinerary and lets a test change one thing. */
    public static final class ItineraryBuilder {

        private String source = "test-provider";
        private RouteDetailLevel detailLevel = RouteDetailLevel.FULL_ITINERARY;
        private PriceQuote price = gbp("1400");
        private BaggageAllowance baggage =
                new BaggageAllowance(true, null, true, 1, 23, "1 checked bag included");
        private String destination = "POA";
        private String connection = "FRA";
        private ZoneOffset connectionZone = GERMANY;
        private OffsetDateTime departure = OffsetDateTime.of(2027, 1, 9, 9, 55, 0, 0, JAPAN);
        private OffsetDateTime arrival = OffsetDateTime.of(2027, 1, 10, 5, 5, 0, 0, BRAZIL);
        private OffsetDateTime returnDeparture = OffsetDateTime.of(2027, 1, 21, 6, 10, 0, 0, BRAZIL);
        private OffsetDateTime returnArrival = OffsetDateTime.of(2027, 1, 23, 3, 5, 0, 0, JAPAN);
        private boolean roundTrip = true;
        private Integer declaredStops;

        public ItineraryBuilder source(String value) {
            this.source = value;
            return this;
        }

        public ItineraryBuilder detailLevel(RouteDetailLevel value) {
            this.detailLevel = value;
            return this;
        }

        public ItineraryBuilder priceGbp(String amount) {
            this.price = gbp(amount);
            return this;
        }

        public ItineraryBuilder price(PriceQuote value) {
            this.price = value;
            return this;
        }

        public ItineraryBuilder baggage(BaggageAllowance value) {
            this.baggage = value;
            return this;
        }

        public ItineraryBuilder destination(String iata) {
            this.destination = iata;
            return this;
        }

        public ItineraryBuilder connectingVia(String iata, ZoneOffset zone) {
            this.connection = iata;
            this.connectionZone = zone;
            return this;
        }

        /** Moves the departure, carrying the arrival with it so the leg stays coherent. */
        public ItineraryBuilder departingAt(OffsetDateTime value) {
            Duration current = Duration.between(departure, arrival);
            this.departure = value;
            this.arrival = value.plus(current).withOffsetSameInstant(BRAZIL);
            return this;
        }

        public ItineraryBuilder arrivingAt(OffsetDateTime value) {
            this.arrival = value;
            return this;
        }

        /** Sets the outbound leg's elapsed time, keeping the departure fixed. */
        public ItineraryBuilder outboundDuration(Duration duration) {
            this.arrival = departure.plus(duration).withOffsetSameInstant(BRAZIL);
            return this;
        }

        /**
         * Sets both legs to the same elapsed time. The duration band looks at the longest leg, so
         * a test about duration has to pin both or the fixed return leg decides the outcome.
         */
        public ItineraryBuilder bothLegsDuration(Duration duration) {
            this.arrival = departure.plus(duration).withOffsetSameInstant(BRAZIL);
            this.returnArrival = returnDeparture.plus(duration).withOffsetSameInstant(JAPAN);
            return this;
        }

        public ItineraryBuilder returningAt(OffsetDateTime value) {
            this.returnArrival = value;
            return this;
        }

        public ItineraryBuilder oneWay() {
            this.roundTrip = false;
            return this;
        }

        public ItineraryBuilder declaredStops(int stops) {
            this.declaredStops = stops;
            return this;
        }

        public Itinerary build() {
            OffsetDateTime connectionArrival = departure.plusHours(13)
                    .withOffsetSameInstant(connectionZone);
            OffsetDateTime connectionDeparture = connectionArrival.plusHours(2);

            List<FlightSegment> outboundSegments = new ArrayList<>();
            outboundSegments.add(new FlightSegment(
                    "HND", departure, connection, connectionArrival,
                    Duration.ofHours(13), "ANA", null, "NH 203", "B77W", "Economy"));
            outboundSegments.add(new FlightSegment(
                    connection, connectionDeparture, destination, arrival,
                    Duration.between(connectionDeparture, arrival),
                    "Lufthansa", null, "LH 500", "A346", "Economy"));

            ItineraryLeg outbound = new ItineraryLeg(
                    outboundSegments,
                    List.of(new Layover(connection, Duration.ofHours(2), false)),
                    Duration.between(departure, arrival),
                    declaredStops);

            ItineraryLeg inbound = null;
            if (roundTrip) {
                OffsetDateTime backConnectionArrival = returnDeparture.plusHours(13)
                        .withOffsetSameInstant(connectionZone);
                OffsetDateTime backConnectionDeparture = backConnectionArrival.plusHours(2);
                inbound = new ItineraryLeg(
                        List.of(
                                new FlightSegment(
                                        destination, returnDeparture, connection,
                                        backConnectionArrival, Duration.ofHours(13),
                                        "Lufthansa", null, "LH 507", "A346", "Economy"),
                                new FlightSegment(
                                        connection, backConnectionDeparture, "HND", returnArrival,
                                        Duration.between(backConnectionDeparture, returnArrival),
                                        "ANA", null, "NH 204", "B77W", "Economy")),
                        List.of(new Layover(connection, Duration.ofHours(2), false)),
                        Duration.between(returnDeparture, returnArrival),
                        declaredStops);
            }

            return Itinerary.builder()
                    .tripId(TRIP_ID)
                    .source(source)
                    .providerOfferId("test-offer")
                    .observedAt(Instant.parse("2026-09-07T10:00:00Z"))
                    .outbound(outbound)
                    .inbound(inbound)
                    .price(price)
                    .baggage(baggage)
                    .fareClass("Economy")
                    .bookingUrl("https://example.test/booking")
                    .searchUrl(null)
                    .detailLevel(detailLevel)
                    .build();
        }
    }
}

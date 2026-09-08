package com.flightmonitor.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * One distinct itinerary, identified by its fingerprint rather than by any provider's id, so the
 * same flights quoted by two sources collapse into this single row.
 *
 * <p>Entities in this package use direct field access (Hibernate reads the fields because
 * {@code @Id} is on one) and public fields. They are persistence records with no behaviour, and
 * accessor pairs for forty columns would add noise without adding safety.
 */
@Entity
@Table(name = "flight_offer",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_flight_offer_fingerprint", columnNames = {"trip_id", "fingerprint"}))
public class FlightOfferEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(name = "trip_id", nullable = false, length = 64)
    public String tripId;

    @Column(nullable = false, length = 64)
    public String fingerprint;

    @Column(name = "origin_airport", nullable = false, length = 4)
    public String originAirport;

    @Column(name = "destination_airport", nullable = false, length = 4)
    public String destinationAirport;

    @Column(name = "departure_at")
    public OffsetDateTime departureAt;

    @Column(name = "arrival_at")
    public OffsetDateTime arrivalAt;

    @Column(name = "return_departure_at")
    public OffsetDateTime returnDepartureAt;

    @Column(name = "return_arrival_at")
    public OffsetDateTime returnArrivalAt;

    // Calendar values as printed on the ticket, in each airport's own local time. Postgres returns
    // timestamptz in UTC, which loses the offset and shifts the displayed date; these carry what a
    // human should read. The instants above stay the source of truth for every comparison.

    @Column(name = "departure_local_date", length = 10)
    public String departureLocalDate;

    @Column(name = "arrival_local_date", length = 10)
    public String arrivalLocalDate;

    @Column(name = "return_departure_local_date", length = 10)
    public String returnDepartureLocalDate;

    @Column(name = "return_arrival_local_time", length = 16)
    public String returnArrivalLocalTime;

    @Column(length = 255)
    public String airlines;

    @Column(name = "flight_numbers", length = 500)
    public String flightNumbers;

    @Column(name = "outbound_stops", nullable = false)
    public int outboundStops;

    @Column(name = "inbound_stops")
    public Integer inboundStops;

    @Column(name = "longest_leg_minutes")
    public Integer longestLegMinutes;

    @Column(length = 1000)
    public String layovers;

    @Column(name = "airports_visited", length = 500)
    public String airportsVisited;

    @Column(name = "countries_visited", length = 500)
    public String countriesVisited;

    @Column(name = "baggage_known", nullable = false)
    public boolean baggageKnown;

    @Column(name = "baggage_cabin_included")
    public Boolean baggageCabinIncluded;

    @Column(name = "baggage_checked_pieces")
    public Integer baggageCheckedPieces;

    @Column(name = "baggage_description", length = 500)
    public String baggageDescription;

    @Column(name = "fare_class", length = 64)
    public String fareClass;

    @Column(name = "detail_level", nullable = false, length = 32)
    public String detailLevel;

    @Column(nullable = false)
    public boolean accepted;

    @Column(name = "rejection_reasons", length = 1000)
    public String rejectionReasons;

    @Column(length = 1000)
    public String warnings;

    @Column(name = "price_band", length = 32)
    public String priceBand;

    @Column(name = "duration_band", length = 32)
    public String durationBand;

    @Column(precision = 8, scale = 2)
    public BigDecimal score;

    @Column(name = "current_price_gbp", nullable = false, precision = 12, scale = 2)
    public BigDecimal currentPriceGbp;

    @Column(name = "lowest_price_gbp", nullable = false, precision = 12, scale = 2)
    public BigDecimal lowestPriceGbp;

    @Column(name = "lowest_price_at", nullable = false)
    public OffsetDateTime lowestPriceAt;

    @Column(name = "highest_price_gbp", nullable = false, precision = 12, scale = 2)
    public BigDecimal highestPriceGbp;

    @Column(name = "previous_price_gbp", precision = 12, scale = 2)
    public BigDecimal previousPriceGbp;

    @Column(name = "first_seen_at", nullable = false)
    public OffsetDateTime firstSeenAt;

    @Column(name = "last_seen_at", nullable = false)
    public OffsetDateTime lastSeenAt;

    @Column(name = "last_alerted_at")
    public OffsetDateTime lastAlertedAt;

}

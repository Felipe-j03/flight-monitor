package com.flightmonitor.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * Append-only observation log. One row per time a price was seen for an offer, which is what the
 * drop detection, the all-time low and the dashboard chart all read from.
 *
 * <p>Nothing updates or deletes rows here: the first price ever seen stays in the record as the
 * first observation rather than being treated as a baseline that later gets rewritten.
 */
@Entity
@Table(name = "price_history")
public class PriceHistoryEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(name = "offer_id", nullable = false)
    public Long offerId;

    @Column(name = "trip_id", nullable = false, length = 64)
    public String tripId;

    @Column(name = "observed_at", nullable = false)
    public OffsetDateTime observedAt;

    @Column(name = "price_gbp", nullable = false, precision = 12, scale = 2)
    public BigDecimal priceGbp;

    @Column(name = "price_original", nullable = false, precision = 12, scale = 2)
    public BigDecimal priceOriginal;

    @Column(nullable = false, length = 3)
    public String currency;

    @Column(name = "fx_rate", nullable = false, precision = 18, scale = 8)
    public BigDecimal fxRate;

    @Column(name = "provider_code", nullable = false, length = 32)
    public String providerCode;
}

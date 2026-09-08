package com.flightmonitor.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * What one provider said about one offer. Keeping these separate is how the system answers
 * "provider A quotes 1450, provider B quotes 1445 for the same flights" instead of storing the
 * trip twice or silently dropping the second quote.
 */
@Entity
@Table(name = "offer_source",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_offer_source", columnNames = {"offer_id", "provider_code"}))
public class OfferSourceEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "offer_id", nullable = false)
    public FlightOfferEntity offer;

    @Column(name = "provider_code", nullable = false, length = 32)
    public String providerCode;

    @Column(name = "provider_offer_id", length = 500)
    public String providerOfferId;

    @Column(name = "price_original", nullable = false, precision = 12, scale = 2)
    public BigDecimal priceOriginal;

    @Column(nullable = false, length = 3)
    public String currency;

    @Column(name = "price_gbp", nullable = false, precision = 12, scale = 2)
    public BigDecimal priceGbp;

    @Column(name = "fx_rate", nullable = false, precision = 18, scale = 8)
    public BigDecimal fxRate;

    @Column(name = "fx_retrieved_at", nullable = false)
    public OffsetDateTime fxRetrievedAt;

    @Column(name = "booking_url", length = 2000)
    public String bookingUrl;

    @Column(name = "search_url", length = 2000)
    public String searchUrl;

    @Column(name = "observed_at", nullable = false)
    public OffsetDateTime observedAt;
}

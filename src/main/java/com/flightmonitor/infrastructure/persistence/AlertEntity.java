package com.flightmonitor.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.OffsetDateTime;

/** Every alert the system decided to send, including whether Telegram actually accepted it. */
@Entity
@Table(name = "alert")
public class AlertEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(name = "offer_id")
    public Long offerId;

    @Column(name = "trip_id", nullable = false, length = 64)
    public String tripId;

    @Column(name = "alert_type", nullable = false, length = 48)
    public String alertType;

    @Column(name = "price_gbp", nullable = false, precision = 12, scale = 2)
    public BigDecimal priceGbp;

    @Column(name = "previous_price_gbp", precision = 12, scale = 2)
    public BigDecimal previousPriceGbp;

    @Column(name = "delta_gbp", precision = 12, scale = 2)
    public BigDecimal deltaGbp;

    @Column(name = "delta_percent", precision = 8, scale = 2)
    public BigDecimal deltaPercent;

    @Column(nullable = false, length = 4000)
    public String message;

    @Column(name = "created_at", nullable = false)
    public OffsetDateTime createdAt;

    @Column(nullable = false)
    public boolean delivered;

    @Column(name = "delivery_error", length = 1000)
    public String deliveryError;
}

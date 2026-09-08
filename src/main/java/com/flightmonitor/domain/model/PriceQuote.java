package com.flightmonitor.domain.model;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Objects;

/**
 * The price of an offer, keeping the source value untouched alongside the GBP conversion.
 *
 * <p>Requirement: never convert with a hardcoded/stale rate. The rate actually used and the
 * moment it was fetched are stored with every quote so any GBP figure can be audited later.
 */
public record PriceQuote(
        Money original,
        BigDecimal gbpAmount,
        BigDecimal rateToGbp,
        Instant convertedAt) {

    public PriceQuote {
        Objects.requireNonNull(original, "original");
        Objects.requireNonNull(gbpAmount, "gbpAmount");
        Objects.requireNonNull(rateToGbp, "rateToGbp");
        Objects.requireNonNull(convertedAt, "convertedAt");
        gbpAmount = gbpAmount.setScale(2, RoundingMode.HALF_UP);
    }

    /** Quote for an offer already priced in GBP; the rate is 1 by definition, not a lookup. */
    public static PriceQuote alreadyGbp(Money original, Instant at) {
        if (!"GBP".equals(original.currency())) {
            throw new IllegalArgumentException("not a GBP amount: " + original.currency());
        }
        return new PriceQuote(original, original.amount(), BigDecimal.ONE, at);
    }
}

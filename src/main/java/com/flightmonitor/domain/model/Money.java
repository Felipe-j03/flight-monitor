package com.flightmonitor.domain.model;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/** A monetary amount in a specific ISO-4217 currency, exactly as reported by the source. */
public record Money(BigDecimal amount, String currency) {

    public Money {
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(currency, "currency");
        if (currency.length() != 3) {
            throw new IllegalArgumentException("currency must be an ISO-4217 code: " + currency);
        }
        amount = amount.setScale(2, RoundingMode.HALF_UP);
        currency = currency.toUpperCase();
    }

    public static Money of(String amount, String currency) {
        return new Money(new BigDecimal(amount), currency);
    }

    public static Money of(double amount, String currency) {
        return new Money(BigDecimal.valueOf(amount), currency);
    }
}

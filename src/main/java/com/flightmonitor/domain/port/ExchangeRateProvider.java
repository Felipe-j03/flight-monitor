package com.flightmonitor.domain.port;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;

/** Live FX lookup. Returns empty rather than a guess when the rate cannot be obtained. */
public interface ExchangeRateProvider {

    /**
     * @return the multiplier that turns one unit of {@code from} into {@code to}, together with
     *         the moment it was retrieved, or empty if the rate is unavailable
     */
    Optional<FxRate> rate(String from, String to);

    record FxRate(String from, String to, BigDecimal rate, Instant retrievedAt, String source) {}
}

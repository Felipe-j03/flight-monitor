package com.flightmonitor.infrastructure.fx;

import com.flightmonitor.domain.model.Money;
import com.flightmonitor.domain.model.PriceQuote;
import com.flightmonitor.domain.port.ExchangeRateProvider;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * Turns a source-currency price into a {@link PriceQuote} carrying the original amount, the GBP
 * amount, the exact rate used and when that rate was fetched — the audit trail the brief asked for.
 */
@Service
public class CurrencyConverter {

    private static final String TARGET = "GBP";

    private final ExchangeRateProvider rates;

    public CurrencyConverter(ExchangeRateProvider rates) {
        this.rates = rates;
    }

    /** @return empty when no live rate is available; the caller must then skip the offer */
    public Optional<PriceQuote> toGbp(Money original) {
        if (TARGET.equals(original.currency())) {
            return Optional.of(PriceQuote.alreadyGbp(original, Instant.now()));
        }
        return rates.rate(original.currency(), TARGET).map(rate -> {
            BigDecimal gbp = original.amount()
                    .multiply(rate.rate())
                    .setScale(2, RoundingMode.HALF_UP);
            return new PriceQuote(original, gbp, rate.rate(), rate.retrievedAt());
        });
    }
}

package com.flightmonitor.application;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * What happened to one itinerary's price between the previous observation and this one.
 *
 * @param firstSighting     true when this is the very first time the itinerary has been seen; the
 *                          price is then an <em>observed price</em>, not yet a baseline to compare
 *                          against, so no drop can possibly be claimed
 * @param newAllTimeLow     true when this observation beats every previous one for this itinerary
 * @param previousLowestGbp the lowest price recorded before this observation, null on first sight
 */
public record PriceChange(
        BigDecimal currentGbp,
        BigDecimal previousGbp,
        BigDecimal previousLowestGbp,
        boolean firstSighting,
        boolean newAllTimeLow,
        int observationCount) {

    public BigDecimal dropGbp() {
        if (previousGbp == null) {
            return BigDecimal.ZERO;
        }
        return previousGbp.subtract(currentGbp).setScale(2, RoundingMode.HALF_UP);
    }

    public BigDecimal dropPercent() {
        if (previousGbp == null || previousGbp.signum() == 0) {
            return BigDecimal.ZERO;
        }
        return dropGbp()
                .multiply(BigDecimal.valueOf(100))
                .divide(previousGbp, 2, RoundingMode.HALF_UP);
    }

    public boolean isDrop() {
        return dropGbp().signum() > 0;
    }

    public boolean isRise() {
        return dropGbp().signum() < 0;
    }

    public boolean isStable() {
        return !firstSighting && dropGbp().signum() == 0;
    }
}

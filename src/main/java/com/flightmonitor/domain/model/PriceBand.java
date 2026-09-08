package com.flightmonitor.domain.model;

import java.math.BigDecimal;

/** Where a GBP price falls relative to the configured budget window. */
public enum PriceBand {

    /** Below the lower bound of the budget — the "excelente" case worth shouting about. */
    EXCELLENT,

    /** Inside the budget window, inclusive on both ends. */
    WITHIN_BUDGET,

    /** Above the upper bound. Still tracked and stored, but not a headline. */
    ABOVE_BUDGET;

    public static PriceBand classify(BigDecimal gbp, BigDecimal budgetMin, BigDecimal budgetMax) {
        if (gbp.compareTo(budgetMin) < 0) {
            return EXCELLENT;
        }
        if (gbp.compareTo(budgetMax) <= 0) {
            return WITHIN_BUDGET;
        }
        return ABOVE_BUDGET;
    }
}

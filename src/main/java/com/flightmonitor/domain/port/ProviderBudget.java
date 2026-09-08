package com.flightmonitor.domain.port;

/**
 * Guards metered free tiers.
 *
 * <p>Every usable free flight-search plan in 2026 is capped per month, so the monitor keeps a
 * persistent count of calls and refuses to exceed it. The allowance is also <em>paced</em> across
 * the month: without pacing, a 6-hourly schedule would spend a 100-call quota in the first week
 * and leave the rest of the month blind.
 */
public interface ProviderBudget {

    /**
     * Reserves one call if the paced monthly allowance permits it.
     *
     * @return true when the caller may proceed; false when the budget for now is spent
     */
    boolean tryConsume(String providerCode, int maxPerMonth);

    int usedThisMonth(String providerCode);

    /** How many calls the pacing curve allows to have been made by this point in the month. */
    int pacedAllowance(int maxPerMonth);
}

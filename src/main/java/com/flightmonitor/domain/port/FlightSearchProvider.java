package com.flightmonitor.domain.port;

import com.flightmonitor.domain.model.RouteDetailLevel;

/**
 * The seam that keeps the monitor independent of any one data source.
 *
 * <p>Adding a source means implementing this interface and adding a configuration block; nothing
 * else in the application changes. Sources differ enormously in what they disclose, so each
 * implementation must declare its {@link #routeDetailLevel()} honestly — that declaration is what
 * the safety filter uses to decide whether it can trust the connection list.
 */
public interface FlightSearchProvider {

    /** Stable machine code, also the key of this provider's configuration block. */
    String code();

    String displayName();

    /** Whether this provider is switched on and has whatever credentials it needs. */
    boolean available();

    /**
     * The best detail level this provider can ever produce. A provider must never return an
     * itinerary claiming more detail than this.
     */
    RouteDetailLevel routeDetailLevel();

    /**
     * Runs one query. Implementations must not throw: transport problems, parse problems and
     * rate-limit responses all come back as {@link ProviderResult#failure}.
     */
    ProviderResult search(SearchQuery query);

    /** How many queries this provider is still willing to run right now (budget-aware). */
    int remainingQueriesThisRun();
}

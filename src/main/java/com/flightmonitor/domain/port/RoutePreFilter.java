package com.flightmonitor.domain.port;

import java.util.Collection;

/**
 * A cheap "is this route obviously disqualified?" check that providers can apply mid-flight.
 *
 * <p>It exists to stop money being wasted. SerpApi needs a second paid call to resolve the return
 * legs of an outbound option, and the outbound airports are already known from the first call — so
 * an itinerary routing through a blocked country can be dropped before paying to complete it.
 * Leaving Tokyo for South America the cheapest option is very often a Gulf carrier, so without this
 * the whole budget goes on resolving itineraries that are then thrown away.
 *
 * <p>This is an optimisation, never the authority. {@code SafetyRule} still evaluates the complete
 * itinerary afterwards, and a provider skipping this check changes nothing about what is accepted.
 */
public interface RoutePreFilter {

    /**
     * @return false when these airports already disqualify the route. A "true" means only "not
     *         obviously disqualified" — it is not an approval.
     */
    boolean plausible(Collection<String> airports);
}

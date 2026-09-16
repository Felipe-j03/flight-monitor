package com.flightmonitor.application;

import com.flightmonitor.config.TripConfig;
import com.flightmonitor.domain.port.SearchQuery;
import java.time.Clock;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Expands a trip into the concrete questions to put to the providers, most valuable first.
 *
 * <p>The full cross product of origins, destinations, departure dates and return dates is far
 * larger than a metered free tier can afford — two origins, three destinations and a five-day
 * departure window alone is thirty combinations per return date. The planner therefore emits them
 * in priority order so whatever budget exists is spent on the combinations closest to what was
 * actually asked for, and the list is truncated to the per-run ceiling.
 *
 * <p>Priority, lowest first: exact target departure date beats a flexible one; a primary
 * destination beats an alternative airport; a later return (a longer stay) beats an earlier one.
 */
@Component
public class QueryPlanner {

    private final Clock clock;

    public QueryPlanner(Clock clock) {
        this.clock = clock;
    }

    public List<SearchQuery> plan(TripConfig trip, int maxQueries) {
        List<SearchQuery> queries = new ArrayList<>();

        for (LocalDate departure = trip.earliestDeparture();
                !departure.isAfter(trip.latestDeparture());
                departure = departure.plusDays(1)) {

            for (LocalDate returnDate : returnDatesFor(trip)) {
                if (returnDate != null && !returnDate.isAfter(departure)) {
                    continue;
                }
                for (String origin : originsFor(trip)) {
                    for (String destination : destinationsFor(trip)) {
                        queries.add(query(trip, origin, destination, departure, returnDate,
                                trip.isOpenJaw() ? String.join(",", trip.returnOriginAirports()) : null,
                                priority(trip, departure, returnDate, destination)));
                    }
                }
            }
        }

        Comparator<SearchQuery> byPriority = Comparator.comparingInt(SearchQuery::priority)
                .thenComparing(SearchQuery::departureDate)
                .thenComparing(SearchQuery::origin)
                .thenComparing(SearchQuery::destination);

        List<SearchQuery> primary = queries.stream()
                .filter(query -> !trip.isAlternativeDestination(query.destination()))
                .sorted(byPriority)
                .toList();
        List<SearchQuery> alternative = queries.stream()
                .filter(query -> trip.isAlternativeDestination(query.destination()))
                .sorted(byPriority)
                .toList();

        List<SearchQuery> plan = merge(primary, alternative, Math.max(1, maxQueries));
        return withAlternativeReturn(trip, plan, Math.max(1, maxQueries));
    }

    /**
     * Adds the "fly home from somewhere else" variant of an open-jaw trip, on the days it is due.
     *
     * <p>It goes second, where a two-query provider still reaches it, and only every
     * {@code alternativeEveryDays} days: it is a long shot that is only worth hearing about when it
     * is clearly cheaper, so it gets a fraction of the budget rather than an equal share.
     */
    private List<SearchQuery> withAlternativeReturn(
            TripConfig trip, List<SearchQuery> plan, int maxQueries) {
        if (!trip.hasAlternativeReturn() || plan.isEmpty()) {
            return plan;
        }
        if (LocalDate.now(clock).getDayOfYear() % trip.alternativeEveryDays() != 0) {
            return plan;
        }
        SearchQuery base = plan.get(0);
        SearchQuery variant = query(trip, base.origin(), base.destination(), base.departureDate(),
                base.returnDate(), String.join(",", trip.alternativeReturnOrigins()),
                base.priority() + 20);

        List<SearchQuery> withVariant = new ArrayList<>(plan);
        withVariant.add(1, variant);
        return List.copyOf(withVariant.subList(0, Math.min(withVariant.size(), maxQueries)));
    }

    private static SearchQuery query(
            TripConfig trip,
            String origin,
            String destination,
            LocalDate departure,
            LocalDate returnDate,
            String returnOrigin,
            int priority) {
        String returnDestination = returnOrigin == null || trip.returnDestinationAirports().isEmpty()
                ? null
                : String.join(",", trip.returnDestinationAirports());
        return new SearchQuery(
                trip.id(),
                origin,
                destination,
                departure,
                returnDate,
                trip.adults(),
                trip.cabinClass(),
                priority,
                trip.budgetCurrency(),
                trip.outboundDepartureFrom(),
                trip.outboundDepartureTo(),
                trip.outboundPreferredDeparture(),
                trip.maxOptionsPerSearch(),
                returnOrigin,
                returnDestination,
                trip.discardAboveBudget() ? trip.targetMaxPrice() : null);
    }

    /** One-way trips have a single "no return" slot; round trips one per day in the window. */
    private static List<LocalDate> returnDatesFor(TripConfig trip) {
        if (trip.isOneWay()) {
            List<LocalDate> none = new ArrayList<>();
            none.add(null);
            return none;
        }
        return trip.returnWindowStart().datesUntil(trip.returnWindowEnd().plusDays(1)).toList();
    }

    private static List<String> originsFor(TripConfig trip) {
        return trip.combineOrigins()
                ? List.of(String.join(",", trip.originAirports()))
                : trip.originAirports();
    }

    /**
     * Reserves one slot per run for an alternative airport, and rotates which one gets it.
     *
     * <p>Straight priority ordering would spend every slot on the primary destination and the
     * alternatives would never actually be searched — the whole point of listing them. One slot is
     * enough: alternatives only matter when they are dramatically cheaper, and that shows up
     * eventually without spending half the budget looking for it.
     *
     * <p>The rotation is the day of the year modulo the number of alternative destinations, so it
     * needs no stored state and still covers every alternative over a few days.
     */
    private List<SearchQuery> merge(
            List<SearchQuery> primary, List<SearchQuery> alternative, int maxQueries) {

        if (alternative.isEmpty() || maxQueries < 2) {
            return primary.isEmpty()
                    ? alternative.stream().limit(maxQueries).toList()
                    : primary.stream().limit(maxQueries).toList();
        }

        SearchQuery alternativeSlot = rotate(
                alternative, SearchQuery::destination, alternative.get(0));

        List<SearchQuery> chosen = new ArrayList<>();

        // Position matters as much as inclusion. Providers consume this list from the front and
        // stop at their own per-run cap — a provider allowed two queries would never reach an
        // alternative parked at the end. Best primary first, alternative second, rest after.
        //
        // The origin rotates for the same reason the destination does: HND and NRT tie on priority
        // and the tie breaks alphabetically, so without rotating, a budget-limited provider would
        // search HND every single run and never look at NRT once.
        //
        // Rotate over the WHOLE primary list, then truncate. Truncating first leaves a single
        // candidate and makes the rotation a no-op — which is exactly the bug this comment exists
        // to stop coming back.
        if (!primary.isEmpty()) {
            chosen.add(rotate(primary, SearchQuery::origin, primary.get(0)));
        }
        chosen.add(alternativeSlot);

        primary.stream()
                .filter(query -> !chosen.contains(query))
                .limit(Math.max(0, maxQueries - chosen.size()))
                .forEach(chosen::add);

        return List.copyOf(chosen);
    }

    /**
     * Picks the highest-priority query whose {@code key} matches today's slot in the rotation.
     *
     * <p>Rotation is the day of the year modulo the number of distinct values, so it needs no
     * stored state, survives restarts, and covers every value over a few days. Used for both the
     * alternative destination and the origin airport: in each case the candidates are otherwise
     * tied on priority and would be resolved alphabetically, handing every run to the same one.
     */
    private SearchQuery rotate(
            List<SearchQuery> candidates,
            java.util.function.Function<SearchQuery, String> key,
            SearchQuery fallback) {

        List<String> values = candidates.stream().map(key).distinct().sorted().toList();
        if (values.isEmpty()) {
            return fallback;
        }
        String todays = values.get(LocalDate.now(clock).getDayOfYear() % values.size());
        return candidates.stream()
                .filter(query -> todays.equals(key.apply(query)))
                .findFirst()
                .orElse(fallback);
    }

    /**
     * One query per destination airport, or a single query covering all of them when the trip asks
     * for it. A combined query costs one metered call instead of one per airport, which is what
     * makes watching both of Rio's airports affordable.
     */
    private static List<String> destinationsFor(TripConfig trip) {
        return trip.combineDestinations()
                ? List.of(String.join(",", trip.destinationAirports()))
                : trip.destinationAirports();
    }

    private int priority(
            TripConfig trip, LocalDate departure, LocalDate returnDate, String destination) {

        int departureOffset = (int) Math.abs(
                ChronoUnit.DAYS.between(trip.targetDepartureDate(), departure));
        int returnOffset = returnDate == null
                ? 0
                : (int) Math.abs(ChronoUnit.DAYS.between(trip.returnWindowEnd(), returnDate));
        int alternativeAirportPenalty = trip.isAlternativeDestination(destination) ? 20 : 0;

        return departureOffset * 10 + returnOffset + alternativeAirportPenalty;
    }
}

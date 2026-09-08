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

            for (LocalDate returnDate = trip.returnWindowStart();
                    !returnDate.isAfter(trip.returnWindowEnd());
                    returnDate = returnDate.plusDays(1)) {

                if (!returnDate.isAfter(departure)) {
                    continue;
                }
                for (String origin : trip.originAirports()) {
                    for (String destination : trip.destinationAirports()) {
                        queries.add(new SearchQuery(
                                trip.id(),
                                origin,
                                destination,
                                departure,
                                returnDate,
                                trip.adults(),
                                trip.cabinClass(),
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

        return merge(primary, alternative, Math.max(1, maxQueries));
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

        List<String> alternativeAirports = alternative.stream()
                .map(SearchQuery::destination)
                .distinct()
                .sorted()
                .toList();
        String todaysAirport = alternativeAirports.get(
                LocalDate.now(clock).getDayOfYear() % alternativeAirports.size());

        SearchQuery alternativeSlot = alternative.stream()
                .filter(query -> query.destination().equals(todaysAirport))
                .findFirst()
                .orElse(alternative.get(0));

        List<SearchQuery> chosen = new ArrayList<>();
        List<SearchQuery> remainingPrimary = primary.stream()
                .limit(maxQueries - 1L)
                .toList();

        // Position matters as much as inclusion. Providers consume this list from the front and
        // stop at their own per-run cap — a provider allowed two queries would never reach an
        // alternative parked at the end. Best primary first, alternative second, rest after.
        if (!remainingPrimary.isEmpty()) {
            chosen.add(remainingPrimary.get(0));
        }
        chosen.add(alternativeSlot);
        chosen.addAll(remainingPrimary.stream().skip(1).toList());

        return List.copyOf(chosen);
    }

    private int priority(
            TripConfig trip, LocalDate departure, LocalDate returnDate, String destination) {

        int departureOffset = (int) Math.abs(
                ChronoUnit.DAYS.between(trip.targetDepartureDate(), departure));
        int returnOffset = (int) Math.abs(
                ChronoUnit.DAYS.between(trip.returnWindowEnd(), returnDate));
        int alternativeAirportPenalty = trip.isAlternativeDestination(destination) ? 20 : 0;

        return departureOffset * 10 + returnOffset + alternativeAirportPenalty;
    }
}

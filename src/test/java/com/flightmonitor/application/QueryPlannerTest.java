package com.flightmonitor.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.flightmonitor.TestFixtures;
import com.flightmonitor.domain.port.SearchQuery;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The planner decides where a tiny metered budget is spent, so what it chooses to leave out matters
 * as much as what it includes.
 */
class QueryPlannerTest {

    private static QueryPlanner plannerOn(String date) {
        return new QueryPlanner(Clock.fixed(Instant.parse(date + "T12:00:00Z"), ZoneOffset.UTC));
    }

    @Test
    @DisplayName("puts the exact target date and the primary destination first")
    void prioritisesTheIdealCombination() {
        List<SearchQuery> plan = plannerOn("2026-09-07").plan(TestFixtures.trip(), 1);

        assertThat(plan).hasSize(1);
        assertThat(plan.get(0).destination()).isEqualTo("POA");
        assertThat(plan.get(0).departureDate())
                .isEqualTo(TestFixtures.trip().targetDepartureDate());
    }

    @Test
    @DisplayName("reserves one slot for an alternative airport instead of spending all on POA")
    void reservesASlotForAlternatives() {
        List<SearchQuery> plan = plannerOn("2026-09-07").plan(TestFixtures.trip(), 2);

        assertThat(plan).hasSize(2);
        assertThat(plan).anySatisfy(query ->
                assertThat(query.destination()).isEqualTo("POA"));
        assertThat(plan)
                .as("without a reserved slot the alternatives would never be searched at all")
                .anySatisfy(query -> assertThat(query.destination()).isNotEqualTo("POA"));
    }

    @Test
    @DisplayName("puts the alternative second, where a two-query provider will actually reach it")
    void alternativeSitsWhereLimitedProvidersSeeIt() {
        // The planner emits up to 12 queries, but a metered provider may only run the first two.
        // An alternative parked at the end would never be searched at all.
        List<SearchQuery> plan = plannerOn("2026-09-07").plan(TestFixtures.trip(), 12);

        assertThat(plan.get(0).destination()).isEqualTo("POA");
        assertThat(plan.get(1).destination())
                .as("second position is what a two-query budget can still afford")
                .isNotEqualTo("POA");
        assertThat(plan.stream().filter(q -> !q.destination().equals("POA")).count())
                .as("exactly one slot goes to alternatives, not more")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("Rio: GIG and SDU go out as ONE query carrying the trip's currency and time window")
    void rioIsOneCombinedQuery() {
        List<SearchQuery> plan = plannerOn("2026-09-16").plan(TestFixtures.rioTrip(), 12);

        assertThat(plan).singleElement().satisfies(query -> {
            assertThat(query.origin()).isEqualTo("POA");
            assertThat(query.destination()).isEqualTo("GIG,SDU");
            assertThat(query.destinations()).containsExactly("GIG", "SDU");
            assertThat(query.departureDate()).isEqualTo(java.time.LocalDate.of(2027, 1, 11));
            assertThat(query.returnDate()).isEqualTo(java.time.LocalDate.of(2027, 1, 13));
            assertThat(query.currency()).isEqualTo("BRL");
            assertThat(query.outboundFrom()).isEqualTo(java.time.LocalTime.of(0, 0));
            assertThat(query.outboundTo()).isEqualTo(java.time.LocalTime.of(11, 59));
            assertThat(query.preferredOutbound()).isEqualTo(java.time.LocalTime.of(5, 0));
            assertThat(query.maxOptions()).isEqualTo(2);
        });
    }

    @Test
    @DisplayName("Tokyo is unaffected: separate queries per airport, in GBP, no time window")
    void tokyoUnchanged() {
        List<SearchQuery> plan = plannerOn("2026-09-16").plan(TestFixtures.trip(), 12);

        assertThat(plan).allSatisfy(query -> {
            assertThat(query.destinations()).hasSize(1);
            assertThat(query.currency()).isEqualTo("GBP");
            assertThat(query.hasOutboundWindow()).isFalse();
            assertThat(query.maxOptions()).isNull();
        });
    }

    @Test
    @DisplayName("rotates the origin airport, so Narita is not starved by alphabetical ties")
    void rotatesOrigin() {
        // HND and NRT tie on priority for the same date, and the tie breaks alphabetically. Without
        // rotation a provider limited to two queries would search HND twice a day, for ever, and
        // never look at NRT once — despite both being configured as origins.
        String first = primaryOriginOn("2026-09-08");
        String second = primaryOriginOn("2026-09-09");

        assertThat(List.of(first, second)).containsExactlyInAnyOrder("HND", "NRT");
    }

    @Test
    @DisplayName("both origins get searched across consecutive days")
    void bothOriginsCoveredOverTime() {
        List<String> origins = List.of(
                        "2026-09-08", "2026-09-09", "2026-09-10", "2026-09-11").stream()
                .map(this::primaryOriginOn)
                .distinct()
                .sorted()
                .toList();

        assertThat(origins).containsExactly("HND", "NRT");
    }

    private String primaryOriginOn(String date) {
        return plannerOn(date).plan(TestFixtures.trip(), 2).stream()
                .filter(query -> query.destination().equals("POA"))
                .map(SearchQuery::origin)
                .findFirst()
                .orElseThrow();
    }

    @Test
    @DisplayName("rotates which alternative airport gets the slot from day to day")
    void rotatesAlternatives() {
        // Three alternatives (CWB, FLN, GRU) rotate on day-of-year modulo three.
        String first = alternativeChosenOn("2026-09-07");
        String second = alternativeChosenOn("2026-09-08");
        String third = alternativeChosenOn("2026-09-09");

        assertThat(List.of(first, second, third))
                .containsExactlyInAnyOrder("CWB", "FLN", "GRU");
    }

    private String alternativeChosenOn(String date) {
        return plannerOn(date).plan(TestFixtures.trip(), 2).stream()
                .map(SearchQuery::destination)
                .filter(destination -> !destination.equals("POA"))
                .findFirst()
                .orElseThrow();
    }

    @Test
    @DisplayName("a single-query budget goes entirely to the primary destination")
    void singleSlotNeverGoesToAnAlternative() {
        for (int day = 1; day <= 5; day++) {
            List<SearchQuery> plan =
                    plannerOn("2026-09-0" + day).plan(TestFixtures.trip(), 1);
            assertThat(plan).singleElement()
                    .satisfies(query -> assertThat(query.destination()).isEqualTo("POA"));
        }
    }

    @Test
    @DisplayName("never proposes a return that is not after the departure")
    void returnAlwaysAfterDeparture() {
        List<SearchQuery> plan = plannerOn("2026-09-07").plan(TestFixtures.trip(), 100);

        assertThat(plan).isNotEmpty();
        assertThat(plan).allSatisfy(query ->
                assertThat(query.returnDate()).isAfter(query.departureDate()));
    }

    @Test
    @DisplayName("stays inside the configured departure window")
    void staysInsideTheWindow() {
        var trip = TestFixtures.trip();
        List<SearchQuery> plan = plannerOn("2026-09-07").plan(trip, 100);

        assertThat(plan).allSatisfy(query -> {
            assertThat(query.departureDate()).isAfterOrEqualTo(trip.earliestDeparture());
            assertThat(query.departureDate()).isBeforeOrEqualTo(trip.latestDeparture());
        });
    }
}

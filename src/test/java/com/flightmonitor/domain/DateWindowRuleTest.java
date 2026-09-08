package com.flightmonitor.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.flightmonitor.TestFixtures;
import com.flightmonitor.domain.model.Itinerary;
import com.flightmonitor.domain.model.Rejection;
import com.flightmonitor.domain.model.RejectionReason;
import com.flightmonitor.domain.rule.DateWindowRule;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The return constraint is on arrival, not departure — the requirement most likely to be got wrong
 * by comparing the wrong field, so it is tested from both sides of the deadline.
 */
class DateWindowRuleTest {

    private final DateWindowRule rule = new DateWindowRule(TestFixtures.trip());

    @Test
    @DisplayName("accepts a departure inside the flex window")
    void departureInsideWindow() {
        Itinerary onTarget = TestFixtures.acceptableItinerary()
                .departingAt(OffsetDateTime.of(2027, 1, 11, 9, 55, 0, 0, TestFixtures.JAPAN))
                .build();

        assertThat(rule.check(onTarget).rejections()).isEmpty();
    }

    @Test
    @DisplayName("rejects a departure outside the flex window")
    void departureOutsideWindow() {
        Itinerary tooEarly = TestFixtures.acceptableItinerary()
                .departingAt(OffsetDateTime.of(2027, 1, 5, 9, 55, 0, 0, TestFixtures.JAPAN))
                .build();

        assertThat(rule.check(tooEarly).rejections())
                .extracting(Rejection::reason)
                .contains(RejectionReason.DEPARTURE_OUTSIDE_WINDOW);
    }

    @Test
    @DisplayName("accepts a return landing before the hard deadline")
    void returnArrivesInTime() {
        Itinerary landsOn22nd = TestFixtures.acceptableItinerary()
                .returningAt(OffsetDateTime.of(2027, 1, 22, 6, 0, 0, 0, TestFixtures.JAPAN))
                .build();

        DateWindowRule.Result result = rule.check(landsOn22nd);

        assertThat(result.rejections()).isEmpty();
        assertThat(result.warnings()).isEmpty();
    }

    @Test
    @DisplayName("rejects a return landing after the hard deadline")
    void returnArrivesTooLate() {
        Itinerary landsOn24th = TestFixtures.acceptableItinerary()
                .returningAt(OffsetDateTime.of(2027, 1, 24, 6, 0, 0, 0, TestFixtures.JAPAN))
                .build();

        assertThat(rule.check(landsOn24th).rejections())
                .extracting(Rejection::reason)
                .contains(RejectionReason.RETURN_ARRIVAL_TOO_LATE);
    }

    @Test
    @DisplayName("a flight that departs before the deadline but lands after it is still rejected")
    void departureBeforeDeadlineButArrivalAfter() {
        // Leaves Porto Alegre on the 22nd, lands in Tokyo at 04:00 on the 24th: the naive check on
        // the departure date would pass this, the arrival check must not.
        Itinerary lateArrival = TestFixtures.acceptableItinerary()
                .returningAt(OffsetDateTime.of(2027, 1, 24, 4, 0, 0, 0, TestFixtures.JAPAN))
                .build();

        assertThat(lateArrival.returnDepartureAt().toLocalDate())
                .isBefore(java.time.LocalDate.of(2027, 1, 23));
        assertThat(rule.check(lateArrival).rejections())
                .extracting(Rejection::reason)
                .contains(RejectionReason.RETURN_ARRIVAL_TOO_LATE);
    }

    @Test
    @DisplayName("warns, but does not reject, when the return lands inside the safety margin")
    void returnInsideSafetyMargin() {
        // Preferred deadline is 12h before 23 Jan 23:59 JST, i.e. 23 Jan 11:59 JST.
        Itinerary tight = TestFixtures.acceptableItinerary()
                .returningAt(OffsetDateTime.of(2027, 1, 23, 20, 0, 0, 0, TestFixtures.JAPAN))
                .build();

        DateWindowRule.Result result = rule.check(tight);

        assertThat(result.rejections()).isEmpty();
        assertThat(result.warnings()).anyMatch(warning -> warning.contains("safety margin"));
    }

    @Test
    @DisplayName("the deadline is compared as an instant, so offsets cannot smuggle a late arrival")
    void deadlineIsInstantBased() {
        // 23 Jan 22:00 in Brazil is 24 Jan 10:00 in Japan: past the deadline despite the date
        // reading as the 23rd in the local field.
        Itinerary offsetTrap = TestFixtures.acceptableItinerary()
                .returningAt(OffsetDateTime.of(2027, 1, 23, 22, 0, 0, 0, TestFixtures.BRAZIL))
                .build();

        assertThat(rule.check(offsetTrap).rejections())
                .extracting(Rejection::reason)
                .contains(RejectionReason.RETURN_ARRIVAL_TOO_LATE);
    }

    @Test
    @DisplayName("a one-way result is rejected when a round trip was asked for")
    void rejectsMissingReturnLeg() {
        Itinerary oneWay = TestFixtures.acceptableItinerary().oneWay().build();

        assertThat(rule.check(oneWay).rejections())
                .extracting(Rejection::reason)
                .contains(RejectionReason.MISSING_RETURN_LEG);
    }
}

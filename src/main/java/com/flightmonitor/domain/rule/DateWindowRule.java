package com.flightmonitor.domain.rule;

import com.flightmonitor.config.TripConfig;
import com.flightmonitor.domain.model.Itinerary;
import com.flightmonitor.domain.model.Rejection;
import com.flightmonitor.domain.model.RejectionReason;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Enforces the two date constraints.
 *
 * <p><b>Outbound:</b> the departure date, read in the departure airport's own offset, must fall in
 * the configured window around the target date.
 *
 * <p><b>Return:</b> the constraint is on <em>arrival</em>, not on when the return flight leaves.
 * A return departing 22 January that lands 24 January is rejected. Because every timestamp in the
 * model is an {@link OffsetDateTime}, comparing it against the configured deadline
 * ({@code 2027-01-23T23:59+09:00}) is a plain instant comparison and needs no timezone guessing.
 *
 * <p>Arrivals between the preferred deadline (hard deadline minus the safety buffer) and the hard
 * deadline are accepted but flagged, so a tight itinerary is visible rather than silently dropped.
 */
public final class DateWindowRule {

    private final TripConfig trip;

    public DateWindowRule(TripConfig trip) {
        this.trip = trip;
    }

    public Result check(Itinerary itinerary) {
        List<Rejection> rejections = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        OffsetDateTime departure = itinerary.departureAt();
        if (departure == null) {
            rejections.add(Rejection.of(
                    RejectionReason.MISSING_REQUIRED_DATA, "field=outbound departure time"));
        } else {
            LocalDate departureDate = departure.toLocalDate();
            if (departureDate.isBefore(trip.earliestDeparture())
                    || departureDate.isAfter(trip.latestDeparture())) {
                rejections.add(Rejection.of(
                        RejectionReason.DEPARTURE_OUTSIDE_WINDOW,
                        "departure=" + departureDate
                                + " window=" + trip.earliestDeparture() + ".." + trip.latestDeparture()));
            }
            // Local time at the departure airport, taken from the offset the provider reported.
            // Providers that honour the window at search time never trip this; it is the backstop
            // for those that cannot filter by time (and for anyone changing the config later).
            if (trip.hasOutboundWindow()) {
                LocalTime leaves = departure.toLocalTime();
                if (leaves.isBefore(trip.outboundDepartureFrom())
                        || leaves.isAfter(trip.outboundDepartureTo())) {
                    rejections.add(Rejection.of(
                            RejectionReason.DEPARTURE_TIME_OUTSIDE_WINDOW,
                            "departs=" + leaves + " window=" + trip.outboundDepartureFrom()
                                    + ".." + trip.outboundDepartureTo()));
                }
            }
        }

        if (trip.isOneWay()) {
            // Nothing to check on the way back: there is no way back on this ticket.
            return new Result(rejections, warnings);
        }

        if (!itinerary.isRoundTrip()) {
            rejections.add(Rejection.of(
                    RejectionReason.MISSING_RETURN_LEG, "source=" + itinerary.source()));
            return new Result(rejections, warnings);
        }

        OffsetDateTime returnArrival = itinerary.returnArrivalAt();
        if (returnArrival == null) {
            rejections.add(Rejection.of(
                    RejectionReason.MISSING_REQUIRED_DATA, "field=return arrival time"));
            return new Result(rejections, warnings);
        }

        if (returnArrival.isAfter(trip.latestReturnArrival())) {
            rejections.add(Rejection.of(
                    RejectionReason.RETURN_ARRIVAL_TOO_LATE,
                    "arrival=" + returnArrival + " deadline=" + trip.latestReturnArrival()));
        } else if (returnArrival.isAfter(trip.preferredReturnArrival())) {
            warnings.add("Return lands " + returnArrival.toLocalDate()
                    + ", inside the " + trip.minimumReturnBufferHours() + "h safety margin");
        }

        return new Result(rejections, warnings);
    }

    /** How close to the target departure date this itinerary is, in whole days. */
    public long daysFromTargetDeparture(Itinerary itinerary) {
        OffsetDateTime departure = itinerary.departureAt();
        if (departure == null) {
            return Long.MAX_VALUE;
        }
        return Math.abs(java.time.temporal.ChronoUnit.DAYS.between(
                trip.targetDepartureDate(), departure.toLocalDate()));
    }

    public record Result(List<Rejection> rejections, List<String> warnings) {}
}

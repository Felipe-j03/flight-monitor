package com.flightmonitor.config;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * Everything about one monitored journey. Nothing here is hardcoded anywhere else in the codebase:
 * changing this block re-points the monitor at a different trip (Tokyo to Paris, say) with no code
 * change. Multiple blocks can be configured and are monitored independently.
 *
 * @param id                       stable key used in the database and in log lines
 * @param originAirports           IATA codes to depart from, all searched
 * @param destinationAirports      IATA codes to arrive at, all searched
 * @param primaryDestinations      subset of the above considered the "real" target; anything else
 *                                 is flagged as an alternative airport in notifications
 * @param targetDepartureDate      the ideal outbound date
 * @param departureFlexDays        how many days either side of the target are acceptable
 * @param returnWindowStart        earliest acceptable return departure date
 * @param returnWindowEnd          latest acceptable return departure date
 * @param latestReturnArrival      hard deadline for being physically back, with offset
 * @param minimumReturnBufferHours safety margin subtracted from the hard deadline to get the
 *                                 preferred deadline; arrivals inside the margin are accepted but
 *                                 flagged, arrivals past the hard deadline are rejected
 * @param targetMinPriceGbp        below this is "excellent"
 * @param targetMaxPriceGbp        above this is "over budget"
 */
public record TripConfig(
        String id,
        String name,
        boolean enabled,
        List<String> originAirports,
        List<String> destinationAirports,
        List<String> primaryDestinations,
        LocalDate targetDepartureDate,
        int departureFlexDays,
        LocalDate returnWindowStart,
        LocalDate returnWindowEnd,
        OffsetDateTime latestReturnArrival,
        int minimumReturnBufferHours,
        BigDecimal targetMinPriceGbp,
        BigDecimal targetMaxPriceGbp,
        int excellentDurationHours,
        int maxPreferredDurationHours,
        int longDurationHours,
        int maxAbsoluteDurationHours,
        int adults,
        String cabinClass) {

    public TripConfig {
        originAirports = upperCase(originAirports);
        destinationAirports = upperCase(destinationAirports);
        primaryDestinations = primaryDestinations == null || primaryDestinations.isEmpty()
                ? List.of(destinationAirports.get(0))
                : upperCase(primaryDestinations);
        adults = adults <= 0 ? 1 : adults;
        cabinClass = cabinClass == null ? "ECONOMY" : cabinClass.toUpperCase();

        // Fail at startup rather than silently monitoring nothing. An inconsistent window makes
        // the planner emit zero queries, and a monitor that quietly searches for nothing is the
        // worst possible failure mode here.
        require(!originAirports.isEmpty(), "originAirports must not be empty");
        require(!destinationAirports.isEmpty(), "destinationAirports must not be empty");
        require(departureFlexDays >= 0, "departureFlexDays must not be negative");
        require(!returnWindowStart.isAfter(returnWindowEnd),
                "returnWindowStart (" + returnWindowStart + ") is after returnWindowEnd ("
                        + returnWindowEnd + ")");
        require(returnWindowStart.isAfter(targetDepartureDate.plusDays(departureFlexDays)),
                "returnWindowStart (" + returnWindowStart + ") must be after the last possible "
                        + "departure (" + targetDepartureDate.plusDays(departureFlexDays) + ")");
        require(latestReturnArrival.toLocalDate().isAfter(returnWindowStart)
                        || latestReturnArrival.toLocalDate().isEqual(returnWindowStart),
                "latestReturnArrival (" + latestReturnArrival + ") is before the return window "
                        + "even opens (" + returnWindowStart + ")");
        require(targetMinPriceGbp.compareTo(targetMaxPriceGbp) <= 0,
                "targetMinPriceGbp must not exceed targetMaxPriceGbp");
        require(excellentDurationHours <= maxPreferredDurationHours
                        && maxPreferredDurationHours <= longDurationHours
                        && longDurationHours <= maxAbsoluteDurationHours,
                "duration thresholds must increase: excellent <= preferred <= long <= absolute");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException("Invalid trip configuration: " + message);
        }
    }

    private static List<String> upperCase(List<String> codes) {
        return codes == null ? List.of() : codes.stream().map(c -> c.trim().toUpperCase()).toList();
    }

    public LocalDate earliestDeparture() {
        return targetDepartureDate.minusDays(departureFlexDays);
    }

    public LocalDate latestDeparture() {
        return targetDepartureDate.plusDays(departureFlexDays);
    }

    /**
     * The deadline the ranking prefers: the hard deadline minus the safety buffer. Arriving after
     * this but before {@link #latestReturnArrival()} is allowed, just scored lower.
     */
    public OffsetDateTime preferredReturnArrival() {
        return latestReturnArrival.minusHours(minimumReturnBufferHours);
    }

    public boolean isAlternativeDestination(String iata) {
        return !primaryDestinations.contains(iata == null ? null : iata.toUpperCase());
    }

    public Duration excellentDuration() {
        return Duration.ofHours(excellentDurationHours);
    }

    public Duration preferredDuration() {
        return Duration.ofHours(maxPreferredDurationHours);
    }

    public Duration longDuration() {
        return Duration.ofHours(longDurationHours);
    }

    public Duration absoluteDuration() {
        return Duration.ofHours(maxAbsoluteDurationHours);
    }
}

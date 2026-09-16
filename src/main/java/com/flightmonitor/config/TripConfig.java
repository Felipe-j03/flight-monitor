package com.flightmonitor.config;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;

/**
 * Everything about one monitored journey. Nothing here is hardcoded anywhere else in the codebase:
 * changing this block re-points the monitor at a different trip with no code change. Multiple
 * blocks can be configured and are monitored independently.
 *
 * @param id                       stable key used in the database and in log lines; must be unique
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
 * @param targetMinPrice           below this is "excellent", in {@code budgetCurrency}
 * @param targetMaxPrice           above this is "over budget", in {@code budgetCurrency}
 * @param budgetCurrency           ISO code the budget is expressed in. A domestic Brazilian trip is
 *                                 naturally budgeted in reais; prices are still compared in GBP
 *                                 internally, after converting the budget with a live rate
 * @param outboundDepartureFrom    optional hard window: outbound must leave at or after this local
 *                                 time at the departure airport
 * @param outboundDepartureTo      optional hard window: outbound must leave at or before this
 * @param outboundPreferredDeparture optional soft target: closer departures rank higher
 * @param returnPreferredDeparture optional soft target for the return leg's departure time
 * @param combineDestinations      search every destination airport in a single query. Worth it for
 *                                 a city served by several airports (Rio: GIG and SDU), where one
 *                                 metered call can cover all of them
 * @param maxOptionsPerSearch      optional per-trip override of how many outbound options get their
 *                                 return legs resolved (each costs one extra metered call)
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
        BigDecimal targetMinPrice,
        BigDecimal targetMaxPrice,
        String budgetCurrency,
        int excellentDurationHours,
        int maxPreferredDurationHours,
        int longDurationHours,
        int maxAbsoluteDurationHours,
        int adults,
        String cabinClass,
        LocalTime outboundDepartureFrom,
        LocalTime outboundDepartureTo,
        LocalTime outboundPreferredDeparture,
        LocalTime returnPreferredDeparture,
        boolean combineDestinations,
        Integer maxOptionsPerSearch) {

    private static final String GBP = "GBP";

    public TripConfig {
        originAirports = upperCase(originAirports);
        destinationAirports = upperCase(destinationAirports);
        primaryDestinations = primaryDestinations == null || primaryDestinations.isEmpty()
                ? destinationAirports.isEmpty() ? List.of() : List.of(destinationAirports.get(0))
                : upperCase(primaryDestinations);
        adults = adults <= 0 ? 1 : adults;
        cabinClass = cabinClass == null ? "ECONOMY" : cabinClass.toUpperCase();
        budgetCurrency = budgetCurrency == null || budgetCurrency.isBlank()
                ? GBP
                : budgetCurrency.strip().toUpperCase();

        // Fail at startup rather than silently monitoring nothing. An inconsistent window makes
        // the planner emit zero queries, and a monitor that quietly searches for nothing is the
        // worst possible failure mode here.
        require(id != null && !id.isBlank(), "id must not be empty");
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
        require(minimumReturnBufferHours >= 0, "minimumReturnBufferHours must not be negative");
        require(targetMinPrice.compareTo(targetMaxPrice) <= 0,
                "targetMinPrice must not exceed targetMaxPrice");
        require(budgetCurrency.length() == 3, "budgetCurrency must be an ISO-4217 code");
        require(excellentDurationHours <= maxPreferredDurationHours
                        && maxPreferredDurationHours <= longDurationHours
                        && longDurationHours <= maxAbsoluteDurationHours,
                "duration thresholds must increase: excellent <= preferred <= long <= absolute");
        require((outboundDepartureFrom == null) == (outboundDepartureTo == null),
                "outboundDepartureFrom and outboundDepartureTo must be set together");
        require(outboundDepartureFrom == null || !outboundDepartureFrom.isAfter(outboundDepartureTo),
                "outboundDepartureFrom must not be after outboundDepartureTo");
        require(maxOptionsPerSearch == null || maxOptionsPerSearch > 0,
                "maxOptionsPerSearch must be positive when set");
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

    /**
     * Whether an airport — or a combined query such as {@code "GIG,SDU"} — lies outside the
     * intended destinations. A combined query counts as primary if any of its airports is primary.
     */
    public boolean isAlternativeDestination(String iataOrList) {
        if (iataOrList == null) {
            return true;
        }
        return Arrays.stream(iataOrList.split(","))
                .map(code -> code.strip().toUpperCase())
                .noneMatch(primaryDestinations::contains);
    }

    public boolean hasOutboundWindow() {
        return outboundDepartureFrom != null;
    }

    public boolean budgetInGbp() {
        return GBP.equals(budgetCurrency);
    }

    /**
     * The budget as GBP, which is what every price comparison in the domain uses. Only valid once
     * the budget is actually in GBP — call {@link #withBudgetConvertedToGbp(BigDecimal)} first for
     * any other currency. Throwing rather than silently comparing reais against pounds is the point.
     */
    public BigDecimal targetMinPriceGbp() {
        requireGbpBudget();
        return targetMinPrice;
    }

    public BigDecimal targetMaxPriceGbp() {
        requireGbpBudget();
        return targetMaxPrice;
    }

    private void requireGbpBudget() {
        if (!budgetInGbp()) {
            throw new IllegalStateException("trip " + id + " is budgeted in " + budgetCurrency
                    + "; convert it to GBP with a live rate before comparing prices");
        }
    }

    /**
     * A copy of this trip with the budget converted to GBP.
     *
     * @param rateToGbp how many GBP one unit of {@code budgetCurrency} buys, from a live source
     */
    public TripConfig withBudgetConvertedToGbp(BigDecimal rateToGbp) {
        if (budgetInGbp()) {
            return this;
        }
        return new TripConfig(
                id, name, enabled, originAirports, destinationAirports, primaryDestinations,
                targetDepartureDate, departureFlexDays, returnWindowStart, returnWindowEnd,
                latestReturnArrival, minimumReturnBufferHours,
                targetMinPrice.multiply(rateToGbp).setScale(2, RoundingMode.HALF_UP),
                targetMaxPrice.multiply(rateToGbp).setScale(2, RoundingMode.HALF_UP),
                GBP,
                excellentDurationHours, maxPreferredDurationHours, longDurationHours,
                maxAbsoluteDurationHours, adults, cabinClass,
                outboundDepartureFrom, outboundDepartureTo,
                outboundPreferredDeparture, returnPreferredDeparture,
                combineDestinations, maxOptionsPerSearch);
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

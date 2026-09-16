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
 * <p>Three shapes are supported:
 * <ul>
 *   <li><b>round trip</b> — back from where you arrived (the default);</li>
 *   <li><b>open jaw</b> — {@code returnOriginAirports} set: the return leaves from somewhere other
 *       than where the outbound landed (e.g. arrive in Rio, fly home from Porto Alegre). Priced as
 *       one multi-city ticket;</li>
 *   <li><b>one way</b> — no return window at all.</li>
 * </ul>
 *
 * @param id                       stable key used in the database and in log lines; must be unique
 * @param originAirports           IATA codes to depart from, all searched
 * @param destinationAirports      IATA codes to arrive at, all searched
 * @param primaryDestinations      subset of the above considered the "real" target; anything else
 *                                 is flagged as an alternative airport in notifications
 * @param targetDepartureDate      the ideal outbound date
 * @param departureFlexDays        how many days either side of the target are acceptable
 * @param returnWindowStart        earliest acceptable return departure date; null for one way
 * @param returnWindowEnd          latest acceptable return departure date; null for one way
 * @param latestReturnArrival      hard deadline for being physically back, with offset; null for
 *                                 one way
 * @param minimumReturnBufferHours safety margin subtracted from the hard deadline to get the
 *                                 preferred deadline; arrivals inside the margin are accepted but
 *                                 flagged, arrivals past the hard deadline are rejected
 * @param targetMinPrice           below this is "excellent", in {@code budgetCurrency}
 * @param targetMaxPrice           above this is "over budget", in {@code budgetCurrency}
 * @param budgetCurrency           ISO code the budget is expressed in. Prices are compared in GBP
 *                                 internally, after converting the budget with a live rate
 * @param outboundDepartureFrom    optional hard window: outbound must leave at or after this local
 *                                 time at the departure airport
 * @param outboundDepartureTo      optional hard window: outbound must leave at or before this
 * @param outboundPreferredDeparture optional soft target: closer departures rank higher
 * @param returnPreferredDeparture optional soft target for the return leg's departure time
 * @param combineDestinations      search every destination airport in a single query
 * @param maxOptionsPerSearch      optional per-trip override of how many outbound options get their
 *                                 next leg resolved (each costs one extra metered call)
 * @param combineOrigins           search every origin airport in a single query
 * @param returnOriginAirports     open jaw: where the return leg leaves from, when that is not where
 *                                 the outbound landed
 * @param returnDestinationAirports open jaw: where the return leg lands; defaults to the origins
 * @param alternativeReturnOrigins optional alternative return departure airports (e.g. fly home
 *                                 from São Paulo instead of Porto Alegre). Searched as a separate
 *                                 variant and only alerted when clearly cheaper
 * @param alternativeEveryDays     search the alternative variant only every N days, to fit the quota
 * @param discardAboveBudget       treat {@code targetMaxPrice} as a hard cap: anything above it is
 *                                 rejected, and not worth a paid call to resolve
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
        Integer maxOptionsPerSearch,
        boolean combineOrigins,
        List<String> returnOriginAirports,
        List<String> returnDestinationAirports,
        List<String> alternativeReturnOrigins,
        int alternativeEveryDays,
        boolean discardAboveBudget) {

    private static final String GBP = "GBP";

    public TripConfig {
        originAirports = upperCase(originAirports);
        destinationAirports = upperCase(destinationAirports);
        primaryDestinations = primaryDestinations == null || primaryDestinations.isEmpty()
                ? destinationAirports.isEmpty() ? List.of() : List.of(destinationAirports.get(0))
                : upperCase(primaryDestinations);
        returnOriginAirports = upperCase(returnOriginAirports);
        returnDestinationAirports = upperCase(returnDestinationAirports);
        alternativeReturnOrigins = upperCase(alternativeReturnOrigins);
        alternativeEveryDays = alternativeEveryDays <= 0 ? 1 : alternativeEveryDays;
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

        boolean oneWay = returnWindowStart == null && returnWindowEnd == null;
        require(oneWay || (returnWindowStart != null && returnWindowEnd != null),
                "returnWindowStart and returnWindowEnd must be set together");
        if (oneWay) {
            require(returnOriginAirports.isEmpty() && alternativeReturnOrigins.isEmpty(),
                    "a one-way trip cannot have return airports");
        } else {
            require(latestReturnArrival != null, "latestReturnArrival is required for a return trip");
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
        }
        require(alternativeReturnOrigins.isEmpty() || !returnOriginAirports.isEmpty(),
                "alternativeReturnOrigins needs returnOriginAirports (an open-jaw trip)");
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
        return codes == null
                ? List.of()
                : codes.stream()
                        .filter(code -> code != null && !code.isBlank())
                        .map(code -> code.trim().toUpperCase())
                        .toList();
    }

    public boolean isOneWay() {
        return returnWindowStart == null;
    }

    /** The return leaves from somewhere other than where the outbound landed. */
    public boolean isOpenJaw() {
        return !isOneWay() && !returnOriginAirports.isEmpty();
    }

    /** Where the return lands: configured explicitly, or back to the origins. */
    public List<String> returnDestinations() {
        return returnDestinationAirports.isEmpty() ? originAirports : returnDestinationAirports;
    }

    public boolean hasAlternativeReturn() {
        return isOpenJaw() && !alternativeReturnOrigins.isEmpty();
    }

    public boolean isAlternativeReturnOrigin(String iata) {
        return iata != null && hasAlternativeReturn()
                && alternativeReturnOrigins.contains(iata.toUpperCase())
                && !returnOriginAirports.contains(iata.toUpperCase());
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
        return latestReturnArrival == null
                ? null
                : latestReturnArrival.minusHours(minimumReturnBufferHours);
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
                combineDestinations, maxOptionsPerSearch,
                combineOrigins, returnOriginAirports, returnDestinationAirports,
                alternativeReturnOrigins, alternativeEveryDays, discardAboveBudget);
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

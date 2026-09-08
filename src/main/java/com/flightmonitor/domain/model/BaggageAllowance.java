package com.flightmonitor.domain.model;

/**
 * Baggage as reported by the source. {@link #unknown()} is the only correct value when the source
 * says nothing — the UI renders it as "Baggage information unavailable" rather than assuming.
 *
 * @param known         false when the source disclosed nothing at all
 * @param cabinIncluded whether a cabin/carry-on bag is included (null = not disclosed)
 * @param checkedPieces number of checked bags included (null = not disclosed)
 * @param checkedWeightKg weight allowance per checked bag in kg (null = not disclosed)
 * @param description   the source's own wording, kept verbatim when available
 */
public record BaggageAllowance(
        boolean known,
        Boolean personalItemIncluded,
        Boolean cabinIncluded,
        Integer checkedPieces,
        Integer checkedWeightKg,
        String description) {

    private static final BaggageAllowance UNKNOWN =
            new BaggageAllowance(false, null, null, null, null, null);

    public static BaggageAllowance unknown() {
        return UNKNOWN;
    }

    public boolean hasIncludedCheckedBag() {
        return known && checkedPieces != null && checkedPieces > 0;
    }

    public boolean hasIncludedCabinBag() {
        return known && Boolean.TRUE.equals(cabinIncluded);
    }
}

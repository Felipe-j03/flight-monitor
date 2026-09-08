package com.flightmonitor.infrastructure.web;

import com.flightmonitor.infrastructure.persistence.FlightOfferEntity;
import com.flightmonitor.infrastructure.persistence.OfferSourceEntity;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * Read model for the API and the dashboard. Anything a source never disclosed stays null here and
 * is rendered as "unavailable" by the client rather than being filled in with a default.
 */
public record OfferView(
        Long id,
        String tripId,
        String fingerprint,
        String origin,
        String destination,
        OffsetDateTime departureAt,
        OffsetDateTime arrivalAt,
        OffsetDateTime returnDepartureAt,
        OffsetDateTime returnArrivalAt,
        // Calendar values as printed on the ticket. Prefer these for display: the instants above
        // come back from Postgres in UTC, which shifts the date a viewer sees.
        String departureLocalDate,
        String arrivalLocalDate,
        String returnDepartureLocalDate,
        String returnArrivalLocalTime,
        String airlines,
        String flightNumbers,
        int outboundStops,
        Integer inboundStops,
        Integer longestLegMinutes,
        String layovers,
        String airportsVisited,
        String countriesVisited,
        String baggage,
        String fareClass,
        String detailLevel,
        boolean accepted,
        String rejectionReasons,
        String warnings,
        String priceBand,
        String durationBand,
        BigDecimal score,
        BigDecimal currentPriceGbp,
        BigDecimal lowestPriceGbp,
        BigDecimal highestPriceGbp,
        BigDecimal previousPriceGbp,
        OffsetDateTime firstSeenAt,
        OffsetDateTime lastSeenAt,
        OffsetDateTime lastAlertedAt,
        List<SourceView> sources) {

    public static OfferView from(FlightOfferEntity entity, List<OfferSourceEntity> sources) {
        return new OfferView(
                entity.id,
                entity.tripId,
                entity.fingerprint,
                entity.originAirport,
                entity.destinationAirport,
                entity.departureAt,
                entity.arrivalAt,
                entity.returnDepartureAt,
                entity.returnArrivalAt,
                entity.departureLocalDate,
                entity.arrivalLocalDate,
                entity.returnDepartureLocalDate,
                entity.returnArrivalLocalTime,
                entity.airlines,
                entity.flightNumbers,
                entity.outboundStops,
                entity.inboundStops,
                entity.longestLegMinutes,
                entity.layovers,
                entity.airportsVisited,
                entity.countriesVisited,
                baggageText(entity),
                entity.fareClass,
                entity.detailLevel,
                entity.accepted,
                entity.rejectionReasons,
                entity.warnings,
                entity.priceBand,
                entity.durationBand,
                entity.score,
                entity.currentPriceGbp,
                entity.lowestPriceGbp,
                entity.highestPriceGbp,
                entity.previousPriceGbp,
                entity.firstSeenAt,
                entity.lastSeenAt,
                entity.lastAlertedAt,
                sources.stream().map(SourceView::from).toList());
    }

    private static String baggageText(FlightOfferEntity entity) {
        if (!entity.baggageKnown) {
            return "Baggage information unavailable";
        }
        StringBuilder text = new StringBuilder();
        if (entity.baggageCheckedPieces != null) {
            text.append(entity.baggageCheckedPieces).append(" checked");
        }
        if (Boolean.TRUE.equals(entity.baggageCabinIncluded)) {
            text.append(text.isEmpty() ? "" : ", ").append("cabin included");
        }
        if (text.isEmpty()) {
            return entity.baggageDescription == null
                    ? "Baggage information unavailable"
                    : entity.baggageDescription;
        }
        return text.toString();
    }

    /** One provider's quote for this itinerary, including the exact FX conversion it went through. */
    public record SourceView(
            String provider,
            BigDecimal priceOriginal,
            String currency,
            BigDecimal priceGbp,
            BigDecimal fxRate,
            OffsetDateTime fxRetrievedAt,
            String bookingUrl,
            String searchUrl,
            OffsetDateTime observedAt) {

        static SourceView from(OfferSourceEntity source) {
            return new SourceView(
                    source.providerCode,
                    source.priceOriginal,
                    source.currency,
                    source.priceGbp,
                    source.fxRate,
                    source.fxRetrievedAt,
                    source.bookingUrl,
                    source.searchUrl,
                    source.observedAt);
        }
    }
}

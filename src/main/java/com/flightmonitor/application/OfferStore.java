package com.flightmonitor.application;

import com.flightmonitor.domain.model.Itinerary;
import com.flightmonitor.domain.model.Layover;
import com.flightmonitor.domain.model.OfferEvaluation;
import com.flightmonitor.domain.model.RouteDetailLevel;
import com.flightmonitor.domain.port.AirportCatalog;
import com.flightmonitor.infrastructure.persistence.FlightOfferEntity;
import com.flightmonitor.infrastructure.persistence.OfferSourceEntity;
import com.flightmonitor.infrastructure.persistence.PriceHistoryEntity;
import com.flightmonitor.infrastructure.persistence.FlightOfferRepository;
import com.flightmonitor.infrastructure.persistence.OfferSourceRepository;
import com.flightmonitor.infrastructure.persistence.PriceHistoryRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Persists evaluated offers and works out what changed.
 *
 * <p>De-duplication happens here. An offer's fingerprint — airports, dates, airlines, flight
 * numbers, stops — is its identity, so the same flights found by two providers become one row with
 * one {@code offer_source} each. The headline price is the best of those quotes, and both remain
 * visible rather than one being silently discarded.
 *
 * <p>All quotes for one fingerprint are written together in {@link #saveGroup(List)} so the price
 * comparison is made against the state at the start of the run. Saving them one at a time would
 * have the second provider comparing against the first provider's write from moments earlier and
 * reporting a "drop" that never happened.
 *
 * <p>The first time an itinerary is seen its price is recorded as an <em>observed price</em>: no
 * rise or fall is claimed, because there is nothing yet to compare against.
 */
@Service
public class OfferStore {

    private static final Logger log = LoggerFactory.getLogger(OfferStore.class);

    private final FlightOfferRepository offers;
    private final OfferSourceRepository sources;
    private final PriceHistoryRepository history;
    private final AirportCatalog catalog;
    private final Clock clock;

    public OfferStore(
            FlightOfferRepository offers,
            OfferSourceRepository sources,
            PriceHistoryRepository history,
            AirportCatalog catalog,
            Clock clock) {
        this.offers = offers;
        this.sources = sources;
        this.history = history;
        this.catalog = catalog;
        this.clock = clock;
    }

    @Transactional
    public StoredOffer save(OfferEvaluation evaluation) {
        return saveGroup(List.of(evaluation));
    }

    /**
     * @param incoming every evaluation from this run that shares one fingerprint. Repeated quotes
     *                 from the same provider are collapsed to the cheapest before anything is
     *                 written, so one run leaves exactly one history row per provider.
     */
    @Transactional
    public StoredOffer saveGroup(List<OfferEvaluation> incoming) {
        if (incoming.isEmpty()) {
            throw new IllegalArgumentException("cannot save an empty group");
        }

        List<OfferEvaluation> group = oneQuotePerProvider(incoming);
        OfferEvaluation representative = pickRepresentative(group);
        Itinerary itinerary = representative.itinerary();
        OffsetDateTime now = OffsetDateTime.now(clock);
        String fingerprint = itinerary.fingerprint();

        Optional<FlightOfferEntity> existing =
                offers.findByTripIdAndFingerprint(itinerary.tripId(), fingerprint);

        boolean created = existing.isEmpty();
        FlightOfferEntity entity = existing.orElseGet(FlightOfferEntity::new);
        BigDecimal priceBefore = created ? null : entity.currentPriceGbp;
        BigDecimal lowestBefore = created ? null : entity.lowestPriceGbp;

        applyItinerary(entity, itinerary, fingerprint, now, created);
        applyEvaluation(entity, representative);
        entity = offers.save(entity);

        for (OfferEvaluation evaluation : group) {
            upsertSource(entity, evaluation.itinerary(), now);
        }
        recomputeHeadlinePrice(entity, now);
        entity.previousPriceGbp = priceBefore;
        entity = offers.save(entity);

        for (OfferEvaluation evaluation : group) {
            appendHistory(entity, evaluation.itinerary(), now);
        }

        boolean newAllTimeLow = !created
                && lowestBefore != null
                && entity.currentPriceGbp.compareTo(lowestBefore) < 0;

        PriceChange change = new PriceChange(
                entity.currentPriceGbp,
                priceBefore,
                lowestBefore,
                created,
                newAllTimeLow,
                (int) history.countByOfferId(entity.id));

        logOutcome(entity, itinerary, change, created, fingerprint);
        return new StoredOffer(
                entity, sources.findByOfferId(entity.id), change, created, providerCodes(group));
    }

    /**
     * Collapses a run's quotes to one per provider, keeping the cheapest.
     *
     * <p>A single provider can return the same itinerary from several queries — neighbouring
     * departure dates often surface the identical flights. Without this, one run would append a
     * dozen identical rows to the price history and inflate the observation count, making the
     * history read as activity where there was none. One observation per provider per run is what
     * the history is supposed to mean.
     */
    private static List<OfferEvaluation> oneQuotePerProvider(List<OfferEvaluation> incoming) {
        Map<String, OfferEvaluation> cheapestByProvider = new LinkedHashMap<>();
        for (OfferEvaluation evaluation : incoming) {
            cheapestByProvider.merge(
                    evaluation.itinerary().source(),
                    evaluation,
                    (existing, candidate) ->
                            candidate.itinerary().price().gbpAmount()
                                            .compareTo(existing.itinerary().price().gbpAmount()) < 0
                                    ? candidate
                                    : existing);
        }
        return List.copyOf(cheapestByProvider.values());
    }

    /**
     * When providers disagree about detail, the richest view wins: an accepted, fully-detailed
     * itinerary describes the trip better than a stop-count-only one, even if the latter is
     * cheaper. The cheaper price is still captured, as an {@code offer_source}.
     */
    private static OfferEvaluation pickRepresentative(List<OfferEvaluation> group) {
        return group.stream()
                .max(Comparator
                        .comparing((OfferEvaluation e) -> e.accepted() ? 1 : 0)
                        .thenComparing(e -> e.itinerary().detailLevel() == RouteDetailLevel.FULL_ITINERARY ? 1 : 0)
                        .thenComparing(e -> e.itinerary().price().gbpAmount(), Comparator.reverseOrder()))
                .orElseThrow();
    }

    private static List<String> providerCodes(List<OfferEvaluation> group) {
        return group.stream()
                .map(evaluation -> evaluation.itinerary().source())
                .distinct()
                .sorted()
                .toList();
    }

    private void logOutcome(
            FlightOfferEntity entity,
            Itinerary itinerary,
            PriceChange change,
            boolean created,
            String fingerprint) {

        if (created) {
            log.info("OFFER_FOUND trip={} fingerprint={} price=GBP{} source={} route={}->{} stops={}",
                    entity.tripId, fingerprint, entity.currentPriceGbp, itinerary.source(),
                    entity.originAirport, entity.destinationAirport, entity.outboundStops);
        } else if (change.isDrop()) {
            log.info("PRICE_DROP_DETECTED trip={} fingerprint={} from=GBP{} to=GBP{} "
                            + "delta=GBP{} percent={}",
                    entity.tripId, fingerprint, change.previousGbp(), entity.currentPriceGbp,
                    change.dropGbp(), change.dropPercent());
        } else if (change.isRise()) {
            log.info("PRICE_CHANGED trip={} fingerprint={} from=GBP{} to=GBP{} direction=up",
                    entity.tripId, fingerprint, change.previousGbp(), entity.currentPriceGbp);
        }
    }

    private void applyItinerary(
            FlightOfferEntity entity,
            Itinerary itinerary,
            String fingerprint,
            OffsetDateTime now,
            boolean created) {

        entity.tripId = itinerary.tripId();
        entity.fingerprint = fingerprint;
        entity.originAirport = itinerary.originAirport();
        entity.destinationAirport = itinerary.destinationAirport();
        entity.departureAt = itinerary.departureAt();
        entity.arrivalAt = itinerary.arrivalAt();
        entity.returnDepartureAt = itinerary.returnDepartureAt();
        entity.returnArrivalAt = itinerary.returnArrivalAt();
        entity.departureLocalDate = localDate(itinerary.departureAt());
        entity.arrivalLocalDate = localDate(itinerary.arrivalAt());
        entity.returnDepartureLocalDate = localDate(itinerary.returnDepartureAt());
        entity.returnArrivalLocalTime = localDateTime(itinerary.returnArrivalAt());
        entity.airlines = join(itinerary.airlines(), 255);
        entity.flightNumbers = join(itinerary.flightNumbers(), 500);
        entity.outboundStops = itinerary.outbound().stops();
        entity.inboundStops = itinerary.inbound() == null ? null : itinerary.inbound().stops();
        entity.longestLegMinutes = itinerary.longestLegDuration() == null
                ? null
                : (int) itinerary.longestLegDuration().toMinutes();
        entity.layovers = join(describeLayovers(itinerary), 1000);
        entity.airportsVisited = join(new ArrayList<>(itinerary.airportsVisited()), 500);
        entity.countriesVisited = join(new ArrayList<>(countriesOf(itinerary)), 500);
        entity.baggageKnown = itinerary.baggage().known();
        entity.baggageCabinIncluded = itinerary.baggage().cabinIncluded();
        entity.baggageCheckedPieces = itinerary.baggage().checkedPieces();
        entity.baggageDescription = truncate(itinerary.baggage().description(), 500);
        entity.fareClass = truncate(itinerary.fareClass(), 64);
        entity.detailLevel = itinerary.detailLevel().name();
        entity.lastSeenAt = now;

        if (created) {
            BigDecimal price = itinerary.price().gbpAmount();
            entity.firstSeenAt = now;
            entity.currentPriceGbp = price;
            entity.lowestPriceGbp = price;
            entity.lowestPriceAt = now;
            entity.highestPriceGbp = price;
        }
    }

    private void applyEvaluation(FlightOfferEntity entity, OfferEvaluation evaluation) {
        entity.accepted = evaluation.accepted();
        entity.rejectionReasons = truncate(evaluation.rejectionSummary(), 1000);
        entity.warnings = truncate(String.join(" | ", evaluation.warnings()), 1000);
        entity.priceBand = evaluation.priceBand() == null ? null : evaluation.priceBand().name();
        entity.durationBand =
                evaluation.durationBand() == null ? null : evaluation.durationBand().name();
        entity.score = BigDecimal.valueOf(evaluation.score());
    }

    private void upsertSource(FlightOfferEntity entity, Itinerary itinerary, OffsetDateTime now) {
        OfferSourceEntity source = sources
                .findByOfferIdAndProviderCode(entity.id, itinerary.source())
                .orElseGet(OfferSourceEntity::new);
        source.offer = entity;
        source.providerCode = itinerary.source();
        source.providerOfferId = truncate(itinerary.providerOfferId(), 500);
        source.priceOriginal = itinerary.price().original().amount();
        source.currency = itinerary.price().original().currency();
        source.priceGbp = itinerary.price().gbpAmount();
        source.fxRate = itinerary.price().rateToGbp();
        source.fxRetrievedAt = itinerary.price().convertedAt().atOffset(now.getOffset());
        source.bookingUrl = truncate(itinerary.bookingUrl(), 2000);
        source.searchUrl = truncate(itinerary.searchUrl(), 2000);
        source.observedAt = now;
        sources.save(source);
    }

    /** Headline price is the best of the latest quote held for each provider. */
    private void recomputeHeadlinePrice(FlightOfferEntity entity, OffsetDateTime now) {
        BigDecimal best = sources.findByOfferId(entity.id).stream()
                .map(source -> source.priceGbp)
                .min(BigDecimal::compareTo)
                .orElse(entity.currentPriceGbp);

        entity.currentPriceGbp = best;
        if (best.compareTo(entity.lowestPriceGbp) < 0) {
            entity.lowestPriceGbp = best;
            entity.lowestPriceAt = now;
        }
        if (best.compareTo(entity.highestPriceGbp) > 0) {
            entity.highestPriceGbp = best;
        }
    }

    private void appendHistory(FlightOfferEntity entity, Itinerary itinerary, OffsetDateTime now) {
        PriceHistoryEntity row = new PriceHistoryEntity();
        row.offerId = entity.id;
        row.tripId = entity.tripId;
        row.observedAt = now;
        row.priceGbp = itinerary.price().gbpAmount();
        row.priceOriginal = itinerary.price().original().amount();
        row.currency = itinerary.price().original().currency();
        row.fxRate = itinerary.price().rateToGbp();
        row.providerCode = itinerary.source();
        history.save(row);
    }

    @Transactional
    public void markAlerted(Long offerId, OffsetDateTime when) {
        offers.findById(offerId).ifPresent(entity -> {
            entity.lastAlertedAt = when;
            offers.save(entity);
        });
    }

    private Set<String> countriesOf(Itinerary itinerary) {
        return itinerary.airportsVisited().stream()
                .map(catalog::find)
                .flatMap(Optional::stream)
                .map(airport -> airport.countryName() + " (" + airport.countryCode() + ")")
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private static List<String> describeLayovers(Itinerary itinerary) {
        List<String> parts = new ArrayList<>();
        for (Layover layover : itinerary.outbound().layovers()) {
            parts.add("IDA " + layover.airport() + " "
                    + (layover.duration() == null ? "?" : layover.duration().toHours() + "h"));
        }
        if (itinerary.inbound() != null) {
            for (Layover layover : itinerary.inbound().layovers()) {
                parts.add("VOLTA " + layover.airport() + " "
                        + (layover.duration() == null ? "?" : layover.duration().toHours() + "h"));
            }
        }
        return parts;
    }

    /** The calendar date at the airport, taken from the offset the provider actually reported. */
    private static String localDate(OffsetDateTime value) {
        return value == null ? null : value.toLocalDate().toString();
    }

    /** Local date and time at the airport, e.g. {@code 2027-01-23T03:05}. */
    private static String localDateTime(OffsetDateTime value) {
        return value == null ? null : value.toLocalDateTime().withNano(0).withSecond(0).toString();
    }

    private static String join(List<String> values, int maxLength) {
        return truncate(String.join(",", values), maxLength);
    }

    private static String truncate(String value, int maxLength) {
        if (value == null || value.isEmpty()) {
            return null;
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }

    /**
     * An offer as it now stands in the database, plus what this observation changed.
     *
     * <p>Sources are carried explicitly rather than as a JPA association: callers such as the
     * notifier read them outside the transaction, and an association would either need an open
     * session or an eager fetch on every query that touches an offer.
     */
    public record StoredOffer(
            FlightOfferEntity entity,
            List<OfferSourceEntity> sources,
            PriceChange change,
            boolean created,
            List<String> providerCodes) {}
}

package com.flightmonitor.infrastructure.persistence;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Append-only price observations. */
public interface PriceHistoryRepository extends JpaRepository<PriceHistoryEntity, Long> {

    List<PriceHistoryEntity> findByOfferIdOrderByObservedAtAsc(Long offerId);

    List<PriceHistoryEntity> findByTripIdOrderByObservedAtDesc(String tripId, Pageable pageable);

    /**
     * History for itineraries that actually passed the filters. A rejected route's price is not an
     * opportunity, so including it would make the trend line track fares the user can never take.
     */
    @Query("select h from PriceHistoryEntity h where h.tripId = :tripId and h.offerId in "
            + "(select o.id from FlightOfferEntity o "
            + " where o.tripId = :tripId and o.accepted = true) "
            + "order by h.observedAt desc")
    List<PriceHistoryEntity> findAcceptedByTrip(@Param("tripId") String tripId, Pageable pageable);

    long countByOfferId(Long offerId);

    @Query("select min(h.priceGbp) from PriceHistoryEntity h "
            + "where h.offerId = :offerId and h.observedAt < :before")
    BigDecimal findLowestBefore(
            @Param("offerId") Long offerId, @Param("before") OffsetDateTime before);
}

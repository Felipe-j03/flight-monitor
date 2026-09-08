package com.flightmonitor.infrastructure.persistence;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Offers, addressed by their deterministic fingerprint rather than by any provider's id. */
public interface FlightOfferRepository extends JpaRepository<FlightOfferEntity, Long> {

    Optional<FlightOfferEntity> findByTripIdAndFingerprint(String tripId, String fingerprint);

    List<FlightOfferEntity> findByTripIdAndAcceptedTrueOrderByCurrentPriceGbpAsc(
            String tripId, Pageable pageable);

    List<FlightOfferEntity> findByTripIdAndAcceptedTrueOrderByScoreDesc(
            String tripId, Pageable pageable);

    List<FlightOfferEntity> findByTripIdOrderByLastSeenAtDesc(String tripId, Pageable pageable);

    long countByTripIdAndAcceptedTrue(String tripId);

    @Query("select min(o.currentPriceGbp) from FlightOfferEntity o "
            + "where o.tripId = :tripId and o.accepted = true")
    BigDecimal findCurrentBestPrice(@Param("tripId") String tripId);

    @Query("select min(o.lowestPriceGbp) from FlightOfferEntity o "
            + "where o.tripId = :tripId and o.accepted = true")
    BigDecimal findAllTimeLowPrice(@Param("tripId") String tripId);

    /**
     * Cheapest accepted offer landing at one of the trip's intended airports. This is the yardstick
     * an alternative airport has to beat before it is worth telling anyone about.
     */
    @Query("select min(o.currentPriceGbp) from FlightOfferEntity o "
            + "where o.tripId = :tripId and o.accepted = true "
            + "and o.destinationAirport in :destinations")
    BigDecimal findBestPriceForDestinations(
            @Param("tripId") String tripId, @Param("destinations") List<String> destinations);
}

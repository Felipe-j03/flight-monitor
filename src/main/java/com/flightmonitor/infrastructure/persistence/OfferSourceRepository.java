package com.flightmonitor.infrastructure.persistence;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** Per-provider quotes for an offer, which is how duplicates stay visible instead of being lost. */
public interface OfferSourceRepository extends JpaRepository<OfferSourceEntity, Long> {

    Optional<OfferSourceEntity> findByOfferIdAndProviderCode(Long offerId, String providerCode);

    List<OfferSourceEntity> findByOfferId(Long offerId);
}

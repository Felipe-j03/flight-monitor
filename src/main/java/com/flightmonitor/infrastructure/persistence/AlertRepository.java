package com.flightmonitor.infrastructure.persistence;

import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/** Sent (and attempted) alerts. */
public interface AlertRepository extends JpaRepository<AlertEntity, Long> {

    List<AlertEntity> findByTripIdOrderByCreatedAtDesc(String tripId, Pageable pageable);

    long countByTripId(String tripId);
}

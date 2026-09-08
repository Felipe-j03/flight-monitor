package com.flightmonitor.infrastructure.persistence;

import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/** One row per execution of the pipeline. */
public interface SearchRunRepository extends JpaRepository<SearchRunEntity, Long> {

    Optional<SearchRunEntity> findFirstByTripIdOrderByStartedAtDesc(String tripId);

    Optional<SearchRunEntity> findFirstByOrderByStartedAtDesc();

    List<SearchRunEntity> findByTripIdOrderByStartedAtDesc(String tripId, Pageable pageable);
}

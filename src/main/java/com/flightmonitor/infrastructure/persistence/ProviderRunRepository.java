package com.flightmonitor.infrastructure.persistence;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** Per-provider outcome of each query, so provider health is observable. */
public interface ProviderRunRepository extends JpaRepository<ProviderRunEntity, Long> {

    List<ProviderRunEntity> findBySearchRunIdOrderByIdAsc(Long searchRunId);

    Optional<ProviderRunEntity> findFirstByProviderCodeOrderByExecutedAtDesc(String providerCode);
}

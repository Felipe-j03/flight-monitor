package com.flightmonitor.infrastructure.persistence;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** Persistent monthly call counters backing the free-tier budget guard. */
public interface ProviderUsageRepository
        extends JpaRepository<ProviderUsageEntity, ProviderUsageEntity.Key> {

    Optional<ProviderUsageEntity> findByProviderCodeAndYearMonth(
            String providerCode, String yearMonth);
}

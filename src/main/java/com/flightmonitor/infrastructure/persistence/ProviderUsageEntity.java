package com.flightmonitor.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.time.OffsetDateTime;
import java.util.Objects;

/**
 * Persistent monthly call counter per provider. Survives restarts, which matters: an in-memory
 * counter would reset on every deploy and quietly blow through a metered free tier.
 */
@Entity
@Table(name = "provider_usage")
@IdClass(ProviderUsageEntity.Key.class)
public class ProviderUsageEntity {

    @Id
    @Column(name = "provider_code", nullable = false, length = 32)
    public String providerCode;

    /** ISO year-month, e.g. {@code 2026-09}. */
    @Id
    @Column(name = "year_month", nullable = false, length = 7)
    public String yearMonth;

    @Column(nullable = false)
    public int calls;

    @Column(name = "updated_at", nullable = false)
    public OffsetDateTime updatedAt;

    public static class Key implements Serializable {
        public String providerCode;
        public String yearMonth;

        public Key() {}

        public Key(String providerCode, String yearMonth) {
            this.providerCode = providerCode;
            this.yearMonth = yearMonth;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof Key key)) {
                return false;
            }
            return Objects.equals(providerCode, key.providerCode)
                    && Objects.equals(yearMonth, key.yearMonth);
        }

        @Override
        public int hashCode() {
            return Objects.hash(providerCode, yearMonth);
        }
    }
}

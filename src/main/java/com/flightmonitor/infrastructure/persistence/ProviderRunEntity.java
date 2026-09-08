package com.flightmonitor.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;

/** Per-provider outcome of one query, so provider health is observable rather than guessed at. */
@Entity
@Table(name = "provider_run")
public class ProviderRunEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(name = "search_run_id", nullable = false)
    public Long searchRunId;

    @Column(name = "provider_code", nullable = false, length = 32)
    public String providerCode;

    @Column(name = "query_summary", nullable = false, length = 255)
    public String querySummary;

    @Column(nullable = false)
    public boolean success;

    @Column(name = "error_message", length = 1000)
    public String errorMessage;

    @Column(name = "offers_found", nullable = false)
    public int offersFound;

    @Column(name = "elapsed_millis", nullable = false)
    public long elapsedMillis;

    @Column(name = "executed_at", nullable = false)
    public OffsetDateTime executedAt;
}

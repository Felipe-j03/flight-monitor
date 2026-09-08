package com.flightmonitor.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;

/** One execution of the search pipeline for one trip, successful or not. */
@Entity
@Table(name = "search_run")
public class SearchRunEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(name = "trip_id", nullable = false, length = 64)
    public String tripId;

    @Column(name = "started_at", nullable = false)
    public OffsetDateTime startedAt;

    @Column(name = "finished_at")
    public OffsetDateTime finishedAt;

    @Column(name = "queries_planned", nullable = false)
    public int queriesPlanned;

    @Column(name = "queries_executed", nullable = false)
    public int queriesExecuted;

    @Column(name = "offers_found", nullable = false)
    public int offersFound;

    @Column(name = "offers_accepted", nullable = false)
    public int offersAccepted;

    @Column(name = "offers_rejected", nullable = false)
    public int offersRejected;

    @Column(name = "alerts_sent", nullable = false)
    public int alertsSent;

    @Column(nullable = false, length = 32)
    public String status;

    @Column(length = 2000)
    public String notes;
}

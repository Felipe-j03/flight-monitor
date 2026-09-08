package com.flightmonitor.application;

import com.flightmonitor.config.FlightMonitorProperties;
import com.flightmonitor.config.TripConfig;
import com.flightmonitor.infrastructure.persistence.SearchRunRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Runs the pipeline on a fixed interval.
 *
 * <p>A lock, not a queue: if a run is somehow still going when the next tick arrives, the tick is
 * skipped rather than stacked. Overlapping runs against a metered API would burn budget twice for
 * the same information.
 */
@Component
public class SearchScheduler {

    private static final Logger log = LoggerFactory.getLogger(SearchScheduler.class);

    private final SearchOrchestrator orchestrator;
    private final FlightMonitorProperties properties;
    private final SearchRunRepository searchRuns;
    private final Clock clock;
    private final ReentrantLock lock = new ReentrantLock();
    private final AtomicReference<Instant> lastRunAt = new AtomicReference<>();
    private final AtomicReference<Instant> nextRunAt = new AtomicReference<>();

    public SearchScheduler(
            SearchOrchestrator orchestrator,
            FlightMonitorProperties properties,
            SearchRunRepository searchRuns,
            Clock clock) {
        this.orchestrator = orchestrator;
        this.properties = properties;
        this.searchRuns = searchRuns;
        this.clock = clock;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        Duration interval = Duration.ofHours(properties.search().intervalHours());
        nextRunAt.set(Instant.now().plus(interval));

        if (!properties.search().enabled()) {
            log.warn("Scheduler is disabled (flight-monitor.search.enabled=false). "
                    + "Trigger searches manually with POST /api/search.");
            return;
        }
        log.info("Scheduler active: every {}h, {} trip(s) monitored",
                properties.search().intervalHours(), properties.enabledTrips().size());

        if (properties.search().runOnStartup()) {
            log.info("Running an immediate search because search.run-on-startup=true");
            trigger();
            return;
        }
        catchUpIfOverdue();
    }

    /**
     * Runs immediately when the last search is older than one interval.
     *
     * <p>{@code @Scheduled} counts from process start, so on a machine that is switched off
     * overnight the countdown restarts every boot. A laptop that is never awake for a full interval
     * would therefore never search at all — and would fail silently, with the dashboard showing a
     * "last search" that simply never advances. Looking at what is actually recorded in the
     * database, rather than at how long this process has been up, is what makes intermittent uptime
     * work.
     *
     * <p>Safe against restart loops by construction: a run records its start time before doing
     * anything, so a process that comes back a minute later sees a recent run and stays put.
     */
    private void catchUpIfOverdue() {
        Duration interval = Duration.ofHours(properties.search().intervalHours());
        Optional<OffsetDateTime> lastRun = searchRuns.findFirstByOrderByStartedAtDesc()
                .map(run -> run.startedAt);

        if (lastRun.isEmpty()) {
            log.info("No search has ever run; starting one now rather than waiting {}h",
                    properties.search().intervalHours());
            trigger();
            return;
        }

        Duration since = Duration.between(lastRun.get().toInstant(), Instant.now(clock));
        if (since.compareTo(interval) >= 0) {
            log.info("Last search was {}h ago, longer than the {}h interval; catching up now",
                    since.toHours(), properties.search().intervalHours());
            trigger();
        } else {
            log.info("Last search was {}h ago; next one due in about {}h",
                    since.toHours(), interval.minus(since).toHours());
        }
    }

    @Scheduled(
            fixedDelayString = "${flight-monitor.search.interval-hours:12}",
            initialDelayString = "${flight-monitor.search.interval-hours:12}",
            timeUnit = TimeUnit.HOURS)
    public void scheduledRun() {
        if (!properties.search().enabled()) {
            return;
        }
        trigger();
    }

    /** Fires a run now. Returns false when one was already in progress. */
    public boolean trigger() {
        return runNow(null).isPresent();
    }

    /**
     * Runs a search under the same lock the scheduler uses, so a manual trigger can never overlap
     * a scheduled one. Two concurrent runs would spend the metered provider budget twice for the
     * same information.
     *
     * @param trip the trip to search, or null for every enabled trip
     * @return the summaries, or empty when a run was already in progress
     */
    public Optional<List<SearchOrchestrator.SearchSummary>> runNow(TripConfig trip) {
        if (!lock.tryLock()) {
            log.warn("Skipping search: the previous run has not finished yet");
            return Optional.empty();
        }
        try {
            List<SearchOrchestrator.SearchSummary> summaries = trip == null
                    ? orchestrator.runAll()
                    : List.of(orchestrator.run(trip));
            lastRunAt.set(Instant.now());
            return Optional.of(summaries);
        } catch (RuntimeException e) {
            log.error("Search run failed unexpectedly: {}", e.toString(), e);
            throw e;
        } finally {
            nextRunAt.set(Instant.now().plus(Duration.ofHours(properties.search().intervalHours())));
            lock.unlock();
        }
    }

    public Optional<Instant> lastRunAt() {
        return Optional.ofNullable(lastRunAt.get());
    }

    public Optional<Instant> nextRunAt() {
        return properties.search().enabled()
                ? Optional.ofNullable(nextRunAt.get())
                : Optional.empty();
    }

    public boolean running() {
        return lock.isLocked();
    }
}

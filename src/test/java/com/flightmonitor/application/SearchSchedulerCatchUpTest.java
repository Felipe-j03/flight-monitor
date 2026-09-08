package com.flightmonitor.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.flightmonitor.TestFixtures;
import com.flightmonitor.config.FlightMonitorProperties;
import com.flightmonitor.config.TripConfig;
import com.flightmonitor.infrastructure.persistence.SearchRunEntity;
import com.flightmonitor.infrastructure.persistence.SearchRunRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@code @Scheduled} counts from process start, which is fine on a server and useless on a laptop
 * that gets switched off every night: the countdown restarts on every boot, so a machine never
 * awake for a full interval would never search at all — silently.
 */
class SearchSchedulerCatchUpTest {

    private static final Instant NOW = Instant.parse("2026-09-08T12:00:00Z");

    private static FlightMonitorProperties propertiesWithInterval(int hours) {
        return new FlightMonitorProperties(
                List.of(TestFixtures.trip()),
                TestFixtures.safety(),
                TestFixtures.alerts(),
                TestFixtures.scoring(),
                new FlightMonitorProperties.SearchConfig(true, hours, 12, false),
                new FlightMonitorProperties.CurrencyConfig("GBP", null, 0, 0),
                Map.of());
    }

    private static SearchRunEntity runStartedAt(Instant instant) {
        SearchRunEntity run = new SearchRunEntity();
        run.startedAt = instant.atOffset(ZoneOffset.UTC);
        return run;
    }

    private record Fixture(
            SearchScheduler scheduler, SearchOrchestrator orchestrator) {}

    private static Fixture schedulerWith(Optional<SearchRunEntity> lastRun, int intervalHours) {
        SearchOrchestrator orchestrator = mock(SearchOrchestrator.class);
        when(orchestrator.runAll()).thenReturn(List.of());

        SearchRunRepository runs = mock(SearchRunRepository.class);
        when(runs.findFirstByOrderByStartedAtDesc()).thenReturn(lastRun);

        SearchScheduler scheduler = new SearchScheduler(
                orchestrator,
                propertiesWithInterval(intervalHours),
                runs,
                Clock.fixed(NOW, ZoneOffset.UTC));
        return new Fixture(scheduler, orchestrator);
    }

    @Test
    @DisplayName("searches on boot when the last run is older than one interval")
    void catchesUpWhenOverdue() {
        Fixture fixture = schedulerWith(
                Optional.of(runStartedAt(NOW.minusSeconds(13 * 3600))), 12);

        fixture.scheduler().onStartup();

        verify(fixture.orchestrator()).runAll();
    }

    @Test
    @DisplayName("stays put when the last run is recent, so a restart does not re-search")
    void doesNotSearchWhenRecent() {
        Fixture fixture = schedulerWith(
                Optional.of(runStartedAt(NOW.minusSeconds(60))), 12);

        fixture.scheduler().onStartup();

        verify(fixture.orchestrator(), never()).runAll();
    }

    @Test
    @DisplayName("a restart loop cannot burn the provider budget")
    void restartLoopIsHarmless() {
        // A run records its start time before doing anything, so a process that comes back a
        // moment later sees a recent run. Three quick restarts must trigger nothing.
        Fixture fixture = schedulerWith(
                Optional.of(runStartedAt(NOW.minusSeconds(5))), 12);

        fixture.scheduler().onStartup();
        fixture.scheduler().onStartup();
        fixture.scheduler().onStartup();

        verify(fixture.orchestrator(), never()).runAll();
    }

    @Test
    @DisplayName("searches immediately the very first time, rather than waiting an interval")
    void searchesOnFirstEverStartup() {
        Fixture fixture = schedulerWith(Optional.empty(), 12);

        fixture.scheduler().onStartup();

        verify(fixture.orchestrator()).runAll();
    }

    @Test
    @DisplayName("a disabled scheduler never searches on boot, however overdue it looks")
    void disabledSchedulerStaysSilent() {
        SearchOrchestrator orchestrator = mock(SearchOrchestrator.class);
        SearchRunRepository runs = mock(SearchRunRepository.class);
        when(runs.findFirstByOrderByStartedAtDesc())
                .thenReturn(Optional.of(runStartedAt(NOW.minusSeconds(999_999))));

        FlightMonitorProperties disabled = new FlightMonitorProperties(
                List.of(TestFixtures.trip()),
                TestFixtures.safety(),
                TestFixtures.alerts(),
                TestFixtures.scoring(),
                new FlightMonitorProperties.SearchConfig(false, 12, 12, false),
                new FlightMonitorProperties.CurrencyConfig("GBP", null, 0, 0),
                Map.of());

        new SearchScheduler(orchestrator, disabled, runs, Clock.fixed(NOW, ZoneOffset.UTC))
                .onStartup();

        verify(orchestrator, never()).runAll();
        verify(orchestrator, never()).run(any(TripConfig.class));
    }

    @Test
    @DisplayName("the recorded start time is what counts, not how long this process has been up")
    void usesPersistedTimeNotUptime() {
        // The scheduler has just been constructed, so any uptime-based check would say "0h".
        Fixture fixture = schedulerWith(
                Optional.of(runStartedAt(NOW.minusSeconds(48 * 3600))), 12);

        assertThat(fixture.scheduler().lastRunAt())
                .as("nothing has run in this process yet")
                .isEmpty();

        fixture.scheduler().onStartup();

        verify(fixture.orchestrator()).runAll();
    }
}

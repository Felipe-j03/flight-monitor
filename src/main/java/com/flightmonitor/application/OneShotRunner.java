package com.flightmonitor.application;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

/**
 * Runs one search and exits, for schedulers that live outside the process — GitHub Actions cron
 * being the case this exists for, where the runner is destroyed after every job.
 *
 * <p>Enabled with {@code --flight-monitor.search.one-shot=true}. The database must therefore be
 * external and durable, or the price history dies with the runner and every observation looks like
 * a first sighting forever.
 *
 * <p>The exit code is 0 even when providers failed: a provider outage is expected operational
 * noise, not a broken job, and a red cron every time an API hiccups trains people to ignore it.
 * Genuine failures (bad configuration, unreachable database) still crash startup and fail loudly.
 */
@Component
@ConditionalOnProperty(name = "flight-monitor.search.one-shot", havingValue = "true")
public class OneShotRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(OneShotRunner.class);

    private final SearchOrchestrator orchestrator;
    private final ApplicationContext context;

    public OneShotRunner(SearchOrchestrator orchestrator, ApplicationContext context) {
        this.orchestrator = orchestrator;
        this.context = context;
    }

    @Override
    public void run(ApplicationArguments args) {
        log.info("One-shot mode: running a single search, then exiting");
        int exitCode = 0;
        try {
            List<SearchOrchestrator.SearchSummary> summaries = orchestrator.runAll();
            for (SearchOrchestrator.SearchSummary summary : summaries) {
                log.info("trip={} status={} offers={} accepted={} rejected={} alerts={}",
                        summary.tripId(), summary.status(), summary.offersFound(),
                        summary.offersAccepted(), summary.offersRejected(), summary.alertsSent());
                if ("NO_PROVIDERS".equals(summary.status())) {
                    exitCode = 1;
                }
            }
        } catch (RuntimeException e) {
            log.error("One-shot run failed: {}", e.toString(), e);
            exitCode = 1;
        }
        int finalExitCode = exitCode;
        System.exit(org.springframework.boot.SpringApplication.exit(context, () -> finalExitCode));
    }
}

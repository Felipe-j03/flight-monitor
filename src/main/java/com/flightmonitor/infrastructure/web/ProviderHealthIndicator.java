package com.flightmonitor.infrastructure.web;

import com.flightmonitor.application.SearchScheduler;
import com.flightmonitor.domain.port.FlightSearchProvider;
import com.flightmonitor.domain.port.NotificationPort;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Surfaces provider and scheduler state through {@code /actuator/health}.
 *
 * <p>Reports DOWN when no provider is usable, because a monitor that cannot ask anyone anything is
 * not healthy no matter how well the web layer is running. A missing Telegram configuration is
 * reported but does not fail the check: collecting prices is still worth doing.
 */
@Component("flightProviders")
public class ProviderHealthIndicator implements HealthIndicator {

    private final List<FlightSearchProvider> providers;
    private final NotificationPort notifier;
    private final SearchScheduler scheduler;

    public ProviderHealthIndicator(
            List<FlightSearchProvider> providers,
            NotificationPort notifier,
            SearchScheduler scheduler) {
        this.providers = providers;
        this.notifier = notifier;
        this.scheduler = scheduler;
    }

    @Override
    public Health health() {
        Map<String, Object> details = new LinkedHashMap<>();

        List<String> available = providers.stream()
                .filter(FlightSearchProvider::available)
                .map(FlightSearchProvider::code)
                .toList();
        List<String> unavailable = providers.stream()
                .filter(provider -> !provider.available())
                .map(FlightSearchProvider::code)
                .toList();

        details.put("providersAvailable", available);
        details.put("providersUnavailable", unavailable);
        details.put("notificationChannel", notifier.channelName());
        details.put("notificationsConfigured", notifier.configured());
        details.put("searchInProgress", scheduler.running());
        scheduler.lastRunAt().ifPresent(instant -> details.put("lastSearchAt", instant.toString()));
        scheduler.nextRunAt().ifPresent(instant -> details.put("nextSearchAt", instant.toString()));

        Health.Builder builder = available.isEmpty() ? Health.down() : Health.up();
        if (available.isEmpty()) {
            details.put("reason", "no flight search provider is enabled and configured");
        }
        return builder.withDetails(details).build();
    }
}

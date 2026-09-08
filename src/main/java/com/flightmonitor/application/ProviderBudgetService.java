package com.flightmonitor.application;

import com.flightmonitor.domain.port.ProviderBudget;
import com.flightmonitor.infrastructure.persistence.ProviderUsageEntity;
import com.flightmonitor.infrastructure.persistence.ProviderUsageRepository;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Persistent, paced monthly call budget.
 *
 * <p>Pacing is the point. A 100-call monthly allowance spent freely by a six-hourly schedule is
 * gone in under a week, leaving the rest of the month with no data at all. The allowance is
 * therefore released linearly: by day 10 of a 30-day month, a third of it is available. One call is
 * always released so a fresh month is never fully blocked on day one.
 *
 * <p>Counters live in their own transaction so a reservation survives even if the surrounding
 * search later fails — a call that was made must be counted whatever happens next.
 */
@Service
public class ProviderBudgetService implements ProviderBudget {

    private static final Logger log = LoggerFactory.getLogger(ProviderBudgetService.class);
    private static final DateTimeFormatter YEAR_MONTH = DateTimeFormatter.ofPattern("yyyy-MM");

    private final ProviderUsageRepository repository;
    private final Clock clock;

    public ProviderBudgetService(ProviderUsageRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public synchronized boolean tryConsume(String providerCode, int maxPerMonth) {
        if (maxPerMonth <= 0) {
            return true;
        }
        String yearMonth = currentYearMonth();
        ProviderUsageEntity usage = repository
                .findByProviderCodeAndYearMonth(providerCode, yearMonth)
                .orElseGet(() -> newUsage(providerCode, yearMonth));

        int allowed = Math.min(maxPerMonth, pacedAllowance(maxPerMonth));
        if (usage.calls >= allowed) {
            log.info("Budget guard held back {}: {} calls used, {} released so far this month "
                    + "(cap {})", providerCode, usage.calls, allowed, maxPerMonth);
            return false;
        }

        usage.calls += 1;
        usage.updatedAt = OffsetDateTime.now(clock);
        repository.save(usage);
        return true;
    }

    @Override
    @Transactional(readOnly = true)
    public int usedThisMonth(String providerCode) {
        return repository.findByProviderCodeAndYearMonth(providerCode, currentYearMonth())
                .map(usage -> usage.calls)
                .orElse(0);
    }

    @Override
    public int pacedAllowance(int maxPerMonth) {
        LocalDate today = LocalDate.now(clock);
        int daysInMonth = YearMonth.from(today).lengthOfMonth();
        double elapsedFraction = today.getDayOfMonth() / (double) daysInMonth;
        return Math.max(1, (int) Math.ceil(maxPerMonth * elapsedFraction));
    }

    private ProviderUsageEntity newUsage(String providerCode, String yearMonth) {
        ProviderUsageEntity usage = new ProviderUsageEntity();
        usage.providerCode = providerCode;
        usage.yearMonth = yearMonth;
        usage.calls = 0;
        usage.updatedAt = OffsetDateTime.now(clock);
        return usage;
    }

    private String currentYearMonth() {
        return LocalDate.now(clock).format(YEAR_MONTH);
    }
}

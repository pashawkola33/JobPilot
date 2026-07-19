package com.jobpilot.maintenance;

import com.jobpilot.common.ApplicationLifecycleGate;
import com.jobpilot.config.MaintenanceProperties;
import com.jobpilot.llm.budget.LlmBudgetService;
import com.jobpilot.observability.OperationalCounter;
import com.jobpilot.observability.OperationalCounters;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class Stage6MaintenanceCoordinator {
    private static final Logger log = LoggerFactory.getLogger(Stage6MaintenanceCoordinator.class);
    private final AtomicBoolean running = new AtomicBoolean();
    private final MaintenanceProperties properties;
    private final LlmBudgetService budgets;
    private final DocumentMaintenanceService documents;
    private final ApplicationLifecycleGate lifecycle;
    private final OperationalCounters counters;

    public Stage6MaintenanceCoordinator(MaintenanceProperties properties,
                                        LlmBudgetService budgets,
                                        DocumentMaintenanceService documents,
                                        ApplicationLifecycleGate lifecycle,
                                        OperationalCounters counters) {
        this.properties = properties;
        this.budgets = budgets;
        this.documents = documents;
        this.lifecycle = lifecycle;
        this.counters = counters;
    }

    @Scheduled(fixedDelayString = "#{@maintenanceInterval}",
            initialDelayString = "#{@maintenanceInterval}")
    public void scheduledRun() {
        runOnce();
    }

    public MaintenanceRunResult runOnce() {
        if (!properties.enabled() || !lifecycle.acceptingWork()
                || !running.compareAndSet(false, true)) return MaintenanceRunResult.skipped();
        long deadline = System.nanoTime() + properties.maxDurationPerRun().toNanos();
        try {
            int expired = budgets.expireReservations(properties.maxItemsPerRun(),
                    properties.maxDurationPerRun());
            int remainingItems = properties.maxItemsPerRun() - expired;
            Duration remainingDuration = remaining(deadline);
            DocumentMaintenanceResult documentResult = remainingItems > 0 && remainingDuration != null
                    ? documents.run(remainingItems, remainingDuration,
                    properties.orphanGracePeriod()) : DocumentMaintenanceResult.empty();
            counters.add(OperationalCounter.MAINTENANCE_ITEMS_RECOVERED,
                    (long) expired + documentResult.staleResumesRecovered()
                            + documentResult.staleCoverNotesRecovered());
            counters.add(OperationalCounter.MAINTENANCE_ITEMS_REMOVED,
                    (long) documentResult.partialArtifactsRemoved()
                            + documentResult.orphanArtifactsRemoved());
            log.info("Maintenance completed expiredReservations={} staleResumes={} staleCoverNotes={} "
                            + "partialsRemoved={} orphansRemoved={}", expired,
                    documentResult.staleResumesRecovered(),
                    documentResult.staleCoverNotesRecovered(),
                    documentResult.partialArtifactsRemoved(),
                    documentResult.orphanArtifactsRemoved());
            return new MaintenanceRunResult(true, expired, documentResult);
        } catch (RuntimeException failure) {
            log.warn("Maintenance run isolated failure category=coordinator");
            return new MaintenanceRunResult(true, 0, DocumentMaintenanceResult.empty());
        } finally {
            running.set(false);
        }
    }

    private Duration remaining(long deadline) {
        long nanos = deadline - System.nanoTime();
        return nanos <= 0 ? null : Duration.ofNanos(nanos);
    }
}

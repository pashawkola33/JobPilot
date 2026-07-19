package com.jobpilot.llm.application;

import com.jobpilot.jobs.domain.Job;
import com.jobpilot.llm.budget.LlmBudgetReservation;
import com.jobpilot.llm.domain.LlmFailureCategory;
import com.jobpilot.llm.domain.LlmOperationType;
import com.jobpilot.llm.domain.LlmUsageEvent;
import com.jobpilot.llm.domain.LlmUsageStatus;
import com.jobpilot.llm.repository.LlmUsageEventRepository;
import java.math.BigDecimal;
import java.time.Clock;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Component
public class LlmUsageRecorder {
    private final LlmUsageEventRepository events;
    private final Clock clock;

    public LlmUsageRecorder(LlmUsageEventRepository events, Clock clock) {
        this.events = events;
        this.clock = clock;
    }

    public LlmUsageEvent record(LlmBudgetReservation reservation, String requestKey, Job job,
                                LlmOperationType operation, String provider, String model,
                                Long inputTokens, Long outputTokens, boolean estimated,
                                BigDecimal finalCost, LlmUsageStatus status, boolean fallback,
                                LlmFailureCategory failureCategory) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("LLM usage accounting requires an active transaction");
        }
        if (reservation != null) {
            var existing = events.findByReservationIdForUpdate(reservation.getId());
            if (existing.isPresent()) {
                LlmUsageEvent event = existing.get();
                event.reconcile(inputTokens, outputTokens, estimated, finalCost,
                        status, fallback, failureCategory);
                return events.save(event);
            }
        }
        return events.saveAndFlush(new LlmUsageEvent(reservation, requestKey, job, operation,
                provider, model, inputTokens, outputTokens, estimated, finalCost,
                status, fallback, failureCategory, clock.instant()));
    }
}

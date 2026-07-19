package com.jobpilot.llm.budget;

import com.jobpilot.config.JobPilotProperties;
import com.jobpilot.jobs.domain.Job;
import com.jobpilot.llm.domain.LlmOperationType;
import com.jobpilot.llm.repository.LlmBudgetControlRepository;
import com.jobpilot.llm.repository.LlmBudgetReservationRepository;
import com.jobpilot.llm.repository.LlmUsageEventRepository;
import com.jobpilot.llm.domain.LlmFailureCategory;
import com.jobpilot.llm.domain.LlmUsageEvent;
import com.jobpilot.llm.domain.LlmUsageStatus;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.data.domain.PageRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Service
public class LlmBudgetService {
    private static final Logger log = LoggerFactory.getLogger(LlmBudgetService.class);
    private static final Duration MIN_RESERVATION_TTL = Duration.ofMinutes(2);
    private final LlmBudgetControlRepository controls;
    private final LlmBudgetReservationRepository reservations;
    private final LlmCostCalculator costs;
    private final LlmUsageEventRepository usageEvents;
    private final JobPilotProperties.Llm settings;
    private final Clock clock;
    private final TransactionTemplate transactions;

    public LlmBudgetService(LlmBudgetControlRepository controls,
                            LlmBudgetReservationRepository reservations,
                            LlmCostCalculator costs, LlmUsageEventRepository usageEvents,
                            JobPilotProperties properties,
                            Clock clock, PlatformTransactionManager transactionManager) {
        this.controls = controls;
        this.reservations = reservations;
        this.costs = costs;
        this.usageEvents = usageEvents;
        this.settings = properties.llm();
        this.clock = clock;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    public LlmBudgetReservationResult reserve(Job job, LlmOperationType operation,
                                              String requestKey) {
        return transactions.execute(status -> reserveWithinTransaction(job, operation, requestKey));
    }

    public LlmBudgetReservationResult reserveWithinTransaction(
            Job job, LlmOperationType operation, String requestKey) {
        requireTransaction();
        if (!settings.enabled()) throw new IllegalStateException("LLM budget service is disabled");
        Instant now = clock.instant();
        LlmBudgetControl control = controls.findByIdForUpdate(LlmBudgetControl.SINGLETON_ID)
                .orElseThrow(() -> new IllegalStateException("LLM budget control row is missing"));
        abandonExpired(now);
        var duplicate = reservations.findByRequestKey(requestKey);
        BigDecimal maximum = costs.maximumReservationCost();
        if (duplicate.isPresent()) {
            return new LlmBudgetReservationResult(
                    LlmBudgetDecision.DUPLICATE, duplicate.get(), maximum);
        }
        LocalDate day = LocalDate.ofInstant(now, ZoneOffset.UTC);
        if (maximum.compareTo(settings.requestBudgetUsd()) > 0) {
            return rejected(LlmBudgetDecision.REQUEST_LIMIT, maximum);
        }
        BigDecimal daily = reservations.committedForDay(day);
        if (daily.add(maximum).compareTo(settings.dailyBudgetUsd()) > 0) {
            return rejected(LlmBudgetDecision.DAILY_LIMIT, maximum);
        }
        BigDecimal monthly = reservations.committedForMonth(day.withDayOfMonth(1));
        if (monthly.add(maximum).compareTo(settings.monthlyBudgetUsd()) > 0) {
            return rejected(LlmBudgetDecision.MONTHLY_LIMIT, maximum);
        }
        Duration ttl = settings.responseTimeout().plus(MIN_RESERVATION_TTL);
        LlmBudgetReservation reservation = new LlmBudgetReservation(requestKey, job, operation,
                settings.provider(), settings.model(), day, settings.maxInputTokens(),
                settings.maxOutputTokens(), settings.maxRetries() + 1,
                maximum, now, now.plus(ttl));
        reservations.saveAndFlush(reservation);
        control.touch(now);
        return new LlmBudgetReservationResult(LlmBudgetDecision.RESERVED, reservation, maximum);
    }

    public void markProviderStarted(long reservationId) {
        transactions.executeWithoutResult(status -> {
            controls.findByIdForUpdate(LlmBudgetControl.SINGLETON_ID).orElseThrow();
            LlmBudgetReservation reservation = reservations.findByIdForUpdate(reservationId)
                    .orElseThrow();
            reservation.markProviderStarted(clock.instant());
        });
    }

    public LlmBudgetReservation reconcileWithinTransaction(long reservationId,
                                                           BigDecimal finalCost) {
        requireTransaction();
        controls.findByIdForUpdate(LlmBudgetControl.SINGLETON_ID).orElseThrow();
        LlmBudgetReservation reservation = reservations.findByIdForUpdate(reservationId)
                .orElseThrow(() -> new IllegalStateException("LLM budget reservation was not found"));
        reservation.reconcileSuccess(finalCost, clock.instant());
        return reservation;
    }

    public LlmBudgetReservation reconcileFailureWithinTransaction(long reservationId,
                                                                  BigDecimal finalCost) {
        requireTransaction();
        controls.findByIdForUpdate(LlmBudgetControl.SINGLETON_ID).orElseThrow();
        LlmBudgetReservation reservation = reservations.findByIdForUpdate(reservationId)
                .orElseThrow(() -> new IllegalStateException("LLM budget reservation was not found"));
        reservation.reconcileFailure(finalCost, clock.instant());
        return reservation;
    }

    public void abandonWithinTransaction(long reservationId) {
        requireTransaction();
        controls.findByIdForUpdate(LlmBudgetControl.SINGLETON_ID).orElseThrow();
        LlmBudgetReservation reservation = reservations.findByIdForUpdate(reservationId)
                .orElseThrow();
        if (reservation.getStatus() == LlmBudgetReservationStatus.RESERVED) {
            expire(reservation, clock.instant());
        }
    }

    public int expireReservations(int maxItems, Duration maxDuration) {
        if (maxItems < 1 || maxItems > 1_000 || maxDuration == null
                || maxDuration.isZero() || maxDuration.isNegative()
                || maxDuration.compareTo(Duration.ofMinutes(5)) > 0) {
            throw new IllegalArgumentException("LLM reservation cleanup bounds are invalid");
        }
        Instant now = clock.instant();
        long deadline = System.nanoTime() + maxDuration.toNanos();
        var ids = reservations.findExpiredIds(LlmBudgetReservationStatus.RESERVED, now,
                PageRequest.of(0, maxItems));
        int expired = 0;
        for (Long id : ids) {
            if (System.nanoTime() >= deadline) break;
            try {
                Boolean changed = transactions.execute(status -> {
                    controls.findByIdForUpdate(LlmBudgetControl.SINGLETON_ID).orElseThrow();
                    LlmBudgetReservation reservation = reservations.findByIdForUpdate(id).orElse(null);
                    if (reservation == null
                            || reservation.getStatus() != LlmBudgetReservationStatus.RESERVED
                            || reservation.getExpiresAt().isAfter(now)) return false;
                    expire(reservation, now);
                    return true;
                });
                if (Boolean.TRUE.equals(changed)) expired++;
            } catch (RuntimeException failure) {
                log.warn("LLM reservation maintenance isolated failure reservationId={} category=persistence",
                        id);
            }
        }
        return expired;
    }

    private void abandonExpired(Instant now) {
        reservations.findFirst100ByStatusAndExpiresAtLessThanEqualOrderByExpiresAtAsc(
                LlmBudgetReservationStatus.RESERVED, now).forEach(value -> {
            expire(value, now);
        });
    }

    private void expire(LlmBudgetReservation value, Instant now) {
        if (value.getProviderStartedAt() == null) {
            value.releaseExpired(now);
            return;
        }
        value.abandon(now);
        if (!usageEvents.existsByReservationId(value.getId())) {
            usageEvents.save(new LlmUsageEvent(value, value.getRequestKey(), value.getJob(),
                    value.getOperationType(), value.getProvider(), value.getModel(),
                    multiply(value.getMaxInputTokens(), value.getMaxAttempts()),
                    multiply(value.getMaxOutputTokens(), value.getMaxAttempts()), true,
                    value.getReservedCostUsd(), LlmUsageStatus.FAILED, true,
                    LlmFailureCategory.TIMEOUT, now));
        }
    }

    private long multiply(long tokens, int attempts) {
        return Math.multiplyExact(tokens, (long) attempts);
    }

    private LlmBudgetReservationResult rejected(LlmBudgetDecision decision, BigDecimal maximum) {
        return new LlmBudgetReservationResult(decision, null, maximum);
    }

    private void requireTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("LLM budget mutation requires an active transaction");
        }
    }
}

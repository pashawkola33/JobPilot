package com.jobpilot.llm.budget;

import com.jobpilot.jobs.domain.Job;
import com.jobpilot.llm.domain.LlmOperationType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(name = "llm_budget_reservations")
public class LlmBudgetReservation {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false, unique = true, length = 64)
    private String requestKey;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "job_id", nullable = false)
    private Job job;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 60)
    private LlmOperationType operationType;
    @Column(nullable = false, length = 120)
    private String provider;
    @Column(nullable = false, length = 200)
    private String model;
    @Column(nullable = false)
    private LocalDate budgetDay;
    @Column(nullable = false)
    private LocalDate budgetMonth;
    @Column(nullable = false)
    private long maxInputTokens;
    @Column(nullable = false)
    private long maxOutputTokens;
    @Column(nullable = false)
    private int maxAttempts;
    @Column(nullable = false, precision = 18, scale = 8)
    private BigDecimal reservedCostUsd;
    @Column(precision = 18, scale = 8)
    private BigDecimal finalCostUsd;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private LlmBudgetReservationStatus status;
    private Instant providerStartedAt;
    @Column(nullable = false)
    private Instant createdAt;
    @Column(nullable = false)
    private Instant expiresAt;
    private Instant reconciledAt;

    protected LlmBudgetReservation() {
    }

    public LlmBudgetReservation(String requestKey, Job job, LlmOperationType operationType,
                                String provider, String model, LocalDate budgetDay,
                                long maxInputTokens, long maxOutputTokens,
                                int maxAttempts, BigDecimal reservedCostUsd,
                                Instant now, Instant expiresAt) {
        this.requestKey = requestKey;
        this.job = job;
        this.operationType = operationType;
        this.provider = provider;
        this.model = model;
        this.budgetDay = budgetDay;
        this.budgetMonth = budgetDay.withDayOfMonth(1);
        this.maxInputTokens = maxInputTokens;
        this.maxOutputTokens = maxOutputTokens;
        this.maxAttempts = maxAttempts;
        this.reservedCostUsd = reservedCostUsd;
        this.status = LlmBudgetReservationStatus.RESERVED;
        this.createdAt = now;
        this.expiresAt = expiresAt;
    }

    public void markProviderStarted(Instant now) {
        if (status == LlmBudgetReservationStatus.RESERVED && providerStartedAt == null) {
            providerStartedAt = now;
        }
    }

    public void reconcileSuccess(BigDecimal cost, Instant now) {
        requireNonNegative(cost);
        if (status == LlmBudgetReservationStatus.RESERVED) {
            finalCostUsd = cost;
            status = cost.signum() == 0
                    ? LlmBudgetReservationStatus.RELEASED : LlmBudgetReservationStatus.SETTLED;
            reconciledAt = now;
            return;
        }
        if (status == LlmBudgetReservationStatus.ABANDONED
                || status == LlmBudgetReservationStatus.LATE_SETTLED
                || status == LlmBudgetReservationStatus.RELEASED && providerStartedAt != null) {
            finalCostUsd = maximum(finalCostUsd, cost);
            status = LlmBudgetReservationStatus.LATE_SETTLED;
            reconciledAt = now;
            return;
        }
        if (status == LlmBudgetReservationStatus.SETTLED) {
            finalCostUsd = maximum(finalCostUsd, cost);
            reconciledAt = now;
        }
    }

    public void reconcileFailure(BigDecimal cost, Instant now) {
        requireNonNegative(cost);
        if (status == LlmBudgetReservationStatus.RESERVED) {
            finalCostUsd = cost;
            status = cost.signum() == 0
                    ? LlmBudgetReservationStatus.RELEASED : LlmBudgetReservationStatus.SETTLED;
            reconciledAt = now;
        }
    }

    public void abandon(Instant now) {
        requireReserved();
        finalCostUsd = reservedCostUsd;
        status = LlmBudgetReservationStatus.ABANDONED;
        reconciledAt = now;
    }

    public void releaseExpired(Instant now) {
        requireReserved();
        finalCostUsd = BigDecimal.ZERO.setScale(LlmCostCalculator.COST_SCALE);
        status = LlmBudgetReservationStatus.RELEASED;
        reconciledAt = now;
    }

    private void requireNonNegative(BigDecimal cost) {
        if (cost == null || cost.signum() < 0) {
            throw new IllegalArgumentException("LLM final cost cannot be negative");
        }
    }

    private BigDecimal maximum(BigDecimal first, BigDecimal second) {
        if (first == null) return second;
        return first.max(second);
    }

    private void requireReserved() {
        if (status != LlmBudgetReservationStatus.RESERVED) {
            throw new IllegalStateException("LLM budget reservation is already reconciled");
        }
    }

    public Long getId() { return id; }
    public String getRequestKey() { return requestKey; }
    public Job getJob() { return job; }
    public LlmOperationType getOperationType() { return operationType; }
    public String getProvider() { return provider; }
    public String getModel() { return model; }
    public LocalDate getBudgetDay() { return budgetDay; }
    public LocalDate getBudgetMonth() { return budgetMonth; }
    public long getMaxInputTokens() { return maxInputTokens; }
    public long getMaxOutputTokens() { return maxOutputTokens; }
    public int getMaxAttempts() { return maxAttempts; }
    public BigDecimal getReservedCostUsd() { return reservedCostUsd; }
    public BigDecimal getFinalCostUsd() { return finalCostUsd; }
    public LlmBudgetReservationStatus getStatus() { return status; }
    public Instant getProviderStartedAt() { return providerStartedAt; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getExpiresAt() { return expiresAt; }
    public Instant getReconciledAt() { return reconciledAt; }
}

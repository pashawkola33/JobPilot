package com.jobpilot.llm.domain;

import com.jobpilot.jobs.domain.Job;
import com.jobpilot.llm.budget.LlmBudgetReservation;
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

@Entity
@Table(name = "llm_usage_events")
public class LlmUsageEvent {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reservation_id")
    private LlmBudgetReservation reservation;
    @Column(length = 64)
    private String requestKey;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "job_id")
    private Job job;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 60)
    private LlmOperationType operationType;
    @Column(nullable = false, length = 120)
    private String provider;
    @Column(nullable = false, length = 200)
    private String model;
    private Long inputTokens;
    private Long outputTokens;
    @Column(nullable = false)
    private boolean tokenCountEstimated;
    @Column(nullable = false, precision = 18, scale = 8)
    private BigDecimal estimatedCostUsd;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private LlmUsageStatus status;
    @Column(nullable = false)
    private boolean fallbackUsed;
    @Enumerated(EnumType.STRING)
    @Column(length = 80)
    private LlmFailureCategory failureCategory;
    @Column(nullable = false)
    private Instant createdAt;

    protected LlmUsageEvent() {
    }

    public LlmUsageEvent(Job job, LlmOperationType operationType, String provider, String model,
                         Long inputTokens, Long outputTokens, boolean tokenCountEstimated,
                         BigDecimal estimatedCostUsd, LlmUsageStatus status, boolean fallbackUsed,
                         LlmFailureCategory failureCategory, Instant createdAt) {
        this.job = job;
        this.operationType = operationType;
        this.provider = provider;
        this.model = model;
        this.inputTokens = inputTokens;
        this.outputTokens = outputTokens;
        this.tokenCountEstimated = tokenCountEstimated;
        this.estimatedCostUsd = estimatedCostUsd;
        this.status = status;
        this.fallbackUsed = fallbackUsed;
        this.failureCategory = failureCategory;
        this.createdAt = createdAt;
    }

    public LlmUsageEvent(LlmBudgetReservation reservation, String requestKey, Job job,
                         LlmOperationType operationType, String provider, String model,
                         Long inputTokens, Long outputTokens, boolean tokenCountEstimated,
                         BigDecimal finalCostUsd, LlmUsageStatus status, boolean fallbackUsed,
                         LlmFailureCategory failureCategory, Instant createdAt) {
        this(job, operationType, provider, model, inputTokens, outputTokens,
                tokenCountEstimated, finalCostUsd, status, fallbackUsed,
                failureCategory, createdAt);
        this.reservation = reservation;
        this.requestKey = requestKey;
    }

    public void reconcile(Long newInputTokens, Long newOutputTokens, boolean estimated,
                          BigDecimal newCost, LlmUsageStatus newStatus, boolean fallback,
                          LlmFailureCategory newFailureCategory) {
        inputTokens = maximum(inputTokens, newInputTokens);
        outputTokens = maximum(outputTokens, newOutputTokens);
        tokenCountEstimated = tokenCountEstimated || estimated
                || estimatedCostUsd.compareTo(newCost) > 0;
        estimatedCostUsd = estimatedCostUsd.max(newCost);
        status = newStatus;
        fallbackUsed = fallback;
        failureCategory = newFailureCategory;
    }

    private Long maximum(Long first, Long second) {
        if (first == null) return second;
        if (second == null) return first;
        return Math.max(first, second);
    }

    public Long getId() { return id; }
    public LlmBudgetReservation getReservation() { return reservation; }
    public String getRequestKey() { return requestKey; }
    public Job getJob() { return job; }
    public LlmOperationType getOperationType() { return operationType; }
    public String getProvider() { return provider; }
    public String getModel() { return model; }
    public Long getInputTokens() { return inputTokens; }
    public Long getOutputTokens() { return outputTokens; }
    public boolean isTokenCountEstimated() { return tokenCountEstimated; }
    public BigDecimal getEstimatedCostUsd() { return estimatedCostUsd; }
    public LlmUsageStatus getStatus() { return status; }
    public boolean isFallbackUsed() { return fallbackUsed; }
    public LlmFailureCategory getFailureCategory() { return failureCategory; }
    public Instant getCreatedAt() { return createdAt; }
}

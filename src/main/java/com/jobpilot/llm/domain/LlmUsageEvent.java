package com.jobpilot.llm.domain;

import com.jobpilot.jobs.domain.Job;
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
    @Column(nullable = false, precision = 12, scale = 6)
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

    public Long getId() { return id; }
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

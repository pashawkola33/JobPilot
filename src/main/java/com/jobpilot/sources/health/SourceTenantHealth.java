package com.jobpilot.sources.health;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;

/**
 * Current roll-up for one provider + tenant. A tenant is never disabled or removed
 * here; this is diagnostic state only.
 */
@Entity
@Table(name = "source_tenant_health",
        uniqueConstraints = @UniqueConstraint(name = "source_tenant_health_uk",
                columnNames = {"provider", "tenant"}))
public class SourceTenantHealth {
    /** Repeated-failure threshold surfaced as `degraded` in the read-only API. */
    public static final int DEGRADED_THRESHOLD = 3;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 50)
    private String provider;
    @Column(nullable = false, length = 100)
    private String tenant;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TenantAttemptStatus lastAttemptStatus;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private TenantFailureCategory lastFailureCategory;
    private Integer lastHttpStatus;
    @Column(nullable = false)
    private int lastFetchedCount;
    @Column(nullable = false)
    private long lastDurationMs;
    @Column(nullable = false)
    private int consecutiveFailures;
    @Column(nullable = false)
    private long totalAttempts;
    @Column(nullable = false)
    private long totalSuccesses;
    @Column(nullable = false)
    private long totalFailures;
    private Instant lastAttemptAt;
    private Instant lastSuccessAt;
    private Instant lastFailureAt;
    @Column(length = 120)
    private String lastErrorType;
    @Column(length = 500)
    private String lastErrorMessage;
    @Column(nullable = false)
    private Instant createdAt;
    @Column(nullable = false)
    private Instant updatedAt;

    protected SourceTenantHealth() {
    }

    public SourceTenantHealth(String provider, String tenant, Instant now) {
        this.provider = SafeErrorText.token(provider);
        this.tenant = SafeErrorText.token(tenant);
        this.lastAttemptStatus = TenantAttemptStatus.FAILURE;
        this.lastFailureCategory = TenantFailureCategory.NONE;
        this.createdAt = now;
        this.updatedAt = now;
    }

    /**
     * Applies one completed attempt. A success clears the previous failure detail;
     * a failure preserves {@code lastSuccessAt} so recovery history is not lost.
     */
    public void apply(TenantAttemptStatus status, TenantFailure failure, int fetchedCount,
                      long durationMs, Instant finishedAt) {
        TenantFailure safe = failure == null ? TenantFailure.none() : failure;
        this.lastAttemptStatus = status;
        this.lastFetchedCount = Math.max(0, fetchedCount);
        this.lastDurationMs = Math.max(0L, durationMs);
        this.lastAttemptAt = finishedAt;
        this.totalAttempts++;
        if (status.successful()) {
            this.lastSuccessAt = finishedAt;
            this.totalSuccesses++;
            this.consecutiveFailures = 0;
            this.lastFailureCategory = TenantFailureCategory.NONE;
            this.lastHttpStatus = null;
            this.lastErrorType = null;
            this.lastErrorMessage = null;
        } else {
            this.lastFailureAt = finishedAt;
            this.totalFailures++;
            this.consecutiveFailures++;
            this.lastFailureCategory = safe.category() == TenantFailureCategory.NONE
                    ? TenantFailureCategory.UNKNOWN_ERROR : safe.category();
            this.lastHttpStatus = safe.httpStatus();
            this.lastErrorType = safe.errorType();
            this.lastErrorMessage = safe.errorMessage();
        }
        this.updatedAt = finishedAt;
    }

    public boolean healthy() {
        return lastAttemptStatus != null && lastAttemptStatus.successful();
    }

    public boolean degraded() {
        return consecutiveFailures >= DEGRADED_THRESHOLD;
    }

    public Long getId() { return id; }
    public String getProvider() { return provider; }
    public String getTenant() { return tenant; }
    public TenantAttemptStatus getLastAttemptStatus() { return lastAttemptStatus; }
    public TenantFailureCategory getLastFailureCategory() { return lastFailureCategory; }
    public Integer getLastHttpStatus() { return lastHttpStatus; }
    public int getLastFetchedCount() { return lastFetchedCount; }
    public long getLastDurationMs() { return lastDurationMs; }
    public int getConsecutiveFailures() { return consecutiveFailures; }
    public long getTotalAttempts() { return totalAttempts; }
    public long getTotalSuccesses() { return totalSuccesses; }
    public long getTotalFailures() { return totalFailures; }
    public Instant getLastAttemptAt() { return lastAttemptAt; }
    public Instant getLastSuccessAt() { return lastSuccessAt; }
    public Instant getLastFailureAt() { return lastFailureAt; }
    public String getLastErrorType() { return lastErrorType; }
    public String getLastErrorMessage() { return lastErrorMessage; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}

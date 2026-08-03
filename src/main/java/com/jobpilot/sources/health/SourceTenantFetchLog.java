package com.jobpilot.sources.health;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** Immutable history row: exactly one per tenant fetch attempt. */
@Entity
@Table(name = "source_tenant_fetch_logs")
public class SourceTenantFetchLog {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private UUID ingestionRunId;
    @Column(nullable = false, length = 50)
    private String provider;
    @Column(nullable = false, length = 100)
    private String tenant;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TenantAttemptStatus attemptStatus;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private TenantFailureCategory failureCategory;
    private Integer httpStatus;
    @Column(nullable = false)
    private int fetchedCount;
    @Column(nullable = false)
    private long durationMs;
    @Column(length = 120)
    private String errorType;
    @Column(length = 500)
    private String errorMessage;
    @Column(nullable = false)
    private Instant startedAt;
    @Column(nullable = false)
    private Instant finishedAt;

    protected SourceTenantFetchLog() {
    }

    public SourceTenantFetchLog(UUID ingestionRunId, String provider, String tenant,
                               TenantAttemptStatus attemptStatus, TenantFailure failure,
                               int fetchedCount, long durationMs,
                               Instant startedAt, Instant finishedAt) {
        this.ingestionRunId = ingestionRunId;
        this.provider = SafeErrorText.token(provider);
        this.tenant = SafeErrorText.token(tenant);
        this.attemptStatus = attemptStatus;
        TenantFailure safe = failure == null ? TenantFailure.none() : failure;
        this.failureCategory = safe.category();
        this.httpStatus = safe.httpStatus();
        this.errorType = attemptStatus == TenantAttemptStatus.FAILURE ? safe.errorType() : null;
        this.errorMessage = attemptStatus == TenantAttemptStatus.FAILURE ? safe.errorMessage() : null;
        this.fetchedCount = Math.max(0, fetchedCount);
        this.durationMs = Math.max(0L, durationMs);
        this.startedAt = startedAt;
        this.finishedAt = finishedAt;
    }

    public Long getId() { return id; }
    public UUID getIngestionRunId() { return ingestionRunId; }
    public String getProvider() { return provider; }
    public String getTenant() { return tenant; }
    public TenantAttemptStatus getAttemptStatus() { return attemptStatus; }
    public TenantFailureCategory getFailureCategory() { return failureCategory; }
    public Integer getHttpStatus() { return httpStatus; }
    public int getFetchedCount() { return fetchedCount; }
    public long getDurationMs() { return durationMs; }
    public String getErrorType() { return errorType; }
    public String getErrorMessage() { return errorMessage; }
    public Instant getStartedAt() { return startedAt; }
    public Instant getFinishedAt() { return finishedAt; }
}

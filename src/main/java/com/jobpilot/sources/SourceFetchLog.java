package com.jobpilot.sources;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "source_fetch_logs")
public class SourceFetchLog {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    /** Correlates this aggregate row with one ingestion run. Null for pre-V10 rows. */
    private UUID ingestionRunId;
    private String sourceName;
    private Instant startedAt;
    private Instant finishedAt;
    private String status;
    private int fetchedCount;
    private int savedCount;
    private String errorSummary;

    protected SourceFetchLog() {
    }

    public SourceFetchLog(String sourceName, Instant startedAt) {
        this(sourceName, startedAt, null);
    }

    public SourceFetchLog(String sourceName, Instant startedAt, UUID ingestionRunId) {
        this.sourceName = sourceName;
        this.startedAt = startedAt;
        this.ingestionRunId = ingestionRunId;
        this.status = "RUNNING";
    }

    public Long getId() {
        return id;
    }

    public UUID getIngestionRunId() {
        return ingestionRunId;
    }

    public String getSourceName() {
        return sourceName;
    }

    public String getStatus() {
        return status;
    }

    public Instant getFinishedAt() {
        return finishedAt;
    }

    public int getFetchedCount() {
        return fetchedCount;
    }

    public int getSavedCount() {
        return savedCount;
    }

    public String getErrorSummary() {
        return errorSummary;
    }
}

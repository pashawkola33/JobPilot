package com.jobpilot.sources;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "source_fetch_logs")
public class SourceFetchLog {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
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
        this.sourceName = sourceName;
        this.startedAt = startedAt;
        this.status = "RUNNING";
    }

    public void succeed(int fetched, int saved, Instant now) {
        status = "SUCCESS";
        fetchedCount = fetched;
        savedCount = saved;
        finishedAt = now;
    }

    public void fail(Exception error, Instant now) {
        status = "FAILED";
        finishedAt = now;
        String message = error.getClass().getSimpleName() + ": " + String.valueOf(error.getMessage());
        errorSummary = message.substring(0, Math.min(message.length(), 500));
    }
}

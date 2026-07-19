package com.jobpilot.telegram.polling;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;

@Entity
@Table(name = "telegram_bot_state")
public class TelegramBotState {
    public static final String LONG_POLLING_KEY = "long-polling";

    @Id
    @Column(name = "state_key", length = 80)
    private String key;
    @Version
    @Column(nullable = false)
    private long version;
    private Long lastProcessedUpdateId;
    private Long failedUpdateId;
    @Column(nullable = false)
    private int failedAttempts;
    @Column(nullable = false)
    private Instant updatedAt;

    protected TelegramBotState() {
    }

    public TelegramBotState(Long lastProcessedUpdateId, Instant updatedAt) {
        this.key = LONG_POLLING_KEY;
        this.lastProcessedUpdateId = lastProcessedUpdateId;
        this.updatedAt = updatedAt;
    }

    public void markProcessed(long updateId, Instant now) {
        if (lastProcessedUpdateId == null || updateId > lastProcessedUpdateId) {
            lastProcessedUpdateId = updateId;
        }
        failedUpdateId = null;
        failedAttempts = 0;
        updatedAt = now;
    }

    public int recordFailure(long updateId, Instant now) {
        if (failedUpdateId != null && failedUpdateId == updateId) {
            failedAttempts++;
        } else {
            failedUpdateId = updateId;
            failedAttempts = 1;
        }
        updatedAt = now;
        return failedAttempts;
    }

    public String getKey() { return key; }
    public long getVersion() { return version; }
    public Long getLastProcessedUpdateId() { return lastProcessedUpdateId; }
    public Long getFailedUpdateId() { return failedUpdateId; }
    public int getFailedAttempts() { return failedAttempts; }
    public Instant getUpdatedAt() { return updatedAt; }
}

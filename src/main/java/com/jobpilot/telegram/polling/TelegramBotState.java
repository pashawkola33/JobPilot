package com.jobpilot.telegram.polling;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "telegram_bot_state")
public class TelegramBotState {
    public static final String LONG_POLLING_KEY = "long-polling";

    @Id
    @Column(name = "state_key", length = 80)
    private String key;
    private Long lastProcessedUpdateId;
    @Column(nullable = false)
    private Instant updatedAt;

    protected TelegramBotState() {
    }

    public TelegramBotState(Long lastProcessedUpdateId, Instant updatedAt) {
        this.key = LONG_POLLING_KEY;
        this.lastProcessedUpdateId = lastProcessedUpdateId;
        this.updatedAt = updatedAt;
    }

    public String getKey() { return key; }
    public Long getLastProcessedUpdateId() { return lastProcessedUpdateId; }
    public Instant getUpdatedAt() { return updatedAt; }
}

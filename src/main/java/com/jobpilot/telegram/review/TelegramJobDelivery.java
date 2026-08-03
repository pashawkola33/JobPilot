package com.jobpilot.telegram.review;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** Written only after the Bot API confirms a send, so a failed send stays retryable. */
@Entity
@Table(name = "telegram_job_delivery")
public class TelegramJobDelivery {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long chatId;

    @Column(nullable = false)
    private Long jobId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private DeliveryType deliveryType;

    @Column(nullable = false)
    private Instant deliveredAt;

    @Column(nullable = false)
    private Instant createdAt;

    protected TelegramJobDelivery() {
    }

    public TelegramJobDelivery(long chatId, long jobId, DeliveryType deliveryType, Instant now) {
        this.chatId = chatId;
        this.jobId = jobId;
        this.deliveryType = deliveryType;
        this.deliveredAt = now;
        this.createdAt = now;
    }

    public Long getId() { return id; }
    public Long getChatId() { return chatId; }
    public Long getJobId() { return jobId; }
    public DeliveryType getDeliveryType() { return deliveryType; }
    public Instant getDeliveredAt() { return deliveredAt; }
    public Instant getCreatedAt() { return createdAt; }
}

package com.jobpilot.applications.domain;

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
import java.time.Instant;
import org.hibernate.annotations.Immutable;

@Entity
@Immutable
@Table(name = "application_status_history")
public class ApplicationStatusHistory {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "application_id", nullable = false, updatable = false)
    private ApplicationRecord application;
    @Enumerated(EnumType.STRING)
    @Column(length = 40, updatable = false)
    private ApplicationStatus previousStatus;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40, updatable = false)
    private ApplicationStatus newStatus;
    @Column(nullable = false, updatable = false)
    private Instant changedAt;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40, updatable = false)
    private ApplicationStatusChangeSource source;

    protected ApplicationStatusHistory() {
    }

    public ApplicationStatusHistory(ApplicationRecord application, ApplicationStatus previousStatus,
                                    ApplicationStatus newStatus, Instant changedAt,
                                    ApplicationStatusChangeSource source) {
        this.application = application;
        this.previousStatus = previousStatus;
        this.newStatus = newStatus;
        this.changedAt = changedAt;
        this.source = source;
    }

    public Long getId() { return id; }
    public ApplicationRecord getApplication() { return application; }
    public ApplicationStatus getPreviousStatus() { return previousStatus; }
    public ApplicationStatus getNewStatus() { return newStatus; }
    public Instant getChangedAt() { return changedAt; }
    public ApplicationStatusChangeSource getSource() { return source; }
}

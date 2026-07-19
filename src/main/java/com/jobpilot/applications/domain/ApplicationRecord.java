package com.jobpilot.applications.domain;

import com.jobpilot.jobs.domain.Job;
import com.jobpilot.resume.domain.CoverNote;
import com.jobpilot.resume.domain.ResumeVersion;
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
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(name = "applications")
public class ApplicationRecord {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Version
    @Column(nullable = false)
    private long version;
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "job_id", nullable = false, unique = true)
    private Job job;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private ApplicationStatus status;
    private Instant applicationDate;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "resume_version_id")
    private ResumeVersion resumeVersion;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "cover_note_id")
    private CoverNote coverNote;
    @Column(columnDefinition = "text")
    private String notes;
    private LocalDate nextFollowUpDate;
    private Instant interviewDate;
    @Column(columnDefinition = "text")
    private String rejectionReason;
    @Column(nullable = false)
    private Instant createdAt;
    @Column(nullable = false)
    private Instant updatedAt;

    protected ApplicationRecord() {
    }

    public static ApplicationRecord create(Job job, ApplicationStatus status, Instant now) {
        if (status != ApplicationStatus.SAVED && status != ApplicationStatus.APPLIED) {
            throw new IllegalArgumentException("An application can only start as SAVED or APPLIED");
        }
        return new ApplicationRecord(job, status,
                status == ApplicationStatus.APPLIED ? now : null,
                null, null, null, null, null, null, now, now);
    }

    public void transitionTo(ApplicationStatus next, Instant interviewAt,
                             String rejectedBecause, Instant now) {
        if (next == ApplicationStatus.APPLIED && applicationDate == null) {
            applicationDate = now;
        }
        if (next == ApplicationStatus.INTERVIEW) {
            if (interviewAt == null) throw new IllegalArgumentException("Interview date is required");
            interviewDate = interviewAt;
        }
        if (next == ApplicationStatus.REJECTED) {
            rejectionReason = rejectedBecause;
        }
        status = next;
        updatedAt = now;
    }

    public void changeFollowUpDate(LocalDate date, Instant now) {
        nextFollowUpDate = date;
        updatedAt = now;
    }

    public void changeNotes(String normalizedNotes, Instant now) {
        notes = normalizedNotes;
        updatedAt = now;
    }

    public ApplicationRecord(Job job, ApplicationStatus status, Instant applicationDate,
                             ResumeVersion resumeVersion, CoverNote coverNote, String notes,
                             LocalDate nextFollowUpDate, Instant interviewDate,
                             String rejectionReason, Instant createdAt, Instant updatedAt) {
        this.job = job;
        this.status = status;
        this.applicationDate = applicationDate;
        this.resumeVersion = resumeVersion;
        this.coverNote = coverNote;
        this.notes = notes;
        this.nextFollowUpDate = nextFollowUpDate;
        this.interviewDate = interviewDate;
        this.rejectionReason = rejectionReason;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public Long getId() { return id; }
    public long getVersion() { return version; }
    public Job getJob() { return job; }
    public ApplicationStatus getStatus() { return status; }
    public Instant getApplicationDate() { return applicationDate; }
    public ResumeVersion getResumeVersion() { return resumeVersion; }
    public CoverNote getCoverNote() { return coverNote; }
    public String getNotes() { return notes; }
    public LocalDate getNextFollowUpDate() { return nextFollowUpDate; }
    public Instant getInterviewDate() { return interviewDate; }
    public String getRejectionReason() { return rejectionReason; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}

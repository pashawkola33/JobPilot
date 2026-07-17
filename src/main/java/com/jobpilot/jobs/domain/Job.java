package com.jobpilot.jobs.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "jobs")
public class Job {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String source;
    private String externalId;
    private String canonicalUrl;
    private String title;
    private String company;
    private String location;
    @Enumerated(EnumType.STRING)
    private RemoteType remoteType;
    private String employmentType;
    @Column(columnDefinition = "text")
    private String description;
    @Column(columnDefinition = "text")
    private String responsibilities;
    @Column(columnDefinition = "text")
    private String requiredSkills;
    @Column(columnDefinition = "text")
    private String preferredSkills;
    private Double requiredExperienceYears;
    @Column(columnDefinition = "text")
    private String educationRequirement;
    @Column(columnDefinition = "text")
    private String studentYearRequirement;
    @Column(columnDefinition = "text")
    private String languageRequirements;
    private String salaryText;
    private Instant publishedAt;
    private Instant deadline;
    private Instant fetchedAt;
    private Instant firstSeenAt;
    private Instant lastSeenAt;
    @Enumerated(EnumType.STRING)
    private JobStatus status;
    private String rawPayloadHash;
    private String descriptionHash;
    private String normalizedFingerprint;

    protected Job() {
    }

    public Job(String source, String externalId, String canonicalUrl, String title, String company,
               String location, RemoteType remoteType, String employmentType, String description,
               Instant publishedAt, Instant deadline, String rawPayloadHash,
               String descriptionHash, String normalizedFingerprint, Instant now) {
        this.source = source;
        this.externalId = externalId;
        this.canonicalUrl = canonicalUrl;
        this.title = title;
        this.company = company;
        this.location = location;
        this.remoteType = remoteType;
        this.employmentType = employmentType;
        this.description = description;
        this.publishedAt = publishedAt;
        this.deadline = deadline;
        this.rawPayloadHash = rawPayloadHash;
        this.descriptionHash = descriptionHash;
        this.normalizedFingerprint = normalizedFingerprint;
        this.fetchedAt = now;
        this.firstSeenAt = now;
        this.lastSeenAt = now;
        this.status = JobStatus.NEW;
    }

    public void applyRequirements(ExtractedRequirements r, String requiredSkills, String languages) {
        this.requiredSkills = requiredSkills;
        this.requiredExperienceYears = r.requiredExperienceYears();
        this.educationRequirement = r.requiredEducation();
        this.studentYearRequirement = r.finalYearMandatory() ? "Final-year mandatory" : null;
        this.languageRequirements = languages;
        this.salaryText = r.salary();
        if (r.applicationDeadline() != null) {
            this.deadline = r.applicationDeadline();
        }
    }

    public void seenAgain(Instant now) {
        fetchedAt = now;
        lastSeenAt = now;
    }

    public void changeStatus(JobStatus next) {
        status = next;
    }

    public Long getId() { return id; }
    public String getSource() { return source; }
    public String getExternalId() { return externalId; }
    public String getCanonicalUrl() { return canonicalUrl; }
    public String getTitle() { return title; }
    public String getCompany() { return company; }
    public String getLocation() { return location; }
    public RemoteType getRemoteType() { return remoteType; }
    public String getEmploymentType() { return employmentType; }
    public String getDescription() { return description; }
    public Double getRequiredExperienceYears() { return requiredExperienceYears; }
    public Instant getPublishedAt() { return publishedAt; }
    public Instant getDeadline() { return deadline; }
    public Instant getFirstSeenAt() { return firstSeenAt; }
    public Instant getLastSeenAt() { return lastSeenAt; }
    public JobStatus getStatus() { return status; }
    public String getRawPayloadHash() { return rawPayloadHash; }
    public String getDescriptionHash() { return descriptionHash; }
    public String getNormalizedFingerprint() { return normalizedFingerprint; }
}

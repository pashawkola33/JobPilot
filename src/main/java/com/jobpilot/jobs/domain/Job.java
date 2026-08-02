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
    @Column(nullable = false, length = 300)
    private String providerTenant;
    private String externalId;
    private String canonicalUrl;
    private String title;
    private String company;
    private String location;
    @Enumerated(EnumType.STRING)
    private RemoteType remoteType;
    @Enumerated(EnumType.STRING)
    private LocationEligibility locationEligibility;
    @Enumerated(EnumType.STRING)
    private RemoteScope remoteScope;
    private String normalizedCity;
    private String normalizedCountry;
    private boolean eligibleFromRomania;
    private String eligibilityReason;
    @Column(columnDefinition = "text")
    private String detectedLocationRestrictions;
    private String requiredTimezone;
    private String requiredWorkAuthorization;
    @Enumerated(EnumType.STRING)
    private SeniorityLevel seniorityLevel;
    private Double experienceMinimumYears;
    private Double experienceMaximumYears;
    private boolean experienceMandatory;
    @Column(columnDefinition = "text")
    private String experienceRawText;
    @Enumerated(EnumType.STRING)
    private EarlyCareerEligibility earlyCareerEligibility;
    private String earlyCareerEligibilityReason;
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
        this(source, company, externalId, canonicalUrl, title, company, location, remoteType,
                employmentType, description, publishedAt, deadline, rawPayloadHash,
                descriptionHash, normalizedFingerprint, now, null, null);
    }

    public Job(String source, String externalId, String canonicalUrl, String title, String company,
               String location, RemoteType remoteType, String employmentType, String description,
               Instant publishedAt, Instant deadline, String rawPayloadHash,
               String descriptionHash, String normalizedFingerprint, Instant now,
               LocationEligibilityDecision eligibility) {
        this(source, company, externalId, canonicalUrl, title, company, location, remoteType,
                employmentType, description, publishedAt, deadline, rawPayloadHash,
                descriptionHash, normalizedFingerprint, now, eligibility, null);
    }

    public Job(String source, String externalId, String canonicalUrl, String title, String company,
               String location, RemoteType remoteType, String employmentType, String description,
               Instant publishedAt, Instant deadline, String rawPayloadHash,
               String descriptionHash, String normalizedFingerprint, Instant now,
               LocationEligibilityDecision eligibility, EarlyCareerDecision earlyCareer) {
        this(source, company, externalId, canonicalUrl, title, company, location, remoteType,
                employmentType, description, publishedAt, deadline, rawPayloadHash,
                descriptionHash, normalizedFingerprint, now, eligibility, earlyCareer);
    }

    public Job(String source, String providerTenant, String externalId, String canonicalUrl,
               String title, String company, String location, RemoteType remoteType,
               String employmentType, String description, Instant publishedAt, Instant deadline,
               String rawPayloadHash, String descriptionHash, String normalizedFingerprint,
               Instant now, LocationEligibilityDecision eligibility, EarlyCareerDecision earlyCareer) {
        this.source = source;
        this.providerTenant = providerTenant == null || providerTenant.isBlank()
                ? "legacy" : providerTenant.strip();
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
        applyEligibility(eligibility);
        applyEarlyCareer(earlyCareer);
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

    /** Copies the changed content of a freshly fetched duplicate onto this stored job. */
    public void refreshContent(Job updated, Instant now) {
        this.title = updated.title;
        this.location = updated.location;
        this.remoteType = updated.remoteType;
        this.employmentType = updated.employmentType;
        this.description = updated.description;
        this.deadline = updated.deadline;
        if (updated.publishedAt != null) {
            this.publishedAt = updated.publishedAt;
        }
        this.rawPayloadHash = updated.rawPayloadHash;
        this.descriptionHash = updated.descriptionHash;
        this.normalizedFingerprint = updated.normalizedFingerprint;
        this.locationEligibility = updated.locationEligibility;
        this.remoteScope = updated.remoteScope;
        this.normalizedCity = updated.normalizedCity;
        this.normalizedCountry = updated.normalizedCountry;
        this.eligibleFromRomania = updated.eligibleFromRomania;
        this.eligibilityReason = updated.eligibilityReason;
        this.detectedLocationRestrictions = updated.detectedLocationRestrictions;
        this.requiredTimezone = updated.requiredTimezone;
        this.requiredWorkAuthorization = updated.requiredWorkAuthorization;
        this.seniorityLevel = updated.seniorityLevel;
        this.experienceMinimumYears = updated.experienceMinimumYears;
        this.experienceMaximumYears = updated.experienceMaximumYears;
        this.experienceMandatory = updated.experienceMandatory;
        this.experienceRawText = updated.experienceRawText;
        this.earlyCareerEligibility = updated.earlyCareerEligibility;
        this.earlyCareerEligibilityReason = updated.earlyCareerEligibilityReason;
        seenAgain(now);
    }

    private void applyEligibility(LocationEligibilityDecision decision) {
        if (decision == null) {
            this.locationEligibility = LocationEligibility.REMOTE_ELIGIBILITY_UNKNOWN;
            this.remoteScope = RemoteScope.UNKNOWN;
            this.eligibleFromRomania = false;
            this.eligibilityReason = "Eligibility was not evaluated";
            return;
        }
        this.locationEligibility = decision.locationEligibility();
        this.remoteScope = decision.remoteScope();
        this.normalizedCity = decision.normalizedCity();
        this.normalizedCountry = decision.normalizedCountry();
        this.eligibleFromRomania = decision.eligibleFromRomania();
        this.eligibilityReason = decision.eligibilityReason();
        this.detectedLocationRestrictions = String.join("|", decision.detectedLocationRestrictions());
        this.requiredTimezone = decision.requiredTimezone();
        this.requiredWorkAuthorization = decision.requiredWorkAuthorization();
    }

    private void applyEarlyCareer(EarlyCareerDecision decision) {
        if (decision == null) {
            this.seniorityLevel = SeniorityLevel.UNKNOWN;
            this.earlyCareerEligibility = EarlyCareerEligibility.UNKNOWN;
            this.earlyCareerEligibilityReason = "No seniority or experience requirement could be determined";
            return;
        }
        this.seniorityLevel = decision.seniorityLevel();
        this.experienceMinimumYears = decision.experienceRequirement().minimumYears();
        this.experienceMaximumYears = decision.experienceRequirement().maximumYears();
        this.experienceMandatory = decision.experienceRequirement().mandatory();
        this.experienceRawText = decision.experienceRequirement().rawText();
        this.earlyCareerEligibility = decision.earlyCareerEligibility();
        this.earlyCareerEligibilityReason = decision.eligibilityReason();
    }

    public void changeStatus(JobStatus next) {
        status = next;
    }

    public Long getId() { return id; }
    public String getSource() { return source; }
    public String getProviderTenant() { return providerTenant; }
    public String getExternalId() { return externalId; }
    public String getCanonicalUrl() { return canonicalUrl; }
    public String getTitle() { return title; }
    public String getCompany() { return company; }
    public String getLocation() { return location; }
    public RemoteType getRemoteType() { return remoteType; }
    public WorkplaceType getWorkplaceType() { return WorkplaceType.valueOf(remoteType.name()); }
    public LocationEligibility getLocationEligibility() { return locationEligibility; }
    public RemoteScope getRemoteScope() { return remoteScope; }
    public String getNormalizedCity() { return normalizedCity; }
    public String getNormalizedCountry() { return normalizedCountry; }
    public boolean isEligibleFromRomania() { return eligibleFromRomania; }
    public String getEligibilityReason() { return eligibilityReason; }
    public String getDetectedLocationRestrictions() { return detectedLocationRestrictions; }
    public String getRequiredTimezone() { return requiredTimezone; }
    public String getRequiredWorkAuthorization() { return requiredWorkAuthorization; }
    public SeniorityLevel getSeniorityLevel() { return seniorityLevel; }
    public Double getExperienceMinimumYears() { return experienceMinimumYears; }
    public Double getExperienceMaximumYears() { return experienceMaximumYears; }
    public boolean isExperienceMandatory() { return experienceMandatory; }
    public String getExperienceRawText() { return experienceRawText; }
    public EarlyCareerEligibility getEarlyCareerEligibility() { return earlyCareerEligibility; }
    public String getEarlyCareerEligibilityReason() { return earlyCareerEligibilityReason; }
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

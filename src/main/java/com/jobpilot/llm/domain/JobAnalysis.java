package com.jobpilot.llm.domain;

import com.jobpilot.candidate.domain.CandidateProfile;
import com.jobpilot.jobs.domain.Job;
import com.jobpilot.llm.budget.LlmBudgetReservation;
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
import jakarta.persistence.Version;
import java.time.Instant;

@Entity
@Table(name = "job_analyses")
public class JobAnalysis {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "job_id", nullable = false)
    private Job job;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "candidate_profile_id")
    private CandidateProfile candidateProfile;
    private Integer candidateProfileVersion;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 60)
    private LlmOperationType operationType;
    @Column(nullable = false, length = 120)
    private String provider;
    @Column(nullable = false, length = 200)
    private String model;
    @Column(nullable = false, length = 80)
    private String promptVersion;
    @Column(nullable = false, length = 64)
    private String jobContentHash;
    @Column(length = 64)
    private String candidateTruthHash;
    @Column(nullable = false, unique = true, length = 64)
    private String cacheKey;
    @Column(nullable = false)
    private int attemptCount;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reservation_id")
    private LlmBudgetReservation reservation;
    @Column(columnDefinition = "text")
    private String roleSummary;
    @Column(columnDefinition = "text")
    private String mustHaveRequirements;
    @Column(columnDefinition = "text")
    private String preferredRequirements;
    @Column(columnDefinition = "text")
    private String responsibilities;
    @Column(columnDefinition = "text")
    private String experienceRequirement;
    @Column(columnDefinition = "text")
    private String educationRequirement;
    @Column(columnDefinition = "text")
    private String languageRequirement;
    @Column(columnDefinition = "text")
    private String locationConstraints;
    @Column(columnDefinition = "text")
    private String workAuthorizationSignals;
    @Column(columnDefinition = "text")
    private String candidateStrengths;
    @Column(columnDefinition = "text")
    private String candidateGaps;
    @Column(columnDefinition = "text")
    private String ambiguousRequirements;
    @Column(columnDefinition = "text")
    private String evidenceReferences;
    private Integer confidenceScore;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private JobAnalysisStatus status;
    @Column(nullable = false)
    private boolean fallbackUsed;
    @Enumerated(EnumType.STRING)
    @Column(length = 80)
    private LlmFailureCategory failureCategory;
    @Column(nullable = false)
    private Instant createdAt;
    private Instant completedAt;
    private Instant retryAfter;
    @Version
    private long version;

    protected JobAnalysis() {
    }

    public JobAnalysis(Job job, CandidateProfile candidateProfile, LlmOperationType operationType,
                       String provider, String model, String promptVersion, String jobContentHash,
                       String candidateTruthHash, String cacheKey, Instant createdAt) {
        this.job = job;
        this.candidateProfile = candidateProfile;
        this.candidateProfileVersion = candidateProfile == null ? null : candidateProfile.getProfileVersion();
        this.operationType = operationType;
        this.provider = provider;
        this.model = model;
        this.promptVersion = promptVersion;
        this.jobContentHash = jobContentHash;
        this.candidateTruthHash = candidateTruthHash;
        this.cacheKey = cacheKey;
        this.attemptCount = 1;
        this.status = JobAnalysisStatus.IN_PROGRESS;
        this.createdAt = createdAt;
    }

    public void attachReservation(LlmBudgetReservation value) {
        reservation = value;
    }

    public void complete(JobAnalysisData data, JobAnalysisJson json, Instant now) {
        roleSummary = data.roleSummary();
        mustHaveRequirements = json.write(data.mustHaveRequirements());
        preferredRequirements = json.write(data.preferredRequirements());
        responsibilities = json.write(data.responsibilities());
        experienceRequirement = data.experienceRequirement();
        educationRequirement = data.educationRequirement();
        languageRequirement = data.languageRequirement();
        locationConstraints = data.locationConstraints();
        workAuthorizationSignals = data.workAuthorizationSignals();
        candidateStrengths = json.write(data.candidateStrengths());
        candidateGaps = json.write(data.candidateGaps());
        ambiguousRequirements = json.write(data.ambiguousRequirements());
        evidenceReferences = json.write(data.evidenceReferences());
        confidenceScore = data.confidenceScore();
        fallbackUsed = data.deterministicFallbackUsed();
        status = fallbackUsed ? JobAnalysisStatus.FALLBACK : JobAnalysisStatus.SUCCEEDED;
        failureCategory = null;
        retryAfter = null;
        completedAt = now;
    }

    public void completeFallback(JobAnalysisData data, LlmFailureCategory category,
                                 Instant retryAt, JobAnalysisJson json, Instant now) {
        complete(data, json, now);
        fallbackUsed = true;
        status = JobAnalysisStatus.FALLBACK;
        failureCategory = category;
        retryAfter = retryAt;
    }

    public void beginRetry(Instant now) {
        attemptCount++;
        reservation = null;
        status = JobAnalysisStatus.IN_PROGRESS;
        fallbackUsed = false;
        failureCategory = null;
        completedAt = null;
        retryAfter = null;
        createdAt = now;
        clearCanonicalData();
    }

    private void clearCanonicalData() {
        roleSummary = null;
        mustHaveRequirements = null;
        preferredRequirements = null;
        responsibilities = null;
        experienceRequirement = null;
        educationRequirement = null;
        languageRequirement = null;
        locationConstraints = null;
        workAuthorizationSignals = null;
        candidateStrengths = null;
        candidateGaps = null;
        ambiguousRequirements = null;
        evidenceReferences = null;
        confidenceScore = null;
    }

    public JobAnalysisData toData(JobAnalysisJson json) {
        if (status == JobAnalysisStatus.IN_PROGRESS) return null;
        return new JobAnalysisData(roleSummary,
                json.readStrings(mustHaveRequirements), json.readStrings(preferredRequirements),
                json.readStrings(responsibilities), experienceRequirement, educationRequirement,
                languageRequirement, locationConstraints, workAuthorizationSignals,
                json.readStrengths(candidateStrengths), json.readStrings(candidateGaps),
                json.readStrings(ambiguousRequirements), json.readEvidence(evidenceReferences),
                confidenceScore, fallbackUsed);
    }

    public Long getId() { return id; }
    public Job getJob() { return job; }
    public CandidateProfile getCandidateProfile() { return candidateProfile; }
    public Integer getCandidateProfileVersion() { return candidateProfileVersion; }
    public LlmOperationType getOperationType() { return operationType; }
    public String getProvider() { return provider; }
    public String getModel() { return model; }
    public String getPromptVersion() { return promptVersion; }
    public String getJobContentHash() { return jobContentHash; }
    public String getCandidateTruthHash() { return candidateTruthHash; }
    public String getCacheKey() { return cacheKey; }
    public int getAttemptCount() { return attemptCount; }
    public LlmBudgetReservation getReservation() { return reservation; }
    public JobAnalysisStatus getStatus() { return status; }
    public boolean isFallbackUsed() { return fallbackUsed; }
    public LlmFailureCategory getFailureCategory() { return failureCategory; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getCompletedAt() { return completedAt; }
    public Instant getRetryAfter() { return retryAfter; }
}

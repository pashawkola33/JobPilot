package com.jobpilot.resume.domain;

import com.jobpilot.candidate.domain.CandidateLanguage;
import com.jobpilot.candidate.domain.CandidateProfile;
import com.jobpilot.candidate.domain.CandidateProject;
import com.jobpilot.candidate.domain.CandidateProjectBullet;
import com.jobpilot.candidate.domain.CandidateSkill;
import com.jobpilot.jobs.domain.Job;
import com.jobpilot.llm.domain.JobAnalysis;
import jakarta.persistence.CascadeType;
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
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Entity
@Table(name = "resume_versions")
public class ResumeVersion {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Version
    @Column(nullable = false)
    private long version;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "job_id", nullable = false)
    private Job job;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "candidate_profile_id", nullable = false)
    private CandidateProfile candidateProfile;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "source_analysis_id")
    private JobAnalysis sourceAnalysis;
    @Column(nullable = false)
    private int profileVersion;
    @Column(length = 200)
    private String selectedTitle;
    @Column(columnDefinition = "text")
    private String summary;
    @Column(columnDefinition = "text")
    private String plainTextPreview;
    @Column(columnDefinition = "text")
    private String changeSummary;
    @Column(columnDefinition = "text")
    private String interviewClaims;
    @Column(length = 1000)
    private String docxPath;
    @Column(length = 64)
    private String docxHash;
    private Long docxSize;
    @Column(length = 1000)
    private String pdfPath;
    @Column(length = 64)
    private String pdfHash;
    private Long pdfSize;
    private Integer pdfPageCount;
    @Column(length = 64)
    private String structuredContentHash;
    @Column(nullable = false, unique = true, length = 64)
    private String cacheKey;
    @Column(nullable = false, length = 80)
    private String templateVersion;
    @Column(nullable = false, length = 80)
    private String rendererVersion;
    @Column(nullable = false, length = 40)
    private String requestedFormats;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private DocumentGenerationMethod generationMethod;
    @Column(nullable = false, length = 120)
    private String provider;
    @Column(nullable = false, length = 200)
    private String model;
    @Column(nullable = false)
    private boolean fallbackUsed;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private DocumentRenderStatus renderStatus;
    @Enumerated(EnumType.STRING)
    @Column(length = 60)
    private DocumentFailureCategory failureCategory;
    @Column(nullable = false)
    private int attemptCount;
    private Instant generatedAt;
    @Column(nullable = false)
    private Instant createdAt;
    @Column(nullable = false)
    private Instant updatedAt;

    @OneToMany(mappedBy = "resumeVersion", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("displayOrder ASC, id ASC")
    private final List<ResumeVersionSkill> selectedSkills = new ArrayList<>();
    @OneToMany(mappedBy = "resumeVersion", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("displayOrder ASC, id ASC")
    private final List<ResumeVersionProject> selectedProjects = new ArrayList<>();
    @OneToMany(mappedBy = "resumeVersion", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("displayOrder ASC, id ASC")
    private final List<ResumeVersionProjectBullet> selectedProjectBullets = new ArrayList<>();
    @OneToMany(mappedBy = "resumeVersion", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("displayOrder ASC, id ASC")
    private final List<ResumeVersionLanguage> selectedLanguages = new ArrayList<>();

    protected ResumeVersion() {
    }

    public static ResumeVersion inProgress(Job job, CandidateProfile profile,
                                           JobAnalysis sourceAnalysis, String cacheKey,
                                           String templateVersion, String rendererVersion,
                                           Set<DocumentFormat> formats, String provider,
                                           String model, Instant now) {
        ResumeVersion value = new ResumeVersion();
        value.job = job;
        value.candidateProfile = profile;
        value.sourceAnalysis = sourceAnalysis;
        value.profileVersion = profile.getProfileVersion();
        value.cacheKey = cacheKey;
        value.templateVersion = templateVersion;
        value.rendererVersion = rendererVersion;
        value.requestedFormats = encodeFormats(formats);
        value.generationMethod = DocumentGenerationMethod.DETERMINISTIC;
        value.provider = provider;
        value.model = model;
        value.renderStatus = DocumentRenderStatus.IN_PROGRESS;
        value.attemptCount = 1;
        value.createdAt = now;
        value.updatedAt = now;
        return value;
    }

    /** Compatibility constructor for the Stage 1 persistence regression tests. */
    public ResumeVersion(Job job, CandidateProfile candidateProfile, int profileVersion,
                         String selectedTitle, String summary, String plainTextPreview,
                         String changeSummary, String interviewClaims, String docxPath,
                         String pdfPath, String contentHash, Instant generatedAt) {
        this.job = job;
        this.candidateProfile = candidateProfile;
        this.profileVersion = profileVersion;
        this.selectedTitle = selectedTitle;
        this.summary = summary;
        this.plainTextPreview = plainTextPreview;
        this.changeSummary = changeSummary;
        this.interviewClaims = interviewClaims;
        this.structuredContentHash = contentHash;
        this.cacheKey = contentHash;
        this.templateVersion = "legacy-v1";
        this.rendererVersion = "legacy-v1";
        this.requestedFormats = "NONE";
        this.generationMethod = DocumentGenerationMethod.DETERMINISTIC;
        this.provider = "disabled";
        this.model = "disabled";
        this.renderStatus = docxPath == null && pdfPath == null
                ? DocumentRenderStatus.COMPLETED : DocumentRenderStatus.FAILED;
        this.failureCategory = this.renderStatus == DocumentRenderStatus.FAILED
                ? DocumentFailureCategory.ARTIFACT_INVALID : null;
        this.attemptCount = 1;
        this.generatedAt = generatedAt;
        this.createdAt = generatedAt;
        this.updatedAt = generatedAt;
    }

    public void beginRetry(Instant now) {
        if (renderStatus == DocumentRenderStatus.COMPLETED) {
            throw new IllegalStateException("A completed resume version is immutable");
        }
        selectedSkills.clear();
        selectedProjects.clear();
        selectedProjectBullets.clear();
        selectedLanguages.clear();
        selectedTitle = null;
        summary = null;
        plainTextPreview = null;
        changeSummary = null;
        interviewClaims = null;
        docxPath = null;
        docxHash = null;
        docxSize = null;
        pdfPath = null;
        pdfHash = null;
        pdfSize = null;
        pdfPageCount = null;
        structuredContentHash = null;
        generatedAt = null;
        failureCategory = null;
        fallbackUsed = false;
        renderStatus = DocumentRenderStatus.IN_PROGRESS;
        attemptCount++;
        updatedAt = now;
    }

    public void complete(String title, String factualSummary, String preview,
                         String changes, String claims, String contentHash,
                         DocumentGenerationMethod method, boolean fallback,
                         DocumentArtifactMetadata docx, DocumentArtifactMetadata pdf,
                         Instant now) {
        if (renderStatus != DocumentRenderStatus.IN_PROGRESS) {
            throw new IllegalStateException("Resume generation was not in progress");
        }
        selectedTitle = title;
        summary = factualSummary;
        plainTextPreview = preview;
        changeSummary = changes;
        interviewClaims = claims;
        structuredContentHash = contentHash;
        generationMethod = method;
        fallbackUsed = fallback;
        applyDocx(docx);
        applyPdf(pdf);
        if (requestedFormatSet().contains(DocumentFormat.DOCX) != (docx != null)
                || requestedFormatSet().contains(DocumentFormat.PDF) != (pdf != null)) {
            throw new IllegalArgumentException("Completed artifacts do not match requested formats");
        }
        renderStatus = DocumentRenderStatus.COMPLETED;
        failureCategory = null;
        generatedAt = now;
        updatedAt = now;
    }

    public void fail(DocumentFailureCategory category, Instant now) {
        if (renderStatus == DocumentRenderStatus.COMPLETED) return;
        renderStatus = DocumentRenderStatus.FAILED;
        failureCategory = category;
        updatedAt = now;
    }

    public boolean isStale(Instant now, Duration threshold) {
        return renderStatus == DocumentRenderStatus.IN_PROGRESS
                && !updatedAt.plus(threshold).isAfter(now);
    }

    public void selectSkill(CandidateSkill skill, int displayOrder) {
        selectedSkills.add(new ResumeVersionSkill(this, skill, displayOrder));
    }

    public void selectProject(CandidateProject project, int displayOrder) {
        selectedProjects.add(new ResumeVersionProject(this, project, displayOrder));
    }

    public void selectProjectBullet(CandidateProjectBullet bullet, int displayOrder) {
        selectedProjectBullets.add(new ResumeVersionProjectBullet(this, bullet, displayOrder));
    }

    public void selectLanguage(CandidateLanguage language, int displayOrder) {
        selectedLanguages.add(new ResumeVersionLanguage(this, language, displayOrder));
    }

    public Set<DocumentFormat> requestedFormatSet() {
        if ("NONE".equals(requestedFormats)) return Set.of();
        EnumSet<DocumentFormat> values = EnumSet.noneOf(DocumentFormat.class);
        for (String value : requestedFormats.split(",")) values.add(DocumentFormat.valueOf(value));
        return Collections.unmodifiableSet(values);
    }

    public DocumentArtifactMetadata artifact(DocumentFormat format) {
        if (format == DocumentFormat.DOCX && docxPath != null) {
            return new DocumentArtifactMetadata(docxPath, docxHash, docxSize);
        }
        if (format == DocumentFormat.PDF && pdfPath != null) {
            return new DocumentArtifactMetadata(pdfPath, pdfHash, pdfSize, pdfPageCount);
        }
        return null;
    }

    private void applyDocx(DocumentArtifactMetadata artifact) {
        if (artifact == null) return;
        docxPath = artifact.relativePath();
        docxHash = artifact.sha256();
        docxSize = artifact.size();
    }

    private void applyPdf(DocumentArtifactMetadata artifact) {
        if (artifact == null) return;
        pdfPath = artifact.relativePath();
        pdfHash = artifact.sha256();
        pdfSize = artifact.size();
        pdfPageCount = artifact.pageCount();
    }

    private static String encodeFormats(Set<DocumentFormat> formats) {
        if (formats == null || formats.isEmpty()) throw new IllegalArgumentException("At least one format is required");
        return formats.stream().sorted().map(Enum::name).collect(Collectors.joining(","));
    }

    public Long getId() { return id; }
    public long getVersion() { return version; }
    public Job getJob() { return job; }
    public CandidateProfile getCandidateProfile() { return candidateProfile; }
    public JobAnalysis getSourceAnalysis() { return sourceAnalysis; }
    public int getProfileVersion() { return profileVersion; }
    public String getSelectedTitle() { return selectedTitle; }
    public String getSummary() { return summary; }
    public String getPlainTextPreview() { return plainTextPreview; }
    public String getChangeSummary() { return changeSummary; }
    public String getInterviewClaims() { return interviewClaims; }
    public String getDocxPath() { return docxPath; }
    public String getDocxHash() { return docxHash; }
    public Long getDocxSize() { return docxSize; }
    public String getPdfPath() { return pdfPath; }
    public String getPdfHash() { return pdfHash; }
    public Long getPdfSize() { return pdfSize; }
    public Integer getPdfPageCount() { return pdfPageCount; }
    public String getContentHash() { return structuredContentHash; }
    public String getStructuredContentHash() { return structuredContentHash; }
    public String getCacheKey() { return cacheKey; }
    public String getTemplateVersion() { return templateVersion; }
    public String getRendererVersion() { return rendererVersion; }
    public String getRequestedFormats() { return requestedFormats; }
    public DocumentGenerationMethod getGenerationMethod() { return generationMethod; }
    public String getProvider() { return provider; }
    public String getModel() { return model; }
    public boolean isFallbackUsed() { return fallbackUsed; }
    public DocumentRenderStatus getRenderStatus() { return renderStatus; }
    public DocumentFailureCategory getFailureCategory() { return failureCategory; }
    public int getAttemptCount() { return attemptCount; }
    public Instant getGeneratedAt() { return generatedAt; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public List<ResumeVersionSkill> getSelectedSkills() { return Collections.unmodifiableList(selectedSkills); }
    public List<ResumeVersionProject> getSelectedProjects() { return Collections.unmodifiableList(selectedProjects); }
    public List<ResumeVersionProjectBullet> getSelectedProjectBullets() { return Collections.unmodifiableList(selectedProjectBullets); }
    public List<ResumeVersionLanguage> getSelectedLanguages() { return Collections.unmodifiableList(selectedLanguages); }
}

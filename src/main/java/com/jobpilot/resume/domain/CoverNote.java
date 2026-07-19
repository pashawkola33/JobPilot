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
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Entity
@Table(name = "cover_notes")
public class CoverNote {
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
    @JoinColumn(name = "resume_version_id")
    private ResumeVersion resumeVersion;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "source_analysis_id")
    private JobAnalysis sourceAnalysis;
    @Column(nullable = false)
    private int profileVersion;
    @Column(columnDefinition = "text")
    private String content;
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
    private Instant generatedAt;
    @Column(nullable = false)
    private Instant createdAt;
    @Column(nullable = false)
    private Instant updatedAt;

    @OneToMany(mappedBy = "coverNote", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("displayOrder ASC, id ASC")
    private final List<CoverNoteFactReference> factReferences = new ArrayList<>();

    protected CoverNote() {
    }

    public static CoverNote inProgress(Job job, CandidateProfile profile, ResumeVersion resume,
                                       JobAnalysis analysis, String cacheKey,
                                       String templateVersion, String rendererVersion,
                                       Set<DocumentFormat> formats, String provider,
                                       String model, Instant now) {
        CoverNote value = new CoverNote();
        value.job = job;
        value.candidateProfile = profile;
        value.resumeVersion = resume;
        value.sourceAnalysis = analysis;
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
    public CoverNote(Job job, CandidateProfile candidateProfile, ResumeVersion resumeVersion,
                     String content, String contentHash, Instant generatedAt) {
        this.job = job;
        this.candidateProfile = candidateProfile;
        this.resumeVersion = resumeVersion;
        this.profileVersion = candidateProfile.getProfileVersion();
        this.content = content;
        this.structuredContentHash = contentHash;
        this.cacheKey = contentHash;
        this.templateVersion = "legacy-v1";
        this.rendererVersion = "legacy-v1";
        this.requestedFormats = "NONE";
        this.generationMethod = DocumentGenerationMethod.DETERMINISTIC;
        this.provider = "disabled";
        this.model = "disabled";
        this.renderStatus = DocumentRenderStatus.COMPLETED;
        this.attemptCount = 1;
        this.generatedAt = generatedAt;
        this.createdAt = generatedAt;
        this.updatedAt = generatedAt;
    }

    public void complete(String validatedContent, String contentHash,
                         DocumentGenerationMethod method, boolean fallback,
                         DocumentArtifactMetadata docx, DocumentArtifactMetadata pdf,
                         Instant now) {
        if (renderStatus != DocumentRenderStatus.IN_PROGRESS) {
            throw new IllegalStateException("Cover-note generation was not in progress");
        }
        content = validatedContent;
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

    public boolean isStale(Instant now, java.time.Duration staleAfter) {
        return renderStatus == DocumentRenderStatus.IN_PROGRESS
                && updatedAt.plus(staleAfter).isBefore(now);
    }

    public void beginRetry(Instant now) {
        if (renderStatus == DocumentRenderStatus.COMPLETED) {
            throw new IllegalStateException("Completed cover notes are immutable");
        }
        content = null;
        structuredContentHash = null;
        generationMethod = DocumentGenerationMethod.DETERMINISTIC;
        fallbackUsed = false;
        renderStatus = DocumentRenderStatus.IN_PROGRESS;
        failureCategory = null;
        docxPath = null;
        docxHash = null;
        docxSize = null;
        pdfPath = null;
        pdfHash = null;
        pdfSize = null;
        pdfPageCount = null;
        generatedAt = null;
        factReferences.clear();
        attemptCount++;
        updatedAt = now;
    }

    public void referenceProfile(CandidateProfile value, int order) {
        factReferences.add(CoverNoteFactReference.profile(this, value, order));
    }

    public void referenceSkill(CandidateSkill value, int order) {
        factReferences.add(CoverNoteFactReference.skill(this, value, order));
    }

    public void referenceLanguage(CandidateLanguage value, int order) {
        factReferences.add(CoverNoteFactReference.language(this, value, order));
    }

    public void referenceProject(CandidateProject value, int order) {
        factReferences.add(CoverNoteFactReference.project(this, value, order));
    }

    public void referenceProjectBullet(CandidateProjectBullet value, int order) {
        factReferences.add(CoverNoteFactReference.bullet(this, value, order));
    }

    public Set<DocumentFormat> requestedFormatSet() {
        if ("NONE".equals(requestedFormats)) return Set.of();
        return java.util.Arrays.stream(requestedFormats.split(","))
                .map(DocumentFormat::valueOf).collect(Collectors.toUnmodifiableSet());
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
    public ResumeVersion getResumeVersion() { return resumeVersion; }
    public JobAnalysis getSourceAnalysis() { return sourceAnalysis; }
    public int getProfileVersion() { return profileVersion; }
    public String getContent() { return content; }
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
    public String getDocxPath() { return docxPath; }
    public String getDocxHash() { return docxHash; }
    public Long getDocxSize() { return docxSize; }
    public String getPdfPath() { return pdfPath; }
    public String getPdfHash() { return pdfHash; }
    public Long getPdfSize() { return pdfSize; }
    public Integer getPdfPageCount() { return pdfPageCount; }
    public Instant getGeneratedAt() { return generatedAt; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public List<CoverNoteFactReference> getFactReferences() { return Collections.unmodifiableList(factReferences); }
}

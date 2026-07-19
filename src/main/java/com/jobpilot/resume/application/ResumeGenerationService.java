package com.jobpilot.resume.application;

import com.jobpilot.candidate.domain.CandidateLanguage;
import com.jobpilot.candidate.domain.CandidateProfile;
import com.jobpilot.candidate.domain.CandidateProject;
import com.jobpilot.candidate.domain.CandidateProjectBullet;
import com.jobpilot.candidate.domain.CandidateSkill;
import com.jobpilot.candidate.repository.CandidateLanguageRepository;
import com.jobpilot.candidate.repository.CandidateProfileRepository;
import com.jobpilot.candidate.repository.CandidateProjectBulletRepository;
import com.jobpilot.candidate.repository.CandidateProjectRepository;
import com.jobpilot.candidate.repository.CandidateSkillRepository;
import com.jobpilot.jobs.domain.Job;
import com.jobpilot.jobs.repository.JobRepository;
import com.jobpilot.llm.application.JobAnalysisService;
import com.jobpilot.llm.domain.JobAnalysis;
import com.jobpilot.llm.domain.JobAnalysisJson;
import com.jobpilot.llm.domain.JobAnalysisResult;
import com.jobpilot.llm.domain.JobAnalysisResultStatus;
import com.jobpilot.llm.domain.LlmFailureCategory;
import com.jobpilot.llm.repository.JobAnalysisRepository;
import com.jobpilot.resume.config.DocumentProperties;
import com.jobpilot.resume.domain.CoverNote;
import com.jobpilot.resume.domain.CoverNoteDocumentModel;
import com.jobpilot.resume.domain.DocumentArtifactMetadata;
import com.jobpilot.resume.domain.DocumentContactBlock;
import com.jobpilot.resume.domain.DocumentFailureCategory;
import com.jobpilot.resume.domain.DocumentFormat;
import com.jobpilot.resume.domain.DocumentGenerationMethod;
import com.jobpilot.resume.domain.DocumentRenderStatus;
import com.jobpilot.resume.domain.ResumeDocumentModel;
import com.jobpilot.resume.domain.ResumeVersion;
import com.jobpilot.resume.render.CoverNoteDocxRenderer;
import com.jobpilot.resume.render.CoverNotePdfRenderer;
import com.jobpilot.resume.render.DocumentRenderException;
import com.jobpilot.resume.render.ResumeDocxRenderer;
import com.jobpilot.resume.render.ResumePdfRenderer;
import com.jobpilot.resume.repository.CoverNoteRepository;
import com.jobpilot.resume.repository.ResumeVersionRepository;
import com.jobpilot.resume.storage.ArtifactValidationException;
import com.jobpilot.resume.storage.DocumentArtifactBundle;
import com.jobpilot.resume.storage.DocumentArtifactStorage;
import com.jobpilot.resume.storage.DocumentKind;
import com.jobpilot.resume.storage.DocumentStorageException;
import com.jobpilot.resume.validation.CoverNoteTruthValidationException;
import com.jobpilot.resume.validation.CoverNoteTruthValidator;
import com.jobpilot.resume.validation.DocumentConfigurationException;
import com.jobpilot.resume.validation.DocumentContactPolicy;
import com.jobpilot.resume.validation.ResumeTruthValidationException;
import com.jobpilot.resume.validation.ResumeTruthValidator;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class ResumeGenerationService {
    private final JobRepository jobs;
    private final CandidateProfileRepository profiles;
    private final CandidateSkillRepository skills;
    private final CandidateLanguageRepository languages;
    private final CandidateProjectRepository projects;
    private final CandidateProjectBulletRepository bullets;
    private final JobAnalysisRepository analyses;
    private final JobAnalysisJson analysisJson;
    private final ResumeVersionRepository resumes;
    private final CoverNoteRepository coverNotes;
    private final JobAnalysisService analysisService;
    private final ResumeDraftBuilder resumeBuilder;
    private final CoverNoteDraftBuilder coverBuilder;
    private final ResumeTruthValidator resumeValidator;
    private final CoverNoteTruthValidator coverValidator;
    private final DocumentLlmDraftService draftService;
    private final ResumePreviewBuilder previews;
    private final DocumentModelHasher modelHasher;
    private final DocumentCacheKey cacheKeys;
    private final DocumentGenerationClaimObserver claimObserver;
    private final DocumentContactPolicy contacts;
    private final ResumeDocxRenderer resumeDocx;
    private final ResumePdfRenderer resumePdf;
    private final CoverNoteDocxRenderer coverDocx;
    private final CoverNotePdfRenderer coverPdf;
    private final DocumentArtifactStorage storage;
    private final DocumentProperties properties;
    private final Clock clock;
    private final TransactionTemplate transactions;

    public ResumeGenerationService(
            JobRepository jobs, CandidateProfileRepository profiles,
            CandidateSkillRepository skills, CandidateLanguageRepository languages,
            CandidateProjectRepository projects, CandidateProjectBulletRepository bullets,
            JobAnalysisRepository analyses, JobAnalysisJson analysisJson,
            ResumeVersionRepository resumes, CoverNoteRepository coverNotes,
            JobAnalysisService analysisService, ResumeDraftBuilder resumeBuilder,
            CoverNoteDraftBuilder coverBuilder, ResumeTruthValidator resumeValidator,
            CoverNoteTruthValidator coverValidator, DocumentLlmDraftService draftService,
            ResumePreviewBuilder previews, DocumentModelHasher modelHasher,
            DocumentCacheKey cacheKeys, DocumentGenerationClaimObserver claimObserver,
            DocumentContactPolicy contacts,
            ResumeDocxRenderer resumeDocx, ResumePdfRenderer resumePdf,
            CoverNoteDocxRenderer coverDocx, CoverNotePdfRenderer coverPdf,
            DocumentArtifactStorage storage, DocumentProperties properties,
            Clock clock, PlatformTransactionManager transactionManager) {
        this.jobs = jobs;
        this.profiles = profiles;
        this.skills = skills;
        this.languages = languages;
        this.projects = projects;
        this.bullets = bullets;
        this.analyses = analyses;
        this.analysisJson = analysisJson;
        this.resumes = resumes;
        this.coverNotes = coverNotes;
        this.analysisService = analysisService;
        this.resumeBuilder = resumeBuilder;
        this.coverBuilder = coverBuilder;
        this.resumeValidator = resumeValidator;
        this.coverValidator = coverValidator;
        this.draftService = draftService;
        this.previews = previews;
        this.modelHasher = modelHasher;
        this.cacheKeys = cacheKeys;
        this.claimObserver = claimObserver;
        this.contacts = contacts;
        this.resumeDocx = resumeDocx;
        this.resumePdf = resumePdf;
        this.coverDocx = coverDocx;
        this.coverPdf = coverPdf;
        this.storage = storage;
        this.properties = properties;
        this.clock = clock;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    public DocumentGenerationResult generate(long jobId, GenerateDocumentsCommand command) {
        if (!properties.enabled()) {
            return DocumentGenerationResult.failure(DocumentGenerationStatus.DISABLED, jobId);
        }
        if (!jobs.existsById(jobId)) {
            return DocumentGenerationResult.failure(DocumentGenerationStatus.JOB_NOT_FOUND, jobId);
        }
        final DocumentContactBlock contact;
        try {
            contact = contacts.requireValidContact();
        } catch (DocumentConfigurationException invalidConfiguration) {
            return DocumentGenerationResult.failure(
                    DocumentGenerationStatus.GENERATION_FAILED, jobId);
        }

        JobAnalysisResult analysisResult = analysisService.analyze(jobId, true);
        if (analysisResult.status() == JobAnalysisResultStatus.JOB_NOT_FOUND) {
            return DocumentGenerationResult.failure(DocumentGenerationStatus.JOB_NOT_FOUND, jobId);
        }
        if (analysisResult.status() == JobAnalysisResultStatus.PROFILE_NOT_FOUND) {
            return DocumentGenerationResult.failure(DocumentGenerationStatus.PROFILE_NOT_FOUND, jobId);
        }
        if (analysisResult.analysisId() == null || analysisResult.analysis() == null) {
            return DocumentGenerationResult.failure(DocumentGenerationStatus.ANALYSIS_FAILED, jobId);
        }

        final Context context;
        try {
            context = transactions.execute(status -> context(analysisResult));
        } catch (RuntimeException databaseFailure) {
            return DocumentGenerationResult.failure(DocumentGenerationStatus.ANALYSIS_FAILED, jobId);
        }
        if (context == null) {
            return DocumentGenerationResult.failure(DocumentGenerationStatus.PROFILE_NOT_FOUND, jobId);
        }

        String resumeKey = cacheKeys.resume(context.job(), context.candidate(), command.formats(),
                command.useLlmDrafting(), contact);
        ResumeClaim resumeClaim = claimResume(context, resumeKey, command);
        if (resumeClaim.state() == ClaimState.CONCURRENT) {
            ResumeVersion winner = awaitCompletedResume(resumeKey);
            if (winner == null) {
                return DocumentGenerationResult.failure(
                        DocumentGenerationStatus.GENERATION_FAILED, jobId);
            }
            resumeClaim = new ResumeClaim(winner, ClaimState.CACHED);
        }

        ResumeWork resumeWork;
        if (resumeClaim.state() == ClaimState.CACHED) {
            ResumeVersion cachedResume = resumeClaim.resume();
            if (!artifactsValid(cachedResume)) {
                return DocumentGenerationResult.failure(DocumentGenerationStatus.ARTIFACT_INVALID, jobId);
            }
            try {
                ResumeDocumentModel model = transactions.execute(status -> rebuildModel(
                        cachedResume.getId(), context, contact));
                resumeWork = new ResumeWork(cachedResume, model, false, false, null);
            } catch (RuntimeException invalidStoredFacts) {
                return DocumentGenerationResult.failure(DocumentGenerationStatus.ARTIFACT_INVALID, jobId);
            }
        } else {
            storage.deleteGeneratedBundle(DocumentKind.RESUME, resumeClaim.resume().getId());
            ResumeDraftPlan deterministic = resumeBuilder.deterministicPlan(
                    context.candidate(), context.job());
            try {
                resumeValidator.validatePlan(deterministic, context.candidate(), context.job());
            } catch (ResumeTruthValidationException invalidFallback) {
                failResume(resumeClaim.resume().getId(), DocumentFailureCategory.TRUTH_VALIDATION);
                return DocumentGenerationResult.failure(DocumentGenerationStatus.GENERATION_FAILED, jobId);
            }
            DocumentDraftOutcome<ResumeDraftPlan> outcome = command.useLlmDrafting()
                    ? draftService.resume(context.candidate(), context.job(), deterministic, resumeKey)
                    : new DocumentDraftOutcome<>(deterministic,
                    DocumentGenerationMethod.DETERMINISTIC, false, null);
            ResumeDocumentModel model;
            try {
                model = resumeBuilder.build(context.candidate(), context.job(), contact,
                        outcome.plan(), properties.resumeTemplateVersion());
                resumeValidator.validate(model, context.candidate(), context.job());
            } catch (RuntimeException invalidDraft) {
                failResume(resumeClaim.resume().getId(), DocumentFailureCategory.TRUTH_VALIDATION);
                return DocumentGenerationResult.failure(DocumentGenerationStatus.GENERATION_FAILED, jobId);
            }
            String preview = previews.build(model, properties.maxPreviewCharacters());
            Map<DocumentFormat, byte[]> rendered;
            try {
                rendered = renderResume(model, command.formats());
            } catch (DocumentRenderException renderFailure) {
                failResume(resumeClaim.resume().getId(), DocumentFailureCategory.RENDER_FAILED);
                return DocumentGenerationResult.failure(DocumentGenerationStatus.RENDER_FAILED, jobId);
            }
            DocumentArtifactBundle artifacts;
            try {
                artifacts = storage.store(DocumentKind.RESUME, resumeClaim.resume().getId(), rendered,
                        resumeExpectedText(model));
            } catch (ArtifactValidationException invalidArtifact) {
                failResume(resumeClaim.resume().getId(), DocumentFailureCategory.ARTIFACT_INVALID);
                return DocumentGenerationResult.failure(DocumentGenerationStatus.ARTIFACT_INVALID, jobId);
            } catch (DocumentStorageException storageFailure) {
                failResume(resumeClaim.resume().getId(), DocumentFailureCategory.STORAGE_FAILED);
                return DocumentGenerationResult.failure(DocumentGenerationStatus.STORAGE_FAILED, jobId);
            }
            ResumeVersion completed = completeResume(resumeClaim.resume().getId(), model, preview,
                    outcome, artifacts);
            if (completed == null) {
                storage.deleteGeneratedBundle(DocumentKind.RESUME, resumeClaim.resume().getId());
                failResume(resumeClaim.resume().getId(), DocumentFailureCategory.DATABASE_FAILED);
                return DocumentGenerationResult.failure(DocumentGenerationStatus.GENERATION_FAILED, jobId);
            }
            resumeWork = new ResumeWork(completed, model, true,
                    outcome.fallbackUsed(), outcome.failureCategory());
        }

        if (!command.includeCoverNote()) {
            return result(resumeWork.created() ? status(resumeWork.fallbackReason())
                            : DocumentGenerationStatus.CACHED,
                    context, resumeWork, null, null);
        }

        String coverKey = cacheKeys.coverNote(context.job(), context.candidate(),
                resumeWork.resume().getStructuredContentHash(), command.formats(),
                command.useLlmDrafting(), contact);
        CoverClaim coverClaim = claimCover(context, resumeWork.resume().getId(), coverKey, command);
        if (coverClaim.state() == ClaimState.CONCURRENT) {
            CoverNote winner = awaitCompletedCoverNote(coverKey);
            if (winner == null) {
                return result(DocumentGenerationStatus.GENERATION_FAILED,
                        context, resumeWork, null, null);
            }
            coverClaim = new CoverClaim(winner, ClaimState.CACHED);
        }
        if (coverClaim.state() == ClaimState.CACHED) {
            if (!artifactsValid(coverClaim.coverNote())) {
                return result(DocumentGenerationStatus.ARTIFACT_INVALID,
                        context, resumeWork, null, null);
            }
            DocumentGenerationStatus status = resumeWork.created()
                    ? status(resumeWork.fallbackReason()) : DocumentGenerationStatus.CACHED;
            return result(status, context, resumeWork, coverClaim.coverNote(), null);
        }

        storage.deleteGeneratedBundle(DocumentKind.COVER_NOTE, coverClaim.coverNote().getId());
        CoverNoteDraftPlan deterministicCover = coverBuilder.deterministicPlan(
                context.candidate(), context.job(), resumeWork.model());
        try {
            coverValidator.validatePlan(deterministicCover, context.candidate(),
                    context.job(), resumeWork.model());
        } catch (CoverNoteTruthValidationException invalidFallback) {
            failCover(coverClaim.coverNote().getId(), DocumentFailureCategory.TRUTH_VALIDATION);
            return result(DocumentGenerationStatus.GENERATION_FAILED,
                    context, resumeWork, null, null);
        }
        DocumentDraftOutcome<CoverNoteDraftPlan> coverOutcome = command.useLlmDrafting()
                ? draftService.coverNote(context.candidate(), context.job(), resumeWork.model(),
                deterministicCover, coverKey)
                : new DocumentDraftOutcome<>(deterministicCover,
                DocumentGenerationMethod.DETERMINISTIC, false, null);
        CoverNoteDocumentModel coverModel;
        try {
            coverModel = coverBuilder.build(context.candidate(), context.job(), resumeWork.model(),
                    contact, coverOutcome.plan(), properties.coverNoteTemplateVersion());
            coverValidator.validate(coverModel, context.candidate(), context.job(), resumeWork.model());
        } catch (RuntimeException invalidCover) {
            failCover(coverClaim.coverNote().getId(), DocumentFailureCategory.TRUTH_VALIDATION);
            return result(DocumentGenerationStatus.GENERATION_FAILED,
                    context, resumeWork, null, null);
        }
        Map<DocumentFormat, byte[]> renderedCover;
        try {
            renderedCover = renderCover(coverModel, command.formats());
        } catch (DocumentRenderException renderFailure) {
            failCover(coverClaim.coverNote().getId(), DocumentFailureCategory.RENDER_FAILED);
            return result(DocumentGenerationStatus.RENDER_FAILED, context, resumeWork, null, null);
        }
        DocumentArtifactBundle coverArtifacts;
        try {
            coverArtifacts = storage.store(DocumentKind.COVER_NOTE,
                    coverClaim.coverNote().getId(), renderedCover, coverExpectedText(coverModel));
        } catch (ArtifactValidationException invalidArtifact) {
            failCover(coverClaim.coverNote().getId(), DocumentFailureCategory.ARTIFACT_INVALID);
            return result(DocumentGenerationStatus.ARTIFACT_INVALID,
                    context, resumeWork, null, null);
        } catch (DocumentStorageException storageFailure) {
            failCover(coverClaim.coverNote().getId(), DocumentFailureCategory.STORAGE_FAILED);
            return result(DocumentGenerationStatus.STORAGE_FAILED, context, resumeWork, null, null);
        }
        CoverNote completedCover = completeCover(coverClaim.coverNote().getId(), coverModel,
                coverOutcome, coverArtifacts);
        if (completedCover == null) {
            storage.deleteGeneratedBundle(DocumentKind.COVER_NOTE, coverClaim.coverNote().getId());
            failCover(coverClaim.coverNote().getId(), DocumentFailureCategory.DATABASE_FAILED);
            return result(DocumentGenerationStatus.GENERATION_FAILED,
                    context, resumeWork, null, null);
        }
        LlmFailureCategory reason = resumeWork.fallbackReason() != null
                ? resumeWork.fallbackReason() : coverOutcome.failureCategory();
        return result(status(reason), context, resumeWork, completedCover,
                coverOutcome.failureCategory());
    }

    public ResumeMetadataView resume(long id) {
        return transactions.execute(status -> toView(resumes.findById(id).orElseThrow()));
    }

    public CoverNoteMetadataView coverNote(long id) {
        return transactions.execute(status -> toView(coverNotes.findById(id).orElseThrow()));
    }

    public List<ResumeMetadataView> resumesForJob(long jobId) {
        return transactions.execute(status -> resumes.findByJobIdOrderByGeneratedAtDesc(jobId).stream()
                .limit(20).map(this::toView).toList());
    }

    public List<CoverNoteMetadataView> coverNotesForJob(long jobId) {
        return transactions.execute(status -> coverNotes.findByJobIdOrderByGeneratedAtDesc(jobId).stream()
                .limit(20).map(this::toView).toList());
    }

    public DocumentDownload downloadResume(long id, DocumentFormat format) {
        DocumentArtifactMetadata metadata = transactions.execute(status -> {
            ResumeVersion resume = resumes.findById(id).orElseThrow();
            requireCompleted(resume.getRenderStatus());
            return resume.artifact(format);
        });
        if (metadata == null) throw new IllegalArgumentException("Requested resume format is unavailable");
        return download(metadata, format, "resume-" + id);
    }

    public DocumentDownload downloadCoverNote(long id, DocumentFormat format) {
        DocumentArtifactMetadata metadata = transactions.execute(status -> {
            CoverNote note = coverNotes.findById(id).orElseThrow();
            requireCompleted(note.getRenderStatus());
            return note.artifact(format);
        });
        if (metadata == null) throw new IllegalArgumentException("Requested cover-note format is unavailable");
        return download(metadata, format, "cover-note-" + id);
    }

    private Context context(JobAnalysisResult result) {
        Job job = jobs.findById(result.jobId()).orElseThrow();
        CandidateProfile profile = profiles.findByActiveTrue().orElse(null);
        if (profile == null || result.candidateProfileVersion() == null
                || profile.getProfileVersion() != result.candidateProfileVersion()) return null;
        JobAnalysis analysis = analyses.findById(result.analysisId()).orElseThrow();
        if (!analysis.getJob().getId().equals(job.getId())
                || !java.util.Objects.equals(analysis.getCandidateProfileVersion(),
                profile.getProfileVersion())) {
            throw new IllegalStateException("Analysis identity is incompatible with document facts");
        }
        return new Context(CandidateDocumentFacts.from(profile),
                JobDocumentFacts.from(job, analysis.getId(), analysis.getCacheKey(), result.analysis()));
    }

    private ResumeClaim claimResume(Context context, String cacheKey,
                                    GenerateDocumentsCommand command) {
        try {
            return transactions.execute(status -> {
                ResumeVersion existing = resumes.findByCacheKeyForUpdate(cacheKey).orElse(null);
                Instant now = clock.instant();
                if (existing != null) {
                    if (existing.getRenderStatus() == DocumentRenderStatus.COMPLETED) {
                        return new ResumeClaim(existing, ClaimState.CACHED);
                    }
                    if (existing.getRenderStatus() == DocumentRenderStatus.IN_PROGRESS
                            && !existing.isStale(now, properties.staleAfter())) {
                        return new ResumeClaim(existing, ClaimState.CONCURRENT);
                    }
                    existing.beginRetry(now);
                    resumes.saveAndFlush(existing);
                    return new ResumeClaim(existing, ClaimState.CLAIMED);
                }
                claimObserver.afterCacheMiss(DocumentKind.RESUME, cacheKey);
                Job job = jobs.findById(context.job().jobId()).orElseThrow();
                CandidateProfile profile = profiles.findById(context.candidate().profileId()).orElseThrow();
                JobAnalysis analysis = analyses.findById(context.job().analysisId()).orElseThrow();
                ResumeVersion claimed = ResumeVersion.inProgress(job, profile, analysis, cacheKey,
                        properties.resumeTemplateVersion(), properties.rendererVersion(),
                        command.formats(), cacheKeys.provider(command.useLlmDrafting()),
                        cacheKeys.model(command.useLlmDrafting()), now);
                return new ResumeClaim(resumes.saveAndFlush(claimed), ClaimState.CLAIMED);
            });
        } catch (DataIntegrityViolationException race) {
            return resumes.findByCacheKey(cacheKey)
                    .map(value -> new ResumeClaim(value, value.getRenderStatus()
                            == DocumentRenderStatus.COMPLETED ? ClaimState.CACHED : ClaimState.CONCURRENT))
                    .orElse(new ResumeClaim(null, ClaimState.CONCURRENT));
        }
    }

    private CoverClaim claimCover(Context context, long resumeId, String cacheKey,
                                  GenerateDocumentsCommand command) {
        try {
            return transactions.execute(status -> {
                CoverNote existing = coverNotes.findByCacheKeyForUpdate(cacheKey).orElse(null);
                if (existing != null) {
                    if (existing.getRenderStatus() == DocumentRenderStatus.COMPLETED) {
                        return new CoverClaim(existing, ClaimState.CACHED);
                    }
                    Instant now = clock.instant();
                    if (existing.getRenderStatus() == DocumentRenderStatus.IN_PROGRESS
                            && !existing.isStale(now, properties.staleAfter())) {
                        return new CoverClaim(existing, ClaimState.CONCURRENT);
                    }
                    existing.beginRetry(now);
                    coverNotes.saveAndFlush(existing);
                    return new CoverClaim(existing, ClaimState.CLAIMED);
                }
                claimObserver.afterCacheMiss(DocumentKind.COVER_NOTE, cacheKey);
                Job job = jobs.findById(context.job().jobId()).orElseThrow();
                CandidateProfile profile = profiles.findById(context.candidate().profileId()).orElseThrow();
                ResumeVersion resume = resumes.findById(resumeId).orElseThrow();
                JobAnalysis analysis = analyses.findById(context.job().analysisId()).orElseThrow();
                CoverNote claimed = CoverNote.inProgress(job, profile, resume, analysis, cacheKey,
                        properties.coverNoteTemplateVersion(), properties.rendererVersion(),
                        command.formats(), cacheKeys.provider(command.useLlmDrafting()),
                        cacheKeys.model(command.useLlmDrafting()), clock.instant());
                return new CoverClaim(coverNotes.saveAndFlush(claimed), ClaimState.CLAIMED);
            });
        } catch (DataIntegrityViolationException race) {
            return coverNotes.findByCacheKey(cacheKey)
                    .map(value -> new CoverClaim(value, value.getRenderStatus()
                            == DocumentRenderStatus.COMPLETED ? ClaimState.CACHED : ClaimState.CONCURRENT))
                    .orElse(new CoverClaim(null, ClaimState.CONCURRENT));
        }
    }

    private ResumeVersion completeResume(long id, ResumeDocumentModel model, String preview,
                                         DocumentDraftOutcome<?> outcome,
                                         DocumentArtifactBundle artifacts) {
        try {
            return transactions.execute(status -> {
                ResumeVersion resume = resumes.findByIdForUpdate(id).orElseThrow();
                if (resume.getRenderStatus() == DocumentRenderStatus.COMPLETED) return resume;
                Map<Long, CandidateSkill> skillFacts = mapById(skills.findAllById(
                        model.skills().stream().map(ResumeDocumentModel.Skill::factId).toList()),
                        CandidateSkill::getId);
                Map<Long, CandidateLanguage> languageFacts = mapById(languages.findAllById(
                        model.languages().stream().map(ResumeDocumentModel.Language::factId).toList()),
                        CandidateLanguage::getId);
                Map<Long, CandidateProject> projectFacts = mapById(projects.findAllById(
                        model.projects().stream().map(ResumeDocumentModel.Project::factId).toList()),
                        CandidateProject::getId);
                List<Long> bulletIds = model.projects().stream().flatMap(value -> value.bullets().stream())
                        .map(ResumeDocumentModel.Bullet::factId).toList();
                Map<Long, CandidateProjectBullet> bulletFacts = mapById(
                        bullets.findAllById(bulletIds), CandidateProjectBullet::getId);
                resume.complete(model.selectedRoleTitle(), model.professionalSummary(), preview,
                        String.join("\n", model.changeSummary()),
                        model.interviewClaims().stream().map(ResumeDocumentModel.InterviewClaim::statement)
                                .collect(Collectors.joining("\n")),
                        modelHasher.hash(model), outcome.method(), outcome.fallbackUsed(),
                        artifacts.docx(), artifacts.pdf(), clock.instant());
                int order = 0;
                for (ResumeDocumentModel.Skill value : model.skills()) {
                    resume.selectSkill(required(skillFacts, value.factId()), order++);
                }
                order = 0;
                for (ResumeDocumentModel.Project value : model.projects()) {
                    resume.selectProject(required(projectFacts, value.factId()), order++);
                }
                order = 0;
                for (ResumeDocumentModel.Project value : model.projects()) {
                    for (ResumeDocumentModel.Bullet bullet : value.bullets()) {
                        resume.selectProjectBullet(required(bulletFacts, bullet.factId()), order++);
                    }
                }
                order = 0;
                for (ResumeDocumentModel.Language value : model.languages()) {
                    resume.selectLanguage(required(languageFacts, value.factId()), order++);
                }
                return resumes.saveAndFlush(resume);
            });
        } catch (RuntimeException databaseFailure) {
            ResumeVersion existing = resumes.findById(id).orElse(null);
            return existing != null && existing.getRenderStatus() == DocumentRenderStatus.COMPLETED
                    && artifactsValid(existing) ? existing : null;
        }
    }

    private CoverNote completeCover(long id, CoverNoteDocumentModel model,
                                    DocumentDraftOutcome<?> outcome,
                                    DocumentArtifactBundle artifacts) {
        try {
            return transactions.execute(status -> {
                CoverNote note = coverNotes.findByIdForUpdate(id).orElseThrow();
                if (note.getRenderStatus() == DocumentRenderStatus.COMPLETED) return note;
                CandidateProfile profile = profiles.findById(note.getCandidateProfile().getId()).orElseThrow();
                Map<String, CandidateSkill> skillFacts = skills
                        .findByCandidateProfileIdOrderByDisplayOrder(profile.getId()).stream()
                        .collect(Collectors.toMap(CandidateSkill::getStableKey, Function.identity()));
                Map<String, CandidateLanguage> languageFacts = languages
                        .findByCandidateProfileIdOrderByDisplayOrder(profile.getId()).stream()
                        .collect(Collectors.toMap(CandidateLanguage::getStableKey, Function.identity()));
                Map<String, CandidateProject> projectFacts = projects
                        .findByCandidateProfileIdOrderByDisplayOrder(profile.getId()).stream()
                        .collect(Collectors.toMap(CandidateProject::getStableKey, Function.identity()));
                Map<String, CandidateProjectBullet> bulletFacts = new HashMap<>();
                for (CandidateProject project : projectFacts.values()) {
                    bullets.findByProjectIdOrderByDisplayOrder(project.getId()).forEach(value ->
                            bulletFacts.put(project.getStableKey() + ":" + value.getStableKey(), value));
                }
                note.complete(model.plainText(), modelHasher.hash(model), outcome.method(),
                        outcome.fallbackUsed(), artifacts.docx(), artifacts.pdf(), clock.instant());
                int order = 0;
                for (String key : model.referencedCandidateFactKeys()) {
                    if (key.startsWith("profile:")) note.referenceProfile(profile, order++);
                    else if (key.startsWith("skill:")) note.referenceSkill(required(
                            skillFacts, key.substring("skill:".length())), order++);
                    else if (key.startsWith("language:")) note.referenceLanguage(required(
                            languageFacts, key.substring("language:".length())), order++);
                    else if (key.startsWith("project:")) note.referenceProject(required(
                            projectFacts, key.substring("project:".length())), order++);
                    else if (key.startsWith("bullet:")) note.referenceProjectBullet(required(
                            bulletFacts, key.substring("bullet:".length())), order++);
                    else throw new IllegalStateException("Unknown validated candidate fact key");
                }
                return coverNotes.saveAndFlush(note);
            });
        } catch (RuntimeException databaseFailure) {
            CoverNote existing = coverNotes.findById(id).orElse(null);
            return existing != null && existing.getRenderStatus() == DocumentRenderStatus.COMPLETED
                    && artifactsValid(existing) ? existing : null;
        }
    }

    private ResumeDocumentModel rebuildModel(long resumeId, Context context,
                                             DocumentContactBlock contact) {
        ResumeVersion resume = resumes.findById(resumeId).orElseThrow();
        ResumeDraftPlan.TitleStyle style = java.util.Arrays.stream(ResumeDraftPlan.TitleStyle.values())
                .filter(value -> ResumeDraftBuilder.title(value).equals(resume.getSelectedTitle()))
                .findFirst().orElseThrow();
        ResumeDraftPlan plan = new ResumeDraftPlan(style,
                resume.getSelectedSkills().stream().map(value ->
                        value.getCandidateSkill().getStableKey()).toList(),
                resume.getSelectedProjects().stream().map(value ->
                        value.getCandidateProject().getStableKey()).toList(),
                resume.getSelectedProjectBullets().stream().map(value -> {
                    CandidateProjectBullet bullet = value.getCandidateProjectBullet();
                    return bullet.getProject().getStableKey() + ":" + bullet.getStableKey();
                }).toList(),
                resume.getSelectedLanguages().stream().map(value ->
                        value.getCandidateLanguage().getStableKey()).toList());
        resumeValidator.validatePlan(plan, context.candidate(), context.job());
        ResumeDocumentModel model = resumeBuilder.build(context.candidate(), context.job(),
                contact, plan, resume.getTemplateVersion());
        resumeValidator.validate(model, context.candidate(), context.job());
        if (!modelHasher.hash(model).equals(resume.getStructuredContentHash())) {
            throw new IllegalStateException("Stored resume facts do not match the structured hash");
        }
        return model;
    }

    private Map<DocumentFormat, byte[]> renderResume(ResumeDocumentModel model,
                                                     Set<DocumentFormat> formats) {
        Map<DocumentFormat, byte[]> rendered = new EnumMap<>(DocumentFormat.class);
        if (formats.contains(DocumentFormat.DOCX)) rendered.put(DocumentFormat.DOCX,
                resumeDocx.render(model));
        if (formats.contains(DocumentFormat.PDF)) rendered.put(DocumentFormat.PDF,
                resumePdf.render(model));
        return rendered;
    }

    private Map<DocumentFormat, byte[]> renderCover(CoverNoteDocumentModel model,
                                                    Set<DocumentFormat> formats) {
        Map<DocumentFormat, byte[]> rendered = new EnumMap<>(DocumentFormat.class);
        if (formats.contains(DocumentFormat.DOCX)) rendered.put(DocumentFormat.DOCX,
                coverDocx.render(model));
        if (formats.contains(DocumentFormat.PDF)) rendered.put(DocumentFormat.PDF,
                coverPdf.render(model));
        return rendered;
    }

    private List<String> resumeExpectedText(ResumeDocumentModel model) {
        List<String> values = new ArrayList<>(List.of(model.fullName(), model.contact().email(),
                "SUMMARY", "TECHNICAL SKILLS", "PROJECTS", "EDUCATION", "LANGUAGES"));
        values.add(model.skills().getFirst().displayName());
        values.add(model.projects().getFirst().bullets().getFirst().verifiedText());
        return values;
    }

    private List<String> coverExpectedText(CoverNoteDocumentModel model) {
        return List.of(model.candidateName(), model.contact().email(), "Dear Hiring Team",
                model.roleTitle(), model.paragraphs().getFirst());
    }

    private boolean artifactsValid(ResumeVersion value) {
        if (value == null || value.getRenderStatus() != DocumentRenderStatus.COMPLETED) return false;
        return value.requestedFormatSet().stream().allMatch(format ->
                storage.isValid(value.artifact(format), format));
    }

    private boolean artifactsValid(CoverNote value) {
        if (value == null || value.getRenderStatus() != DocumentRenderStatus.COMPLETED) return false;
        return value.requestedFormatSet().stream().allMatch(format ->
                storage.isValid(value.artifact(format), format));
    }

    private ResumeVersion awaitCompletedResume(String cacheKey) {
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            ResumeVersion value = transactions.execute(status ->
                    resumes.findByCacheKey(cacheKey).orElse(null));
            if (value != null && value.getRenderStatus() == DocumentRenderStatus.COMPLETED) {
                return value;
            }
            if (value != null && value.getRenderStatus() == DocumentRenderStatus.FAILED) return null;
            if (!pauseForWinner()) return null;
        }
        return null;
    }

    private CoverNote awaitCompletedCoverNote(String cacheKey) {
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            CoverNote value = transactions.execute(status ->
                    coverNotes.findByCacheKey(cacheKey).orElse(null));
            if (value != null && value.getRenderStatus() == DocumentRenderStatus.COMPLETED) {
                return value;
            }
            if (value != null && value.getRenderStatus() == DocumentRenderStatus.FAILED) return null;
            if (!pauseForWinner()) return null;
        }
        return null;
    }

    private boolean pauseForWinner() {
        try {
            Thread.sleep(25);
            return true;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private void failResume(long id, DocumentFailureCategory category) {
        try {
            transactions.executeWithoutResult(status -> resumes.findByIdForUpdate(id)
                    .ifPresent(value -> value.fail(category, clock.instant())));
        } catch (RuntimeException ignored) {
            // The public result remains sanitized; stale recovery handles a retained in-progress row.
        }
    }

    private void failCover(long id, DocumentFailureCategory category) {
        try {
            transactions.executeWithoutResult(status -> coverNotes.findByIdForUpdate(id)
                    .ifPresent(value -> value.fail(category, clock.instant())));
        } catch (RuntimeException ignored) {
            // The public result remains sanitized; orphan cleanup handles retained generated files.
        }
    }

    private DocumentGenerationResult result(DocumentGenerationStatus status, Context context,
                                            ResumeWork resume, CoverNote cover,
                                            LlmFailureCategory coverReason) {
        LlmFailureCategory reason = resume.fallbackReason() != null
                ? resume.fallbackReason() : coverReason;
        return new DocumentGenerationResult(status, context.job().jobId(), resume.resume().getId(),
                cover == null ? null : cover.getId(),
                previews.build(resume.model(), properties.maxPreviewCharacters()),
                resume.model().changeSummary(), resume.model().interviewClaims().stream()
                .map(ResumeDocumentModel.InterviewClaim::statement).toList(),
                resume.fallbackUsed() || cover != null && cover.isFallbackUsed(), reason);
    }

    private DocumentGenerationStatus status(LlmFailureCategory fallbackReason) {
        if (fallbackReason == LlmFailureCategory.BUDGET_EXHAUSTED
                || fallbackReason == LlmFailureCategory.CALL_LIMIT) {
            return DocumentGenerationStatus.BUDGET_EXCEEDED;
        }
        return fallbackReason == null ? DocumentGenerationStatus.CREATED
                : DocumentGenerationStatus.FALLBACK;
    }

    private ResumeMetadataView toView(ResumeVersion value) {
        return new ResumeMetadataView(value.getId(), value.getJob().getId(),
                value.getProfileVersion(), value.getSourceAnalysis() == null
                ? null : value.getSourceAnalysis().getId(), value.getSelectedTitle(),
                value.getSummary(), value.getPlainTextPreview(), lines(value.getChangeSummary()),
                lines(value.getInterviewClaims()), value.getTemplateVersion(),
                value.getGenerationMethod(), value.isFallbackUsed(), value.getRenderStatus(),
                value.getDocxPath() != null, value.getDocxHash(), value.getDocxSize(),
                value.getPdfPath() != null, value.getPdfHash(), value.getPdfSize(),
                value.getPdfPageCount(),
                value.getGeneratedAt());
    }

    private CoverNoteMetadataView toView(CoverNote value) {
        return new CoverNoteMetadataView(value.getId(), value.getJob().getId(),
                value.getProfileVersion(), value.getResumeVersion().getId(),
                value.getSourceAnalysis() == null ? null : value.getSourceAnalysis().getId(),
                value.getContent(), value.getFactReferences().stream()
                .map(reference -> reference.getFactType().name() + ":" + reference.getFactKey()).toList(),
                value.getTemplateVersion(), value.getGenerationMethod(), value.isFallbackUsed(),
                value.getRenderStatus(), value.getDocxPath() != null, value.getDocxHash(),
                value.getDocxSize(), value.getPdfPath() != null, value.getPdfHash(),
                value.getPdfSize(), value.getPdfPageCount(), value.getGeneratedAt());
    }

    private DocumentDownload download(DocumentArtifactMetadata metadata, DocumentFormat format,
                                      String baseName) {
        byte[] bytes = storage.read(metadata, format);
        String type = format == DocumentFormat.DOCX
                ? "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
                : "application/pdf";
        return new DocumentDownload(bytes, type, baseName + "." + format.name().toLowerCase());
    }

    private void requireCompleted(DocumentRenderStatus status) {
        if (status != DocumentRenderStatus.COMPLETED) {
            throw new IllegalArgumentException("Document is not completed");
        }
    }

    private List<String> lines(String value) {
        return value == null || value.isBlank() ? List.of() : List.of(value.split("\\R"));
    }

    private <K, V> Map<K, V> mapById(List<V> values, Function<V, K> key) {
        return values.stream().collect(Collectors.toMap(key, Function.identity()));
    }

    private <K, V> V required(Map<K, V> values, K key) {
        V value = values.get(key);
        if (value == null) throw new IllegalStateException("Validated fact reference is missing");
        return value;
    }

    private enum ClaimState { CLAIMED, CACHED, CONCURRENT }
    private record Context(CandidateDocumentFacts candidate, JobDocumentFacts job) { }
    private record ResumeClaim(ResumeVersion resume, ClaimState state) { }
    private record CoverClaim(CoverNote coverNote, ClaimState state) { }
    private record ResumeWork(ResumeVersion resume, ResumeDocumentModel model,
                              boolean created, boolean fallbackUsed,
                              LlmFailureCategory fallbackReason) { }
}

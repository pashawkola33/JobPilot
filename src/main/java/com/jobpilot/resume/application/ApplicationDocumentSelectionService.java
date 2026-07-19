package com.jobpilot.resume.application;

import com.jobpilot.applications.domain.ApplicationRecord;
import com.jobpilot.applications.repository.ApplicationRepository;
import com.jobpilot.resume.domain.CoverNote;
import com.jobpilot.resume.domain.DocumentFormat;
import com.jobpilot.resume.domain.DocumentRenderStatus;
import com.jobpilot.resume.domain.ResumeVersion;
import com.jobpilot.resume.repository.CoverNoteRepository;
import com.jobpilot.resume.repository.ResumeVersionRepository;
import com.jobpilot.resume.storage.DocumentArtifactStorage;
import java.time.Clock;
import java.util.Objects;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class ApplicationDocumentSelectionService {
    private final ApplicationRepository applications;
    private final ResumeVersionRepository resumes;
    private final CoverNoteRepository coverNotes;
    private final DocumentArtifactStorage storage;
    private final Clock clock;
    private final TransactionTemplate transactions;

    public ApplicationDocumentSelectionService(ApplicationRepository applications,
                                               ResumeVersionRepository resumes,
                                               CoverNoteRepository coverNotes,
                                               DocumentArtifactStorage storage, Clock clock,
                                               PlatformTransactionManager transactionManager) {
        this.applications = applications;
        this.resumes = resumes;
        this.coverNotes = coverNotes;
        this.storage = storage;
        this.clock = clock;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    public ApplicationDocumentSelectionResult select(long jobId, long resumeVersionId,
                                                     Long coverNoteId) {
        SelectionSnapshot snapshot = transactions.execute(status -> snapshot(
                jobId, resumeVersionId, coverNoteId));
        validateArtifacts(snapshot.resume());
        if (snapshot.coverNote() != null) validateArtifacts(snapshot.coverNote());

        try {
            return transactions.execute(status -> {
                ApplicationRecord application = applications.findByJobIdForUpdate(jobId)
                        .orElseThrow(() -> failure(
                                ApplicationDocumentSelectionException.Category.APPLICATION_NOT_FOUND,
                                "Application record was not found."));
                ResumeVersion resume = resumes.findByIdForUpdate(resumeVersionId)
                        .orElseThrow(() -> failure(
                                ApplicationDocumentSelectionException.Category.RESUME_NOT_FOUND,
                                "Resume version was not found."));
                CoverNote note = coverNoteId == null ? null : coverNotes.findByIdForUpdate(coverNoteId)
                        .orElseThrow(() -> failure(
                                ApplicationDocumentSelectionException.Category.COVER_NOTE_NOT_FOUND,
                                "Cover note was not found."));
                validateCompatibility(jobId, resume, note);
                boolean changed = application.getResumeVersion() == null
                        || !Objects.equals(application.getResumeVersion().getId(), resume.getId())
                        || !Objects.equals(application.getCoverNote() == null
                        ? null : application.getCoverNote().getId(), note == null ? null : note.getId());
                if (changed) {
                    application.selectDocuments(resume, note, clock.instant());
                    applications.saveAndFlush(application);
                }
                return new ApplicationDocumentSelectionResult(application.getId(), jobId,
                        resume.getId(), note == null ? null : note.getId(),
                        application.getStatus(), changed);
            });
        } catch (ApplicationDocumentSelectionException known) {
            throw known;
        } catch (DataIntegrityViolationException | OptimisticLockingFailureException conflict) {
            throw failure(ApplicationDocumentSelectionException.Category.CONFLICT,
                    "Application document selection changed concurrently.");
        }
    }

    private SelectionSnapshot snapshot(long jobId, long resumeId, Long coverId) {
        if (!applications.findByJobId(jobId).isPresent()) {
            throw failure(ApplicationDocumentSelectionException.Category.APPLICATION_NOT_FOUND,
                    "Application record was not found.");
        }
        ResumeVersion resume = resumes.findById(resumeId).orElseThrow(() -> failure(
                ApplicationDocumentSelectionException.Category.RESUME_NOT_FOUND,
                "Resume version was not found."));
        CoverNote cover = coverId == null ? null : coverNotes.findById(coverId).orElseThrow(() -> failure(
                ApplicationDocumentSelectionException.Category.COVER_NOTE_NOT_FOUND,
                "Cover note was not found."));
        validateCompatibility(jobId, resume, cover);
        return new SelectionSnapshot(resume, cover);
    }

    private void validateCompatibility(long jobId, ResumeVersion resume, CoverNote cover) {
        if (!resume.getJob().getId().equals(jobId)
                || cover != null && !cover.getJob().getId().equals(jobId)) {
            throw failure(ApplicationDocumentSelectionException.Category.WRONG_JOB,
                    "Selected documents must belong to the application job.");
        }
        if (resume.getRenderStatus() != DocumentRenderStatus.COMPLETED
                || cover != null && cover.getRenderStatus() != DocumentRenderStatus.COMPLETED) {
            throw failure(ApplicationDocumentSelectionException.Category.ARTIFACT_INVALID,
                    "Only completed document artifacts may be selected.");
        }
        if (cover != null && (resume.getProfileVersion() != cover.getProfileVersion()
                || !resume.getCandidateProfile().getId().equals(cover.getCandidateProfile().getId()))) {
            throw failure(ApplicationDocumentSelectionException.Category.INCOMPATIBLE_PROFILE,
                    "Resume and cover note use incompatible profile versions.");
        }
        if (cover != null && (cover.getResumeVersion() == null
                || !cover.getResumeVersion().getId().equals(resume.getId()))) {
            throw failure(ApplicationDocumentSelectionException.Category.INCOMPATIBLE_COVER_NOTE,
                    "Cover note does not reference the selected resume version.");
        }
    }

    private void validateArtifacts(ResumeVersion value) {
        for (DocumentFormat format : value.requestedFormatSet()) {
            if (!storage.isValid(value.artifact(format), format)) {
                throw failure(ApplicationDocumentSelectionException.Category.ARTIFACT_INVALID,
                        "Selected resume artifact is missing or invalid.");
            }
        }
    }

    private void validateArtifacts(CoverNote value) {
        for (DocumentFormat format : value.requestedFormatSet()) {
            if (!storage.isValid(value.artifact(format), format)) {
                throw failure(ApplicationDocumentSelectionException.Category.ARTIFACT_INVALID,
                        "Selected cover-note artifact is missing or invalid.");
            }
        }
    }

    private ApplicationDocumentSelectionException failure(
            ApplicationDocumentSelectionException.Category category, String message) {
        return new ApplicationDocumentSelectionException(category, message);
    }

    private record SelectionSnapshot(ResumeVersion resume, CoverNote coverNote) { }
}

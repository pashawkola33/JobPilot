package com.jobpilot.telegram.commands;

import com.jobpilot.applications.application.ApplicationTrackerService;
import com.jobpilot.applications.application.ApplicationTrackingException;
import com.jobpilot.applications.domain.ApplicationStatus;
import com.jobpilot.applications.domain.ApplicationStatusChangeSource;
import com.jobpilot.llm.application.JobAnalysisService;
import com.jobpilot.llm.domain.JobAnalysisResult;
import com.jobpilot.manualurl.application.ManualJobUrlService;
import com.jobpilot.resume.application.ApplicationDocumentSelectionException;
import com.jobpilot.resume.application.ApplicationDocumentSelectionService;
import com.jobpilot.resume.application.GenerateDocumentsCommand;
import com.jobpilot.resume.application.ResumeGenerationService;
import com.jobpilot.resume.application.DocumentGenerationResult;
import com.jobpilot.observability.OperationalCounter;
import com.jobpilot.observability.OperationalCounters;
import java.util.NoSuchElementException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

@Component
public class TelegramCommandDispatcher {
    private final ApplicationTrackerService tracker;
    private final ManualJobUrlService manualJobs;
    private final TelegramMessageRenderer renderer;
    private final JobAnalysisService analyses;
    private final ResumeGenerationService documents;
    private final ApplicationDocumentSelectionService selection;
    private final OperationalCounters counters;

    @Autowired
    public TelegramCommandDispatcher(ApplicationTrackerService tracker,
                                     ManualJobUrlService manualJobs,
                                     TelegramMessageRenderer renderer,
                                     JobAnalysisService analyses,
                                     ResumeGenerationService documents,
                                     ApplicationDocumentSelectionService selection,
                                     OperationalCounters counters) {
        this.tracker = tracker;
        this.manualJobs = manualJobs;
        this.renderer = renderer;
        this.analyses = analyses;
        this.documents = documents;
        this.selection = selection;
        this.counters = counters;
    }

    public TelegramCommandDispatcher(ApplicationTrackerService tracker,
                                     ManualJobUrlService manualJobs,
                                     TelegramMessageRenderer renderer) {
        this(tracker, manualJobs, renderer, null, null, null, new OperationalCounters());
    }

    public TelegramCommandResult dispatch(TelegramCommand command,
                                          ApplicationStatusChangeSource source) {
        try {
            String html = switch (command.kind()) {
                case HELP -> renderer.help();
                case ADD -> renderer.manual(manualJobs.submit(command.text()));
                case SAVE -> renderer.mutation(tracker.transition(command.jobId(), ApplicationStatus.SAVED,
                        null, null, source));
                case APPLIED -> renderer.mutation(tracker.transition(command.jobId(), ApplicationStatus.APPLIED,
                        null, null, source));
                case INTERVIEW -> renderer.mutation(tracker.transition(command.jobId(), ApplicationStatus.INTERVIEW,
                        command.instant(), null, source));
                case REJECTED -> renderer.mutation(tracker.transition(command.jobId(), ApplicationStatus.REJECTED,
                        null, command.text(), source));
                case OFFER -> renderer.mutation(tracker.transition(command.jobId(), ApplicationStatus.OFFER,
                        null, null, source));
                case WITHDRAW -> renderer.mutation(tracker.transition(command.jobId(), ApplicationStatus.WITHDRAWN,
                        null, null, source));
                case FOLLOWUP -> renderer.mutation(
                        tracker.changeFollowUpDate(command.jobId(), command.date()));
                case NOTE -> renderer.mutation(tracker.changeNotes(command.jobId(), command.text()));
                case STATUS -> renderer.application(tracker.findByJobId(command.jobId()));
                case APPLICATIONS -> renderer.applications(tracker.list(command.statusFilter()));
                case HISTORY -> renderer.history(tracker.history(command.jobId()));
                case ANALYZE -> analyze(command);
                case DOCUMENTS -> generateDocuments(command);
                case RESUMES -> renderer.resumes(documents.resumesForJob(command.jobId()));
                case COVER_NOTES -> renderer.coverNotes(documents.coverNotesForJob(command.jobId()));
                case SELECT_DOCUMENTS -> renderer.selection(selection.select(command.jobId(),
                        command.resumeVersionId(), command.coverNoteId()));
            };
            return new TelegramCommandResult(html, callbackSummary(command, false));
        } catch (ApplicationTrackingException expected) {
            return new TelegramCommandResult(renderer.error(expected.getMessage()), expected.getMessage());
        } catch (ApplicationDocumentSelectionException expected) {
            return new TelegramCommandResult(renderer.error(expected.getMessage()),
                    "Document selection failed");
        } catch (NoSuchElementException expected) {
            return new TelegramCommandResult(renderer.error("Requested metadata was not found."),
                    "Not found");
        } catch (DataAccessException expected) {
            return new TelegramCommandResult(renderer.error(
                    "The operation could not be completed because persisted state changed."),
                    "Temporary conflict");
        }
    }

    private String analyze(TelegramCommand command) {
        JobAnalysisResult result = analyses.analyze(command.jobId(), true);
        switch (result.status()) {
            case CREATED -> counters.increment(OperationalCounter.ANALYSES_CREATED);
            case CACHED -> counters.increment(OperationalCounter.ANALYSES_CACHED);
            case FALLBACK, DISABLED -> counters.increment(OperationalCounter.ANALYSES_FALLBACK);
            case BUDGET_EXCEEDED -> counters.increment(OperationalCounter.ANALYSES_BUDGET_REJECTED);
            case JOB_NOT_FOUND, PROFILE_NOT_FOUND, PROVIDER_FAILED, INVALID_PROVIDER_RESPONSE ->
                    counters.increment(OperationalCounter.ANALYSES_FAILED);
        }
        return renderer.analysis(result);
    }

    private String generateDocuments(TelegramCommand command) {
        DocumentGenerationResult result = documents.generate(command.jobId(),
                new GenerateDocumentsCommand(
                        command.documentScope() == TelegramCommand.DocumentScope.ALL,
                        command.documentFormats(), true));
        switch (result.status()) {
            case CREATED -> counters.increment(OperationalCounter.DOCUMENTS_CREATED);
            case CACHED -> counters.increment(OperationalCounter.DOCUMENTS_CACHED);
            case FALLBACK, BUDGET_EXCEEDED -> counters.increment(OperationalCounter.DOCUMENTS_FALLBACK);
            case DISABLED, JOB_NOT_FOUND, PROFILE_NOT_FOUND, ANALYSIS_FAILED, GENERATION_FAILED,
                    RENDER_FAILED, ARTIFACT_INVALID, STORAGE_FAILED ->
                    counters.increment(OperationalCounter.DOCUMENTS_FAILED);
        }
        return renderer.documents(result);
    }

    private String callbackSummary(TelegramCommand command, boolean unused) {
        return switch (command.kind()) {
            case SAVE -> "Saved";
            case APPLIED -> "Marked as applied";
            case SELECT_DOCUMENTS -> "Documents selected";
            case ANALYZE -> "Analysis complete";
            case DOCUMENTS -> "Documents complete";
            default -> "Done";
        };
    }
}

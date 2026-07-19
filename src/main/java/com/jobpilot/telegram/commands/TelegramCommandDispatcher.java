package com.jobpilot.telegram.commands;

import com.jobpilot.applications.application.ApplicationTrackerService;
import com.jobpilot.applications.application.ApplicationTrackingException;
import com.jobpilot.applications.domain.ApplicationStatus;
import com.jobpilot.applications.domain.ApplicationStatusChangeSource;
import com.jobpilot.manualurl.application.ManualJobUrlService;
import org.springframework.stereotype.Component;

@Component
public class TelegramCommandDispatcher {
    private final ApplicationTrackerService tracker;
    private final ManualJobUrlService manualJobs;
    private final TelegramMessageRenderer renderer;

    public TelegramCommandDispatcher(ApplicationTrackerService tracker,
                                     ManualJobUrlService manualJobs,
                                     TelegramMessageRenderer renderer) {
        this.tracker = tracker;
        this.manualJobs = manualJobs;
        this.renderer = renderer;
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
            };
            return new TelegramCommandResult(html, callbackSummary(command, false));
        } catch (ApplicationTrackingException expected) {
            return new TelegramCommandResult(renderer.error(expected.getMessage()), expected.getMessage());
        }
    }

    private String callbackSummary(TelegramCommand command, boolean unused) {
        return switch (command.kind()) {
            case SAVE -> "Saved";
            case APPLIED -> "Marked as applied";
            default -> "Done";
        };
    }
}

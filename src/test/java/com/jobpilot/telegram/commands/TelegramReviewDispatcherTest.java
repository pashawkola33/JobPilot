package com.jobpilot.telegram.commands;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jobpilot.applications.domain.ApplicationStatusChangeSource;
import com.jobpilot.config.JobPilotProperties;
import com.jobpilot.jobreview.application.JobDetailView;
import com.jobpilot.jobreview.application.JobQueue;
import com.jobpilot.jobreview.application.JobQueueItem;
import com.jobpilot.jobreview.application.JobQueuePage;
import com.jobpilot.jobreview.application.JobReviewException;
import com.jobpilot.jobreview.application.JobReviewService;
import com.jobpilot.jobreview.application.JobReviewStats;
import com.jobpilot.jobreview.application.WorkflowView;
import com.jobpilot.jobreview.domain.WorkflowStatus;
import com.jobpilot.jobs.domain.ScreeningDisposition;
import com.jobpilot.support.TestProperties;
import com.jobpilot.telegram.review.TelegramCallbackData.Action;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TelegramReviewDispatcherTest {
    private static final Instant WHEN = Instant.parse("2026-08-04T09:00:00Z");

    private JobReviewService review;
    private TelegramCommandDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        review = mock(JobReviewService.class);
        JobPilotProperties properties = TestProperties.create(
                new JobPilotProperties.Telegram("1234:obviously-fake", true, List.of("777"),
                        true, true, 5, 500));
        dispatcher = new TelegramCommandDispatcher(null, null, new TelegramMessageRenderer(),
                review, properties);
    }

    private TelegramCommandResult run(TelegramCommand command) {
        return dispatcher.dispatch(command, ApplicationStatusChangeSource.TELEGRAM_COMMAND);
    }

    private JobQueueItem item(long id) {
        return new JobQueueItem(id, "Java Intern", "Acme", "Bucharest",
                ScreeningDisposition.MATCH, 90, WorkflowStatus.UNREVIEWED, "greenhouse",
                "acme", WHEN, "https://example.test/jobs/" + id);
    }

    private JobDetailView detail(long id) {
        return new JobDetailView(id, "Java Intern", "Acme", "Bucharest",
                "https://example.test/jobs/" + id, 90, ScreeningDisposition.MATCH, null, null,
                List.of(), "greenhouse", "acme", WHEN, WHEN, WorkflowStatus.UNREVIEWED, null, null);
    }

    @Test
    void rendersEachQueueWithTheConfiguredPageSize() {
        for (JobQueue queue : JobQueue.values()) {
            when(review.page(queue, 0, 5)).thenReturn(
                    new JobQueuePage(queue, List.of(item(1)), 0, 5, 1));

            TelegramCommandResult result = run(TelegramCommand.queue(queue, 0));

            assertThat(result.html()).contains(queue.name()).contains("Java Intern");
            verify(review).page(queue, 0, 5);
        }
    }

    @Test
    void offersANextButtonOnlyWhileMorePagesRemain() {
        when(review.page(JobQueue.MATCHES, 0, 5))
                .thenReturn(new JobQueuePage(JobQueue.MATCHES, List.of(item(1)), 0, 5, 40));

        assertThat(run(TelegramCommand.queue(JobQueue.MATCHES, 0)).buttons())
                .singleElement().satisfies(row ->
                        assertThat(row.getFirst()).containsEntry("callback_data", "jn:m:1"));
    }

    @Test
    void rendersOneVacancyWithItsFullActionKeyboard() {
        when(review.detail(7L)).thenReturn(detail(7));

        TelegramCommandResult result = run(new TelegramCommand(
                TelegramCommand.Kind.JOB, 7L, null, null, null, null));

        assertThat(result.html()).contains("Job ID: 7");
        assertThat(result.buttons().stream().flatMap(List::stream)
                .map(button -> button.get("text")))
                .containsExactly("Open vacancy", "Save", "Applied", "Dismiss", "Reset");
    }

    @Test
    void rendersQueueStatistics() {
        when(review.stats()).thenReturn(new JobReviewStats(3, 70, 5, 2, 4));

        assertThat(run(TelegramCommand.simple(TelegramCommand.Kind.STATS)).html())
                .contains("Unreviewed MATCH: 3", "Unreviewed REVIEW: 70", "Saved: 5",
                        "Applied: 2", "Dismissed: 4");
    }

    @Test
    void rendersStartAndHelp() {
        assertThat(run(TelegramCommand.simple(TelegramCommand.Kind.START)).html())
                .contains("JobPilot review bot");
        assertThat(run(TelegramCommand.simple(TelegramCommand.Kind.HELP)).html())
                .contains("/matches", "/review", "/saved", "/applied", "/stats", "/job",
                        "/note", "/reset");
    }

    @Test
    void writesAndClearsTheWorkflowNote() {
        when(review.note(4L, "call them back"))
                .thenReturn(new WorkflowView(4, WorkflowStatus.SAVED, "call them back",
                        null, WHEN, true));
        when(review.note(4L, null))
                .thenReturn(new WorkflowView(4, WorkflowStatus.SAVED, null, null, WHEN, true));

        assertThat(run(new TelegramCommand(TelegramCommand.Kind.NOTE, 4L, "call them back",
                null, null, null)).html()).contains("call them back");
        assertThat(run(new TelegramCommand(TelegramCommand.Kind.NOTE, 4L, null,
                null, null, null)).html()).doesNotContain("Note:");
    }

    @Test
    void resetsAVacancyBackToUnreviewed() {
        when(review.reset(4L)).thenReturn(
                new WorkflowView(4, WorkflowStatus.UNREVIEWED, null, null, null, true));

        assertThat(run(new TelegramCommand(TelegramCommand.Kind.RESET, 4L, null,
                null, null, null)).html()).contains("reset to unreviewed");
    }

    @Test
    void reportsAnAlreadyAppliedActionAsUnchanged() {
        when(review.save(4L)).thenReturn(
                new WorkflowView(4, WorkflowStatus.SAVED, null, null, WHEN, false));

        TelegramCommandResult result = dispatcher.applyWorkflow(Action.SAVE, 4L);

        assertThat(result.html()).contains("Already");
        assertThat(result.callbackText()).isEqualTo("Saved");
    }

    @Test
    void routesEveryCallbackActionToItsWorkflowMethod() {
        when(review.save(anyLong())).thenReturn(view(WorkflowStatus.SAVED));
        when(review.applied(anyLong())).thenReturn(view(WorkflowStatus.APPLIED));
        when(review.dismiss(anyLong())).thenReturn(view(WorkflowStatus.DISMISSED));
        when(review.reset(anyLong())).thenReturn(view(WorkflowStatus.UNREVIEWED));

        assertThat(dispatcher.applyWorkflow(Action.SAVE, 1).callbackText()).isEqualTo("Saved");
        assertThat(dispatcher.applyWorkflow(Action.APPLIED, 1).callbackText())
                .isEqualTo("Marked as applied");
        assertThat(dispatcher.applyWorkflow(Action.DISMISS, 1).callbackText())
                .isEqualTo("Dismissed");
        assertThat(dispatcher.applyWorkflow(Action.RESET, 1).callbackText()).isEqualTo("Reset");
    }

    @Test
    void reportsAMissingVacancyWithoutRevealingWhetherItEverExisted() {
        when(review.detail(anyLong())).thenThrow(new JobReviewException(
                JobReviewException.Category.JOB_NOT_FOUND,
                "That vacancy is not in the review queue."));

        TelegramCommandResult result = run(new TelegramCommand(
                TelegramCommand.Kind.JOB, 99999L, null, null, null, null));

        assertThat(result.html()).contains("not in the review queue")
                .doesNotContain("99999").doesNotContain("select").doesNotContain("job_workflow");
        assertThat(result.callbackText()).isEqualTo("Not found");
    }

    @Test
    void surfacesAnInvalidWorkflowRequestAsSafeText() {
        when(review.note(eq(4L), eq("x"))).thenThrow(new JobReviewException(
                JobReviewException.Category.INVALID_WORKFLOW, "Note must contain at most 1000 characters."));

        TelegramCommandResult result = run(new TelegramCommand(
                TelegramCommand.Kind.NOTE, 4L, "x", null, null, null));

        assertThat(result.html()).contains("at most 1000 characters");
        assertThat(result.callbackText()).isEqualTo("Rejected");
    }

    private WorkflowView view(WorkflowStatus status) {
        return new WorkflowView(1, status, null,
                status == WorkflowStatus.APPLIED ? WHEN : null, WHEN, true);
    }
}

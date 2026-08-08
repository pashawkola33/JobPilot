package com.jobpilot.miniapp.api;

import static com.jobpilot.miniapp.auth.TelegramInitDataFixture.BOT_TOKEN;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobpilot.applications.application.ApplicationTrackerService;
import com.jobpilot.applications.domain.ApplicationStatus;
import com.jobpilot.config.JobPilotProperties;
import com.jobpilot.jobreview.application.JobReviewException;
import com.jobpilot.jobreview.application.WorkflowView;
import com.jobpilot.jobreview.domain.WorkflowStatus;
import com.jobpilot.jobs.domain.ScreeningDisposition;
import com.jobpilot.miniapp.application.MiniAppSnapshotService;
import com.jobpilot.miniapp.application.MiniAppMutationException;
import com.jobpilot.miniapp.application.MiniAppOperation;
import com.jobpilot.miniapp.application.MiniAppWorkflowService;
import com.jobpilot.miniapp.auth.MiniAppAuthInterceptor;
import com.jobpilot.miniapp.auth.TelegramInitDataFixture;
import com.jobpilot.miniapp.auth.TelegramInitDataValidator;
import com.jobpilot.support.TestProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class MiniAppControllerTest {
    private static final long ALLOWED_USER = 4242L;
    private static final long OTHER_USER = 9999L;
    private static final Instant NOW = Instant.parse("2026-01-15T12:00:00Z");
    private static final long FRESH = NOW.minus(Duration.ofMinutes(5)).getEpochSecond();
    private static final String BASE = MiniAppController.BASE;
    private static final String MUTATION = "mutation-00000001";
    private static final String OTHER_MUTATION = "mutation-00000002";

    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final MiniAppSnapshotService snapshots = mock(MiniAppSnapshotService.class);
    private final MiniAppWorkflowService workflows = mock(MiniAppWorkflowService.class);

    private MockMvc mvcWith(JobPilotProperties properties) {
        ObjectMapper objectMapper = new ObjectMapper();
        MiniAppAuthInterceptor interceptor = new MiniAppAuthInterceptor(properties,
                new TelegramInitDataValidator(properties, objectMapper, clock), objectMapper);
        return MockMvcBuilders.standaloneSetup(new MiniAppController(snapshots, workflows))
                .addInterceptors(interceptor).build();
    }

    private MockMvc enabled() {
        return mvcWith(TestProperties.create(new JobPilotProperties.Telegram(BOT_TOKEN, ""),
                new JobPilotProperties.MiniApp(true, List.of(Long.toString(ALLOWED_USER)),
                        Duration.ofHours(1))));
    }

    private static String initDataFor(long userId) {
        return TelegramInitDataFixture.launch(userId, FRESH).signed();
    }

    // ------------------------------------------------------------------ feature gate

    @Test
    void servesNothingWhileTheFeatureIsDisabled() throws Exception {
        MockMvc disabled = mvcWith(TestProperties.create(
                new JobPilotProperties.Telegram(BOT_TOKEN, "")));

        disabled.perform(get(BASE + "/snapshot")
                        .header(MiniAppAuthInterceptor.HEADER, initDataFor(ALLOWED_USER)))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.category").value(MiniAppApiError.DISABLED));

        // Not merely hidden: the read never runs.
        verifyNoInteractions(snapshots);
    }

    @Test
    void refusesMutationsWhileTheFeatureIsDisabled() throws Exception {
        MockMvc disabled = mvcWith(TestProperties.create(
                new JobPilotProperties.Telegram(BOT_TOKEN, "")));

        disabled.perform(put(BASE + "/jobs/7/workflow")
                        .header(MiniAppAuthInterceptor.HEADER, initDataFor(ALLOWED_USER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("SAVED")))
                .andExpect(status().isServiceUnavailable());

        verifyNoInteractions(workflows);
    }

    // ---------------------------------------------------------------- authentication

    @Test
    void rejectsAMissingInitDataHeader() throws Exception {
        enabled().perform(get(BASE + "/snapshot"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.category").value(MiniAppApiError.UNAUTHENTICATED));

        verifyNoInteractions(snapshots);
    }

    @Test
    void rejectsInvalidInitData() throws Exception {
        enabled().perform(get(BASE + "/snapshot")
                        .header(MiniAppAuthInterceptor.HEADER, "user=%7B%22id%22%3A1%7D&hash=deadbeef"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.category").value(MiniAppApiError.INVALID_AUTH));

        verifyNoInteractions(snapshots);
    }

    @Test
    void reportsExpiredAuthenticationSeparatelySoTheClientCanPromptAReopen() throws Exception {
        long stale = NOW.minus(Duration.ofHours(3)).getEpochSecond();

        enabled().perform(get(BASE + "/snapshot").header(MiniAppAuthInterceptor.HEADER,
                        TelegramInitDataFixture.launch(ALLOWED_USER, stale).signed()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.category").value(MiniAppApiError.EXPIRED_AUTH));
    }

    @Test
    void doesNotRevealWhichValidationStepRejectedThePayload() throws Exception {
        String tampered = TelegramInitDataFixture.launch(ALLOWED_USER, FRESH)
                .signedThenTampered("user", "{\"id\":" + OTHER_USER + "}");
        String malformed = "not-a-query-string";

        String tamperedBody = enabled().perform(get(BASE + "/snapshot")
                        .header(MiniAppAuthInterceptor.HEADER, tampered))
                .andExpect(status().isUnauthorized()).andReturn()
                .getResponse().getContentAsString();
        String malformedBody = enabled().perform(get(BASE + "/snapshot")
                        .header(MiniAppAuthInterceptor.HEADER, malformed))
                .andExpect(status().isUnauthorized()).andReturn()
                .getResponse().getContentAsString();

        assertThat(tamperedBody).isEqualTo(malformedBody);
    }

    // ----------------------------------------------------------------- authorization

    @Test
    void rejectsAValidTelegramUserThatIsNotOnTheAllowList() throws Exception {
        enabled().perform(get(BASE + "/snapshot")
                        .header(MiniAppAuthInterceptor.HEADER, initDataFor(OTHER_USER)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.category").value(MiniAppApiError.FORBIDDEN));

        // Authentic Telegram data is still not access: no job ever reaches this user.
        verifyNoInteractions(snapshots);
    }

    @Test
    void rejectsEveryUserWhenTheAllowListIsEmpty() throws Exception {
        // An empty list can only be constructed while disabled, which is itself the
        // deny-all: enabling with no ids fails at binding time.
        assertThat(JobPilotProperties.MiniApp.disabled().allows(ALLOWED_USER)).isFalse();
        assertThat(JobPilotProperties.MiniApp.disabled().allows(OTHER_USER)).isFalse();
    }

    @Test
    void refusesMutationsFromAnUnauthorizedUser() throws Exception {
        enabled().perform(put(BASE + "/jobs/7/workflow")
                        .header(MiniAppAuthInterceptor.HEADER, initDataFor(OTHER_USER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("DISMISSED")))
                .andExpect(status().isForbidden());

        verify(workflows, never()).change(anyString(), anyLong(), any());
    }

    // ------------------------------------------------------------------------- reads

    @Test
    void servesTheSnapshotToAnAuthorizedUser() throws Exception {
        when(snapshots.snapshot()).thenReturn(snapshot(
                List.of(new MiniAppSnapshot.MiniAppJob(7L, "Junior Java Engineer", "Northsail",
                        "Bucharest, Romania", "HYBRID", "JUNIOR", "Full-time", 91, "EXCELLENT_MATCH",
                        ScreeningDisposition.MATCH, WorkflowStatus.UNREVIEWED, "greenhouse",
                        NOW, "https://boards.example/jobs/7",
                        List.of("Spring Boot"), List.of("On-call"))),
                List.of(new MiniAppSnapshot.MiniAppApplication(7L, "Junior Java Engineer",
                        "Northsail", ApplicationStatus.APPLIED, "https://boards.example/jobs/7",
                        NOW, NOW, null, null))));

        enabled().perform(get(BASE + "/snapshot")
                        .header(MiniAppAuthInterceptor.HEADER, initDataFor(ALLOWED_USER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reviewQueue.items[0].id").value(7))
                .andExpect(jsonPath("$.reviewQueue.items[0].score").value(91))
                .andExpect(jsonPath("$.reviewQueue.items[0].band").value("EXCELLENT_MATCH"))
                .andExpect(jsonPath("$.reviewQueue.items[0].workflowStatus").value("UNREVIEWED"))
                .andExpect(jsonPath("$.applications.items[0].status").value("APPLIED"));
    }

    @Test
    void neverExposesTenantOrIngestionMetadataInTheSnapshot() throws Exception {
        when(snapshots.snapshot()).thenReturn(snapshot(
                List.of(new MiniAppSnapshot.MiniAppJob(7L, "Junior Java Engineer", "Northsail",
                        "Bucharest", "HYBRID", "JUNIOR", null, 91, "EXCELLENT_MATCH",
                        ScreeningDisposition.MATCH, WorkflowStatus.UNREVIEWED, "greenhouse",
                        NOW, null, List.of(), List.of())),
                List.of()));

        String body = enabled().perform(get(BASE + "/snapshot")
                        .header(MiniAppAuthInterceptor.HEADER, initDataFor(ALLOWED_USER)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain("tenant", "providerTenant", "externalId",
                "screeningReason", "note");
    }

    // --------------------------------------------------------------------- mutations

    @ParameterizedTest
    @CsvSource({"SAVED", "APPLIED", "DISMISSED", "UNREVIEWED"})
    void appliesEveryWorkflowActionThroughTheMiniAppOrchestrator(String status) throws Exception {
        WorkflowStatus target = WorkflowStatus.valueOf(status);
        when(workflows.change(MUTATION, 7L, target)).thenReturn(operation(target, true, "undo-1"));

        enabled().perform(put(BASE + "/jobs/7/workflow")
                        .header(MiniAppAuthInterceptor.HEADER, initDataFor(ALLOWED_USER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(status)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.jobId").value(7))
                .andExpect(jsonPath("$.status").value(status))
                .andExpect(jsonPath("$.changed").value(true))
                .andExpect(jsonPath("$.mutationId").value(MUTATION))
                .andExpect(jsonPath("$.mutationRevision").value(12))
                .andExpect(jsonPath("$.undoToken").value("undo-1"));

        verify(workflows).change(MUTATION, 7L, target);
    }

    /**
     * Correction A on the wire. A mutation is authoritative about the job it locked and about
     * nothing else, so shipping a global snapshot here — as P0-A did — would let a response
     * whose revision merely looks newer overwrite Review or Saved state that ingestion or a
     * Telegram command has already moved past it.
     */
    @Test
    void neverReturnsAGlobalSnapshotFromAMutation() throws Exception {
        when(workflows.change(MUTATION, 7L, WorkflowStatus.SAVED))
                .thenReturn(operation(WorkflowStatus.SAVED, true, "undo-1"));

        String payload = enabled().perform(put(BASE + "/jobs/7/workflow")
                        .header(MiniAppAuthInterceptor.HEADER, initDataFor(ALLOWED_USER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("SAVED")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.snapshot").doesNotExist())
                .andReturn().getResponse().getContentAsString();

        assertThat(payload).doesNotContain("reviewQueue", "workflowCounts", "applicationCounts");
        verifyNoInteractions(snapshots);
    }

    @Test
    void reversesThroughTheServerOwnedUndoEndpoint() throws Exception {
        when(workflows.undo(OTHER_MUTATION, "undo-1"))
                .thenReturn(new MiniAppOperation(OTHER_MUTATION, 13L, false, 7L,
                        WorkflowStatus.UNREVIEWED, true, null, null));

        enabled().perform(post(BASE + "/undo")
                        .header(MiniAppAuthInterceptor.HEADER, initDataFor(ALLOWED_USER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(undoBody(OTHER_MUTATION, "undo-1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UNREVIEWED"))
                // A reversal is never itself reversible, so no capability comes back.
                .andExpect(jsonPath("$.undoToken").doesNotExist());
    }

    @ParameterizedTest
    @CsvSource({"IDEMPOTENCY_CONFLICT", "UNDO_STALE"})
    void reportsProtocolConflictsAsTypedConflictsRatherThanFaults(String category)
            throws Exception {
        when(workflows.undo(OTHER_MUTATION, "undo-1")).thenThrow(category.equals("UNDO_STALE")
                ? MiniAppMutationException.undoStale()
                : MiniAppMutationException.idempotencyConflict());

        enabled().perform(post(BASE + "/undo")
                        .header(MiniAppAuthInterceptor.HEADER, initDataFor(ALLOWED_USER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(undoBody(OTHER_MUTATION, "undo-1")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.category").value(category));
    }

    @ParameterizedTest
    @CsvSource({"'{\"status\":\"SAVED\"}'", "'{\"status\":\"SAVED\",\"mutationId\":\"\"}'",
            "'{\"status\":\"SAVED\",\"mutationId\":\"short\"}'",
            "'{\"status\":\"SAVED\",\"mutationId\":\"has spaces\"}'"})
    void rejectsAnUnusableMutationId(String payload) throws Exception {
        enabled().perform(put(BASE + "/jobs/7/workflow")
                        .header(MiniAppAuthInterceptor.HEADER, initDataFor(ALLOWED_USER))
                        .contentType(MediaType.APPLICATION_JSON).content(payload))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(workflows);
    }

    @Test
    void reportsARepeatedActionAsUnchangedRatherThanFailing() throws Exception {
        // No change means nothing to reverse, so no undo capability is offered.
        when(workflows.change(MUTATION, 7L, WorkflowStatus.SAVED))
                .thenReturn(operation(WorkflowStatus.SAVED, false, null));

        enabled().perform(put(BASE + "/jobs/7/workflow")
                        .header(MiniAppAuthInterceptor.HEADER, initDataFor(ALLOWED_USER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("SAVED")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.changed").value(false))
                .andExpect(jsonPath("$.undoToken").doesNotExist());
    }

    @ParameterizedTest
    @CsvSource({"'{\"status\":\"NONSENSE\",\"mutationId\":\"mutation-00000001\"}'", "'{}'",
            "'{\"status\":null,\"mutationId\":\"mutation-00000001\"}'"})
    void rejectsAnUnusableStatus(String payload) throws Exception {
        enabled().perform(put(BASE + "/jobs/7/workflow")
                        .header(MiniAppAuthInterceptor.HEADER, initDataFor(ALLOWED_USER))
                        .contentType(MediaType.APPLICATION_JSON).content(payload))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(workflows);
    }

    @Test
    void reportsAMissingJobAsNotFound() throws Exception {
        when(workflows.change(MUTATION, 404L, WorkflowStatus.SAVED)).thenThrow(new JobReviewException(
                JobReviewException.Category.JOB_NOT_FOUND,
                "That vacancy is not in the review queue."));

        enabled().perform(put(BASE + "/jobs/404/workflow")
                        .header(MiniAppAuthInterceptor.HEADER, initDataFor(ALLOWED_USER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("SAVED")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.category").value(MiniAppApiError.JOB_NOT_FOUND));
    }

    @Test
    void reportsAnInvalidWorkflowChangeAsAConflict() throws Exception {
        when(workflows.change(MUTATION, 7L, WorkflowStatus.APPLIED)).thenThrow(new JobReviewException(
                JobReviewException.Category.INVALID_WORKFLOW, "Note must contain at most 1000 characters."));

        enabled().perform(put(BASE + "/jobs/7/workflow")
                        .header(MiniAppAuthInterceptor.HEADER, initDataFor(ALLOWED_USER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("APPLIED")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.category").value(MiniAppApiError.INVALID_WORKFLOW));
    }

    /**
     * An exhausted concurrency retry must reach the client as the same typed 409 as any other
     * refused change — never as an untyped 500 carrying UnexpectedRollbackException.
     */
    @Test
    void reportsAnExhaustedConcurrencyRetryAsATypedConflict() throws Exception {
        when(workflows.change(MUTATION, 7L, WorkflowStatus.SAVED))
                .thenThrow(ApplicationTrackerService.conflict());

        enabled().perform(put(BASE + "/jobs/7/workflow")
                        .header(MiniAppAuthInterceptor.HEADER, initDataFor(ALLOWED_USER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("SAVED")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.category").value(MiniAppApiError.INVALID_WORKFLOW))
                .andExpect(jsonPath("$.message")
                        .value("The application changed concurrently. Please retry."));
    }

    /**
     * {@code @Positive} on the path variable is the first guard in the running application,
     * but standaloneSetup builds no method-validation proxy, so it cannot be exercised here.
     * The guard that always applies is JobReviewService's own {@code jobId <= 0} check, and
     * this asserts the controller turns it into a 404 rather than a 500.
     */
    @Test
    void reportsANonPositiveJobIdAsNotFound() throws Exception {
        when(workflows.change(MUTATION, 0L, WorkflowStatus.SAVED)).thenThrow(new JobReviewException(
                JobReviewException.Category.JOB_NOT_FOUND,
                "That vacancy is not in the review queue."));

        enabled().perform(put(BASE + "/jobs/0/workflow")
                        .header(MiniAppAuthInterceptor.HEADER, initDataFor(ALLOWED_USER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("SAVED")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.category").value(MiniAppApiError.JOB_NOT_FOUND));
    }

    // ---------------------------------------------------------------- error hygiene

    @Test
    void keepsSecretsAndSubmittedCredentialsOutOfEveryErrorBody() throws Exception {
        String initData = initDataFor(OTHER_USER);
        MockMvc mvc = enabled();

        for (String body : List.of(
                mvc.perform(get(BASE + "/snapshot")).andReturn().getResponse().getContentAsString(),
                mvc.perform(get(BASE + "/snapshot").header(MiniAppAuthInterceptor.HEADER, "bad"))
                        .andReturn().getResponse().getContentAsString(),
                mvc.perform(get(BASE + "/snapshot").header(MiniAppAuthInterceptor.HEADER, initData))
                        .andReturn().getResponse().getContentAsString())) {
            assertThat(body).doesNotContain(BOT_TOKEN, initData, "hash", "WebAppData",
                    Long.toString(OTHER_USER));
        }
    }

    /** A workflow request body. Every mutation carries the id that makes a retry resolvable. */
    private static String body(String status) {
        return "{\"status\":\"" + status + "\",\"mutationId\":\"" + MUTATION + "\"}";
    }

    private static String undoBody(String mutationId, String undoToken) {
        return "{\"mutationId\":\"" + mutationId + "\",\"undoToken\":\"" + undoToken + "\"}";
    }

    private static MiniAppOperation operation(WorkflowStatus status, boolean changed, String undo) {
        return new MiniAppOperation(MUTATION, 12L, false, 7L, status, changed,
                status == WorkflowStatus.SAVED ? ApplicationStatus.SAVED : null, undo);
    }

    private static MiniAppSnapshot snapshot(
            List<MiniAppSnapshot.MiniAppJob> review,
            List<MiniAppSnapshot.MiniAppApplication> applications) {
        return new MiniAppSnapshot(
                12L,
                new MiniAppSnapshot.MiniAppJobPage(review, review.size(), 50, false),
                new MiniAppSnapshot.MiniAppJobPage(List.of(), 0, 50, false),
                new MiniAppSnapshot.MiniAppApplicationPage(
                        applications, applications.size(), 20, false),
                new MiniAppSnapshot.MiniAppWorkflowCounts(review.size(), 0, 0, 0, 0),
                new MiniAppSnapshot.MiniAppApplicationCounts(
                        applications.size(), 0, applications.size(), 0, 0, 0, 0));
    }
}

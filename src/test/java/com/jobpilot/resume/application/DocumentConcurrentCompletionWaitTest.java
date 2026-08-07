package com.jobpilot.resume.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;

import com.jobpilot.jobs.domain.RawJob;
import com.jobpilot.jobs.repository.JobRepository;
import com.jobpilot.jobs.repository.JobRequirementRepository;
import com.jobpilot.jobs.repository.JobScoreRepository;
import com.jobpilot.jobs.service.JobProcessor;
import com.jobpilot.llm.api.LlmProvider;
import com.jobpilot.llm.repository.JobAnalysisRepository;
import com.jobpilot.llm.repository.LlmBudgetReservationRepository;
import com.jobpilot.llm.repository.LlmUsageEventRepository;
import com.jobpilot.resume.application.ControlledRenderers.ControlledCoverNoteDocxRenderer;
import com.jobpilot.resume.application.ControlledRenderers.ControlledResumeDocxRenderer;
import com.jobpilot.resume.config.DocumentProperties;
import com.jobpilot.resume.domain.DocumentFormat;
import com.jobpilot.resume.domain.DocumentRenderStatus;
import com.jobpilot.resume.repository.CoverNoteRepository;
import com.jobpilot.resume.repository.ResumeVersionRepository;
import com.jobpilot.resume.storage.DocumentKind;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The duplicate-request wait, measured against a deadline the test sets rather than against how
 * fast this host renders a PDF.
 *
 * <p>The context runs a deliberately tiny {@code concurrent-completion-timeout} (1s) and pauses
 * the renderer with latches, so "the winner finished in time" and "the winner ran past the
 * budget" are both chosen by the test. Against the previous hard-coded five seconds the expiry
 * case fails deterministically: five seconds outlasts every delay used here, so the loser would
 * report CACHED where the configured budget says it must give up.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:concurrent-wait;MODE=PostgreSQL;"
                + "DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa", "spring.datasource.password=",
        "jobpilot.documents.enabled=true",
        "jobpilot.documents.max-docx-bytes=2097152",
        "jobpilot.documents.max-pdf-bytes=2097152",
        "jobpilot.documents.resume-template-version=resume-wait-v1",
        "jobpilot.documents.cover-note-template-version=cover-wait-v1",
        "jobpilot.documents.renderer-version=renderer-wait-v1",
        "jobpilot.documents.max-preview-characters=4000",
        "jobpilot.documents.stale-after=2m",
        "jobpilot.documents.concurrent-completion-timeout=1s",
        "jobpilot.documents.contact-cache-hmac-key="
                + "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
        "jobpilot.documents.contact.email=student@example.test",
        "jobpilot.documents.contact.phone=+1 202 555 0100",
        "jobpilot.documents.contact.github-url=https://example.test/code",
        "jobpilot.llm.enabled=false"
})
@Import(ControlledRenderers.Configuration.class)
class DocumentConcurrentCompletionWaitTest {
    private static final Path STORAGE = temporaryStorage();
    /** Comfortably inside the configured 1s budget. */
    private static final Duration WITHIN_BUDGET = Duration.ofMillis(200);
    /** Past the configured 1s budget, and far short of the five seconds this replaced. */
    private static final Duration PAST_BUDGET = Duration.ofMillis(1_600);

    @DynamicPropertySource
    static void storage(DynamicPropertyRegistry registry) {
        registry.add("jobpilot.documents.storage-root", STORAGE::toString);
    }

    @Autowired private ResumeGenerationService service;
    @Autowired private JobProcessor processor;
    @Autowired private JobRepository jobs;
    @Autowired private JobRequirementRepository requirements;
    @Autowired private JobScoreRepository scores;
    @Autowired private ResumeVersionRepository resumes;
    @Autowired private CoverNoteRepository coverNotes;
    @Autowired private DocumentProperties properties;
    @Autowired private ControlledResumeDocxRenderer resumeRenderer;
    @Autowired private ControlledCoverNoteDocxRenderer coverRenderer;
    @Autowired private JobAnalysisRepository analyses;
    @Autowired private LlmBudgetReservationRepository reservations;
    @Autowired private LlmUsageEventRepository usage;
    @Autowired private JdbcTemplate jdbc;
    @MockBean private DocumentGenerationClaimObserver claimObserver;
    @MockBean private LlmProvider provider;

    @BeforeEach
    void clean() throws Exception {
        reset(claimObserver, provider);
        resumeRenderer.resetControl();
        coverRenderer.resetControl();
        jdbc.update("delete from application_status_history");
        jdbc.update("delete from applications");
        coverNotes.deleteAll();
        resumes.deleteAll();
        usage.deleteAll();
        analyses.deleteAll();
        reservations.deleteAll();
        requirements.deleteAll();
        scores.deleteAll();
        jobs.deleteAll();
        clearStorage();
    }

    @AfterAll
    static void removeStorage() throws Exception {
        clearStorage();
        Files.deleteIfExists(STORAGE);
    }

    @Test
    void theConfiguredBudgetIsTheOneInEffect() {
        assertThat(properties.concurrentCompletionTimeout()).isEqualTo(Duration.ofSeconds(1));
    }

    @Test
    void loserReturnsCachedWhenTheWinnerCompletesInsideTheBudget() throws Exception {
        Race race = race("within-budget", WITHIN_BUDGET);

        assertThat(race.statuses()).containsExactlyInAnyOrder(
                DocumentGenerationStatus.CREATED, DocumentGenerationStatus.CACHED);
        assertThat(race.resumeVersionIds()).doesNotContainNull()
                .containsOnly(race.first().resumeVersionId());
        assertOneCompletedResume();
        assertThat(resumeRenderer.calls()).isEqualTo(1);
    }

    @Test
    void loserFailsWhenTheBudgetExpiresWhileTheWinnerStillCompletes() throws Exception {
        Race race = race("past-budget", PAST_BUDGET);

        // The waiter gave up on the configured deadline rather than on a hard-coded five seconds.
        assertThat(race.statuses()).containsExactlyInAnyOrder(
                DocumentGenerationStatus.CREATED, DocumentGenerationStatus.GENERATION_FAILED);
        // The winner was never disturbed: one row, completed, one artifact, nothing marked FAILED.
        assertOneCompletedResume();
        assertThat(resumeRenderer.calls()).isEqualTo(1);
        assertThat(countFiles("resume.docx")).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "select count(*) from resume_versions where render_status = 'FAILED'",
                Integer.class)).isZero();
    }

    @Test
    void coverNoteGenerationUsesTheSameConfiguredBudget() throws Exception {
        // The résumé is rendered and cached first, so the second race is decided by the cover note.
        long jobId = createJob("cover-budget");
        service.generate(jobId, command(true));
        assertThat(coverNotes.count()).isEqualTo(1);
        coverNotes.deleteAll();
        clearCoverArtifacts();
        coverRenderer.resetControl();

        Race race = raceOn(jobId, PAST_BUDGET, coverRenderer);

        assertThat(race.statuses()).containsExactlyInAnyOrder(
                DocumentGenerationStatus.CREATED, DocumentGenerationStatus.GENERATION_FAILED);
        assertThat(coverRenderer.calls()).isEqualTo(1);
        assertThat(coverNotes.findAll()).singleElement().satisfies(note ->
                assertThat(note.getRenderStatus()).isEqualTo(DocumentRenderStatus.COMPLETED));
    }

    // ------------------------------------------------------------------ helpers

    private record Race(DocumentGenerationResult first, DocumentGenerationResult second) {
        java.util.List<DocumentGenerationStatus> statuses() {
            return java.util.List.of(first.status(), second.status());
        }

        java.util.List<Long> resumeVersionIds() {
            return java.util.Arrays.asList(first.resumeVersionId(), second.resumeVersionId());
        }
    }

    private Race race(String externalId, Duration hold) throws Exception {
        return raceOn(createJob(externalId), hold, resumeRenderer);
    }

    /**
     * Two identical requests, released together. Both are held at the cache miss so neither can
     * win by scheduling luck; the winner is then held inside the renderer for {@code hold}, which
     * is the only thing that decides whether the loser's budget expires.
     */
    private Race raceOn(long jobId, Duration hold, Object controlledRenderer) throws Exception {
        CyclicBarrier bothMissed = new CyclicBarrier(2);
        doAnswer(invocation -> {
            bothMissed.await(20, TimeUnit.SECONDS);
            return null;
        }).when(claimObserver).afterCacheMiss(any(DocumentKind.class), anyString());
        CountDownLatch rendererEntered = new CountDownLatch(1);
        CountDownLatch releaseRenderer = new CountDownLatch(1);
        if (controlledRenderer == resumeRenderer) {
            resumeRenderer.block(rendererEntered, releaseRenderer);
        } else {
            coverRenderer.block(rendererEntered, releaseRenderer);
        }

        GenerateDocumentsCommand command = command(controlledRenderer == coverRenderer);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> service.generate(jobId, command));
            var second = executor.submit(() -> service.generate(jobId, command));
            assertThat(rendererEntered.await(20, TimeUnit.SECONDS)).isTrue();
            Thread.sleep(hold.toMillis());
            releaseRenderer.countDown();
            return new Race(first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS));
        } finally {
            releaseRenderer.countDown();
        }
    }

    private void assertOneCompletedResume() {
        assertThat(resumes.findAll()).singleElement().satisfies(winner ->
                assertThat(winner.getRenderStatus()).isEqualTo(DocumentRenderStatus.COMPLETED));
    }

    /** Résumé only: the cover note is opted into where it is the subject of the race. */
    private GenerateDocumentsCommand command() {
        return command(false);
    }

    private GenerateDocumentsCommand command(boolean includeCoverNote) {
        return new GenerateDocumentsCommand(includeCoverNote,
                Set.of(DocumentFormat.DOCX, DocumentFormat.PDF), false);
    }

    private long createJob(String externalId) {
        return processor.process(new RawJob("synthetic", externalId,
                "https://example.invalid/jobs/" + externalId, "Java Backend Intern",
                "Synthetic Company", "Bucharest, Romania",
                "Java Spring Boot SQL internship with REST API work and mentorship.",
                "INTERN", Instant.parse("2026-07-19T08:00:00Z"), null,
                "Synthetic concurrency fixture")).job().getId();
    }

    private long countFiles(String suffix) throws Exception {
        if (!Files.exists(STORAGE)) return 0;
        try (var paths = Files.walk(STORAGE)) {
            return paths.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(suffix)).count();
        }
    }

    private void clearCoverArtifacts() throws Exception {
        if (!Files.exists(STORAGE)) return;
        try (var paths = Files.walk(STORAGE)) {
            paths.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().contains("cover"))
                    .forEach(DocumentConcurrentCompletionWaitTest::delete);
        }
    }

    private static void clearStorage() throws Exception {
        if (!Files.exists(STORAGE)) return;
        try (var paths = Files.walk(STORAGE)) {
            paths.sorted(Comparator.reverseOrder())
                    .filter(path -> !path.equals(STORAGE))
                    .forEach(DocumentConcurrentCompletionWaitTest::delete);
        }
    }

    private static void delete(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (Exception ignored) {
            // Best effort: the temporary directory is removed after the class.
        }
    }

    private static Path temporaryStorage() {
        try {
            return Files.createTempDirectory("jobpilot-concurrent-wait");
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }
}

package com.jobpilot.resume.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verifyNoInteractions;

import com.jobpilot.applications.domain.ApplicationStatus;
import com.jobpilot.applications.domain.ApplicationStatusChangeSource;
import com.jobpilot.applications.repository.ApplicationRepository;
import com.jobpilot.applications.application.ApplicationTrackerService;
import com.jobpilot.candidate.domain.Candidate;
import com.jobpilot.candidate.domain.CandidateProfile;
import com.jobpilot.candidate.repository.CandidateProfileRepository;
import com.jobpilot.candidate.repository.CandidateRepository;
import com.jobpilot.jobs.domain.RawJob;
import com.jobpilot.jobs.repository.JobRequirementRepository;
import com.jobpilot.jobs.repository.JobRepository;
import com.jobpilot.jobs.repository.JobScoreRepository;
import com.jobpilot.jobs.service.JobProcessor;
import com.jobpilot.llm.api.LlmProvider;
import com.jobpilot.llm.application.JobAnalysisService;
import com.jobpilot.llm.domain.JobAnalysis;
import com.jobpilot.llm.domain.JobAnalysisData;
import com.jobpilot.llm.domain.JobAnalysisJson;
import com.jobpilot.llm.domain.JobAnalysisResult;
import com.jobpilot.llm.domain.JobAnalysisResultStatus;
import com.jobpilot.llm.domain.LlmFailureCategory;
import com.jobpilot.llm.domain.LlmOperationType;
import com.jobpilot.llm.repository.JobAnalysisRepository;
import com.jobpilot.llm.repository.LlmBudgetReservationRepository;
import com.jobpilot.llm.repository.LlmUsageEventRepository;
import com.jobpilot.maintenance.DocumentMaintenanceService;
import com.jobpilot.resume.application.ControlledRenderers.ControlledResumeDocxRenderer;
import com.jobpilot.resume.domain.DocumentFailureCategory;
import com.jobpilot.resume.domain.DocumentFormat;
import com.jobpilot.resume.domain.DocumentRenderStatus;
import com.jobpilot.resume.domain.ResumeVersion;
import com.jobpilot.resume.repository.CoverNoteRepository;
import com.jobpilot.resume.repository.ResumeVersionRepository;
import com.jobpilot.resume.storage.DocumentKind;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:stage5-documents;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa", "spring.datasource.password=",
        "jobpilot.documents.enabled=true",
        "jobpilot.documents.max-docx-bytes=2097152",
        "jobpilot.documents.max-pdf-bytes=2097152",
        "jobpilot.documents.resume-template-version=resume-test-v1",
        "jobpilot.documents.cover-note-template-version=cover-test-v1",
        "jobpilot.documents.renderer-version=renderer-test-v1",
        "jobpilot.documents.max-preview-characters=4000",
        "jobpilot.documents.stale-after=2m",
        "jobpilot.documents.contact-cache-hmac-key=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
        "jobpilot.documents.contact.email=student@example.test",
        "jobpilot.documents.contact.phone=+1 202 555 0100",
        "jobpilot.documents.contact.github-url=https://example.test/code",
        "jobpilot.documents.contact.linkedin-url=",
        "jobpilot.documents.contact.portfolio-url=",
        "jobpilot.llm.enabled=false"
})
@Import(ControlledRenderers.Configuration.class)
class ResumeGenerationServiceTest {
    private static final Path STORAGE = temporaryStorage();

    @DynamicPropertySource
    static void storage(DynamicPropertyRegistry registry) {
        registry.add("jobpilot.documents.storage-root", STORAGE::toString);
    }

    @Autowired private ResumeGenerationService service;
    @Autowired private ControlledResumeDocxRenderer resumeRenderer;
    @Autowired private ApplicationDocumentSelectionService selection;
    @Autowired private ApplicationTrackerService tracker;
    @Autowired private JobProcessor processor;
    @Autowired private JobRepository jobs;
    @Autowired private JobRequirementRepository requirements;
    @Autowired private JobScoreRepository scores;
    @Autowired private CandidateRepository candidates;
    @Autowired private CandidateProfileRepository profiles;
    @Autowired private ResumeVersionRepository resumes;
    @Autowired private CoverNoteRepository coverNotes;
    @Autowired private JobAnalysisRepository analyses;
    @Autowired private JobAnalysisJson analysisJson;
    @SpyBean private JobAnalysisService analysisService;
    @Autowired private LlmBudgetReservationRepository reservations;
    @Autowired private LlmUsageEventRepository usage;
    @Autowired private ApplicationRepository applications;
    @Autowired private DocumentMaintenanceService maintenance;
    @Autowired private JdbcTemplate jdbc;
    @MockBean private LlmProvider provider;
    /** A production no-op, mocked here purely to synchronise the concurrency test. */
    @MockBean private DocumentGenerationClaimObserver claimObserver;

    @BeforeEach
    void cleanDatabase() throws Exception {
        org.mockito.Mockito.reset(claimObserver, analysisService);
        resumeRenderer.resetControl();
        jdbc.update("delete from application_status_history");
        applications.deleteAll();
        coverNotes.deleteAll();
        resumes.deleteAll();
        usage.deleteAll();
        analyses.deleteAll();
        reservations.deleteAll();
        requirements.deleteAll();
        scores.deleteAll();
        jobs.deleteAll();
        if (Files.exists(STORAGE)) {
            try (var paths = Files.walk(STORAGE)) {
                paths.sorted(Comparator.reverseOrder()).filter(path -> !path.equals(STORAGE))
                        .forEach(ResumeGenerationServiceTest::delete);
            }
        }
    }

    @AfterAll
    static void removeStorage() throws Exception {
        if (!Files.exists(STORAGE)) return;
        try (var paths = Files.walk(STORAGE)) {
            paths.sorted(Comparator.reverseOrder()).forEach(ResumeGenerationServiceTest::delete);
        }
    }

    @Test
    void createsTruthfulPrivateArtifactsCachesThemAndRequiresHumanSelection() throws Exception {
        long jobId = processor.process(new RawJob("synthetic", "stage5-1",
                "https://example.invalid/jobs/stage5-1", "Java Backend Intern",
                "Synthetic Company", "Bucharest, Romania",
                "Java backend internship using Spring Boot, REST, PostgreSQL and JUnit. "
                        + "The role includes mentorship and asks for SQL and API development.",
                "INTERN", Instant.parse("2026-07-19T08:00:00Z"), null,
                "Synthetic Stage 5 fixture")).job().getId();
        GenerateDocumentsCommand command = new GenerateDocumentsCommand(true,
                Set.of(DocumentFormat.DOCX, DocumentFormat.PDF), true);

        DocumentGenerationResult created = service.generate(jobId, command);

        assertThat(created.status()).isEqualTo(DocumentGenerationStatus.FALLBACK);
        assertThat(created.resumeVersionId()).isPositive();
        assertThat(created.coverNoteId()).isPositive();
        assertThat(created.fallbackUsed()).isTrue();
        assertThat(created.resumePreview())
                .contains("SUMMARY", "TECHNICAL SKILLS", "PROJECTS", "EDUCATION", "LANGUAGES")
                .doesNotContain("student@example.test", "+1 202 555 0100");
        assertThat(created.changeSummary()).hasSize(3);
        assertThat(created.interviewClaims()).isNotEmpty()
                .allMatch(value -> value.startsWith("Can discuss: "));

        var resume = resumes.findById(created.resumeVersionId()).orElseThrow();
        var cover = coverNotes.findById(created.coverNoteId()).orElseThrow();
        assertThat(resume.getRenderStatus()).isEqualTo(DocumentRenderStatus.COMPLETED);
        assertThat(count("resume_version_skills", resume.getId())).isBetween(8, 16);
        assertThat(count("resume_version_projects", resume.getId())).isBetween(1, 3);
        assertThat(count("resume_version_languages", resume.getId())).isBetween(2, 5);
        assertThat(resume.getDocxPath()).doesNotStartWith("/").doesNotContain("..", "Pavlo");
        assertThat(resume.getPdfPath()).doesNotStartWith("/").doesNotContain("..", "Pavlo");
        assertThat(resume.getPdfPageCount()).isBetween(1, 2);
        assertThat(resume.getPlainTextPreview()).doesNotContain("student@example.test");
        assertThat(cover.getContent()).doesNotContain("student@example.test", "+1 202 555 0100")
                .contains("Dear Hiring Team,")
                .doesNotContain("perfect match", "application was submitted");
        assertThat(count("cover_note_fact_references", cover.getId())).isPositive();

        DocumentDownload resumeDocx = service.downloadResume(resume.getId(), DocumentFormat.DOCX);
        try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(resumeDocx.bytes()));
             XWPFWordExtractor text = new XWPFWordExtractor(document)) {
            assertThat(text.getText()).contains("student@example.test", "SUMMARY", "Spring Boot")
                    .doesNotContain("WORK EXPERIENCE", "professional Java engineer");
            assertThat(document.getTables()).isEmpty();
        }
        DocumentDownload resumePdf = service.downloadResume(resume.getId(), DocumentFormat.PDF);
        try (var document = Loader.loadPDF(resumePdf.bytes())) {
            assertThat(document.getNumberOfPages()).isBetween(1, 2);
            assertThat(document.getNumberOfPages()).isEqualTo(resume.getPdfPageCount());
            assertThat(new PDFTextStripper().getText(document))
                    .contains("student@example.test", "SUMMARY", "PROJECTS");
            assertThat(document.getDocumentCatalog().getAcroForm()).isNull();
        }

        DocumentGenerationResult cached = service.generate(jobId, command);
        assertThat(cached.status()).isEqualTo(DocumentGenerationStatus.CACHED);
        assertThat(cached.resumeVersionId()).isEqualTo(created.resumeVersionId());
        assertThat(cached.coverNoteId()).isEqualTo(created.coverNoteId());
        assertThat(resumes.count()).isEqualTo(1);
        assertThat(coverNotes.count()).isEqualTo(1);
        verifyNoInteractions(provider);

        tracker.transition(jobId, ApplicationStatus.SAVED, null, null,
                ApplicationStatusChangeSource.INTERNAL);
        ApplicationDocumentSelectionResult selected = selection.select(jobId,
                resume.getId(), cover.getId());
        ApplicationDocumentSelectionResult duplicate = selection.select(jobId,
                resume.getId(), cover.getId());
        assertThat(selected.changed()).isTrue();
        assertThat(selected.applicationStatus()).isEqualTo(ApplicationStatus.SAVED);
        assertThat(duplicate.changed()).isFalse();
        assertThat(applications.findByJobId(jobId).orElseThrow().getStatus())
                .isEqualTo(ApplicationStatus.SAVED);
    }

    @Test
    void generationUsesAnalysisProfileWhenAnotherCandidateHasSameActiveVersion() {
        CandidateProfile configured = configuredProfile();
        CandidateProfile other = createOtherProfile(
                "other-documents", configured.getProfileVersion());
        long jobId = createDocumentJob("candidate-profile-identity");

        DocumentGenerationResult result = service.generate(jobId,
                new GenerateDocumentsCommand(false, Set.of(DocumentFormat.DOCX), false));

        assertThat(result.status()).isEqualTo(DocumentGenerationStatus.CREATED);
        ResumeVersion resume = resumes.findById(result.resumeVersionId()).orElseThrow();
        assertThat(other.getProfileVersion()).isEqualTo(configured.getProfileVersion());
        assertThat(resume.getCandidateProfile().getId()).isEqualTo(configured.getId());
        assertThat(analyses.findById(resume.getSourceAnalysis().getId()).orElseThrow()
                .getCandidateProfile().getId())
                .isEqualTo(configured.getId());
    }

    @Test
    void sameProfileVersionFromAnotherCandidateCannotSatisfyDocumentIdentity() {
        CandidateProfile configured = configuredProfile();
        CandidateProfile other = createOtherProfile(
                "other-analysis", configured.getProfileVersion());
        long jobId = createDocumentJob("candidate-analysis-mismatch");
        var job = jobs.findById(jobId).orElseThrow();
        Instant now = Instant.parse("2026-07-19T10:00:00Z");
        JobAnalysisData data = minimalAnalysis();
        JobAnalysis mismatched = new JobAnalysis(job, other, LlmOperationType.JOB_ANALYSIS,
                "disabled", "disabled", "job-analysis-v1", "1".repeat(64),
                other.getSourceHash(), "2".repeat(64), now);
        mismatched.completeFallback(data, LlmFailureCategory.DISABLED, null, analysisJson, now);
        analyses.saveAndFlush(mismatched);
        JobAnalysisResult mismatchedResult = new JobAnalysisResult(
                JobAnalysisResultStatus.DISABLED, mismatched.getId(), jobId,
                other.getProfileVersion(), data, LlmFailureCategory.DISABLED);
        doReturn(mismatchedResult).when(analysisService).analyze(jobId, true);

        DocumentGenerationResult result = service.generate(jobId,
                new GenerateDocumentsCommand(false, Set.of(DocumentFormat.DOCX), false));

        assertThat(result.status()).isEqualTo(DocumentGenerationStatus.ANALYSIS_FAILED);
        assertThat(resumes.count()).isZero();
    }

    /**
     * The waiter honours an interrupt: it stops well inside the fifteen-second default budget and
     * leaves the interrupted status set for whoever asked it to stop — a graceful shutdown,
     * typically.
     *
     * <p>The winner is started first and parked in the renderer, so the second request is the
     * loser by construction rather than by whichever thread the scheduler favours. The interrupt
     * is delivered only once that thread is demonstrably parked in the waiter's own poll sleep;
     * interrupting anywhere else lets the driver swallow the flag.
     */
    @Test
    void anInterruptedWaiterStopsPromptlyAndRestoresItsInterruptedFlag() throws Exception {
        long jobId = processor.process(new RawJob("synthetic", "stage5-interrupt",
                "https://example.invalid/jobs/stage5-interrupt", "Java Backend Intern",
                "Synthetic Company", "Bucharest, Romania",
                "Java Spring Boot SQL internship with REST API work and mentorship.",
                "INTERN", Instant.parse("2026-07-19T08:00:00Z"), null,
                "Synthetic interrupt fixture")).job().getId();
        GenerateDocumentsCommand command = new GenerateDocumentsCommand(false,
                Set.of(DocumentFormat.DOCX, DocumentFormat.PDF), false);
        CountDownLatch rendererEntered = new CountDownLatch(1);
        CountDownLatch releaseRenderer = new CountDownLatch(1);
        resumeRenderer.block(rendererEntered, releaseRenderer);
        java.util.concurrent.atomic.AtomicBoolean flagRestored =
                new java.util.concurrent.atomic.AtomicBoolean();
        java.util.concurrent.atomic.AtomicReference<DocumentGenerationStatus> loserStatus =
                new java.util.concurrent.atomic.AtomicReference<>();

        try (var executor = Executors.newFixedThreadPool(1)) {
            var winner = executor.submit(() -> service.generate(jobId, command));
            // Only once the winner owns the claim and is rendering does the duplicate arrive.
            assertThat(rendererEntered.await(20, TimeUnit.SECONDS)).isTrue();

            Thread loser = new Thread(() -> {
                DocumentGenerationStatus status = service.generate(jobId, command).status();
                flagRestored.set(Thread.currentThread().isInterrupted());
                loserStatus.set(status);
            }, "interrupted-waiter");
            loser.start();

            assertThat(awaitParkedInWaiter(loser)).as("loser parked in the poll sleep").isTrue();
            long interruptedAt = System.nanoTime();
            loser.interrupt();
            loser.join(TimeUnit.SECONDS.toMillis(10));
            Duration elapsed = Duration.ofNanos(System.nanoTime() - interruptedAt);

            assertThat(loser.isAlive()).isFalse();
            // Far short of the fifteen-second default it would otherwise have waited out.
            assertThat(elapsed).isLessThan(Duration.ofSeconds(2));
            assertThat(flagRestored).isTrue();
            assertThat(loserStatus).hasValue(DocumentGenerationStatus.GENERATION_FAILED);

            releaseRenderer.countDown();
            assertThat(winner.get(30, TimeUnit.SECONDS).status())
                    .isEqualTo(DocumentGenerationStatus.CREATED);
        } finally {
            releaseRenderer.countDown();
        }
        assertThat(resumes.findAll()).singleElement().satisfies(value ->
                assertThat(value.getRenderStatus()).isEqualTo(DocumentRenderStatus.COMPLETED));
    }

    /**
     * True once the thread is parked in the waiter's own poll sleep — not merely TIMED_WAITING,
     * which it also is while acquiring a connection.
     */
    private static boolean awaitParkedInWaiter(Thread thread) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            if (thread.getState() == Thread.State.TIMED_WAITING) {
                for (StackTraceElement frame : thread.getStackTrace()) {
                    if (frame.getClassName().equals(ResumeGenerationService.class.getName())
                            && frame.getMethodName().equals("pauseForWinner")) {
                        return true;
                    }
                }
            }
            Thread.sleep(2);
        }
        return false;
    }

    /**
     * Both requests are held at the cache miss so neither wins by scheduling luck, and the winner
     * is released from the renderer explicitly. Nothing here depends on how long this host takes
     * to render a DOCX and a PDF — the previous version did, and failed on CI whenever rendering
     * outran the waiter's budget.
     */
    @Test
    void concurrentIdenticalGenerationCreatesAtMostOneCompletedVersion() throws Exception {
        long jobId = processor.process(new RawJob("synthetic", "stage5-concurrent",
                "https://example.invalid/jobs/stage5-concurrent", "Java Backend Intern",
                "Synthetic Company", "Bucharest, Romania",
                "Java Spring Boot SQL internship with REST API work and mentorship.",
                "INTERN", Instant.parse("2026-07-19T08:00:00Z"), null,
                "Synthetic concurrent fixture")).job().getId();
        GenerateDocumentsCommand command = new GenerateDocumentsCommand(false,
                Set.of(DocumentFormat.DOCX, DocumentFormat.PDF), false);
        CyclicBarrier bothMissedTheCache = new CyclicBarrier(2);
        doAnswer(invocation -> {
            bothMissedTheCache.await(20, TimeUnit.SECONDS);
            return null;
        }).when(claimObserver).afterCacheMiss(any(DocumentKind.class), anyString());
        CountDownLatch rendererEntered = new CountDownLatch(1);
        CountDownLatch releaseRenderer = new CountDownLatch(1);
        resumeRenderer.block(rendererEntered, releaseRenderer);

        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> service.generate(jobId, command));
            var second = executor.submit(() -> service.generate(jobId, command));
            assertThat(rendererEntered.await(20, TimeUnit.SECONDS)).isTrue();
            releaseRenderer.countDown();
            DocumentGenerationResult a = first.get(30, TimeUnit.SECONDS);
            DocumentGenerationResult b = second.get(30, TimeUnit.SECONDS);

            assertThat(java.util.List.of(a.status(), b.status()))
                    .containsExactlyInAnyOrder(DocumentGenerationStatus.CREATED,
                            DocumentGenerationStatus.CACHED);
            assertThat(resumeRenderer.calls()).isEqualTo(1);
            assertThat(resumes.count()).isEqualTo(1);
            assertThat(resumes.findAll()).singleElement().satisfies(value ->
                    assertThat(value.getRenderStatus()).isEqualTo(DocumentRenderStatus.COMPLETED));
        } finally {
            releaseRenderer.countDown();
        }
        verifyNoInteractions(provider);
    }

    @Test
    void maintenanceRecoversStaleClaimsAndRemovesBoundedPartialAndOrphanFiles() throws Exception {
        long jobId = processor.process(new RawJob("synthetic", "stage6-maintenance",
                "https://example.invalid/jobs/stage6-maintenance", "Java Backend Intern",
                "Synthetic Company", "Bucharest, Romania",
                "Java Spring Boot SQL internship with REST API work and mentorship.",
                "INTERN", Instant.now(), null, "Synthetic maintenance fixture")).job().getId();
        DocumentGenerationResult generated = service.generate(jobId,
                new GenerateDocumentsCommand(false, Set.of(DocumentFormat.DOCX), false));
        var resume = resumes.findById(generated.resumeVersionId()).orElseThrow();
        Path generatedPath = STORAGE.resolve(resume.getDocxPath());
        assertThat(generatedPath).exists();
        jdbc.update("update resume_versions set render_status = 'IN_PROGRESS', generated_at = null, "
                        + "failure_category = null, updated_at = ? where id = ?",
                java.sql.Timestamp.from(Instant.now().minus(Duration.ofMinutes(5))), resume.getId());

        Path orphanDirectory = STORAGE.resolve("resumes/999999");
        Files.createDirectories(orphanDirectory);
        Path orphan = orphanDirectory.resolve("resume.pdf");
        Path partial = orphanDirectory.resolve(".jobpilot-orphan.partial");
        Files.write(orphan, new byte[]{1});
        Files.write(partial, new byte[]{2});
        FileTime old = FileTime.from(Instant.now().minus(Duration.ofHours(2)));
        Files.setLastModifiedTime(orphan, old);
        Files.setLastModifiedTime(partial, old);

        var result = maintenance.run(10, Duration.ofSeconds(5), Duration.ofMinutes(10));

        assertThat(result.staleResumesRecovered()).isEqualTo(1);
        assertThat(result.partialArtifactsRemoved()).isEqualTo(1);
        assertThat(result.orphanArtifactsRemoved()).isEqualTo(1);
        assertThat(resumes.findById(resume.getId()).orElseThrow().getRenderStatus())
                .isEqualTo(DocumentRenderStatus.FAILED);
        assertThat(resumes.findById(resume.getId()).orElseThrow().getFailureCategory())
                .isEqualTo(DocumentFailureCategory.STALE_GENERATION);
        assertThat(generatedPath).doesNotExist();
        assertThat(orphan).doesNotExist();
        assertThat(partial).doesNotExist();
    }

    private static Path temporaryStorage() {
        try {
            return Files.createTempDirectory("jobpilot-stage5-");
        } catch (java.io.IOException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    private long createDocumentJob(String externalId) {
        return processor.process(new RawJob("synthetic", externalId,
                "https://example.invalid/jobs/" + externalId, "Java Backend Intern",
                "Synthetic Company", "Bucharest, Romania",
                "Java Spring Boot SQL internship with REST API work and mentorship.",
                "INTERN", Instant.parse("2026-07-19T08:00:00Z"), null,
                "Synthetic candidate identity fixture")).job().getId();
    }

    private CandidateProfile configuredProfile() {
        Candidate configured = candidates.findByStableKey("default").orElseThrow();
        return profiles.findByCandidateIdAndActiveTrue(configured.getId()).orElseThrow();
    }

    private CandidateProfile createOtherProfile(String stableKey, int profileVersion) {
        Instant now = Instant.parse("2026-07-19T07:00:00Z");
        Candidate other = candidates.saveAndFlush(new Candidate(stableKey, now));
        return profiles.saveAndFlush(new CandidateProfile(other, profileVersion,
                "Other Candidate", "Elsewhere", "Other University", "Other Degree",
                2024, null, true, false, BigDecimal.ZERO, "f".repeat(64), now, true));
    }

    private JobAnalysisData minimalAnalysis() {
        return new JobAnalysisData("Synthetic Java internship", List.of("Java"), List.of(),
                List.of("Build backend services"), null, null, null,
                "Bucharest, Romania", null, List.of(), List.of(),
                List.of("Work authorization is unknown"), List.of(), 80, true);
    }

    private int count(String table, long id) {
        return jdbc.queryForObject("select count(*) from " + table
                + " where " + (table.startsWith("resume_version_")
                ? "resume_version_id" : "cover_note_id") + " = ?", Integer.class, id);
    }

    private static void delete(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (java.io.IOException exception) {
            throw new AssertionError(exception);
        }
    }
}

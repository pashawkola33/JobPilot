package com.jobpilot.resume.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verifyNoInteractions;

import com.jobpilot.common.Hashing;
import com.jobpilot.jobs.domain.RawJob;
import com.jobpilot.jobs.repository.JobRequirementRepository;
import com.jobpilot.jobs.repository.JobRepository;
import com.jobpilot.jobs.repository.JobScoreRepository;
import com.jobpilot.jobs.service.JobProcessor;
import com.jobpilot.llm.api.LlmProvider;
import com.jobpilot.llm.application.JobAnalysisService;
import com.jobpilot.llm.repository.JobAnalysisRepository;
import com.jobpilot.llm.repository.LlmBudgetReservationRepository;
import com.jobpilot.llm.repository.LlmUsageEventRepository;
import com.jobpilot.resume.config.DocumentProperties;
import com.jobpilot.resume.domain.DocumentFormat;
import com.jobpilot.resume.domain.DocumentRenderStatus;
import com.jobpilot.resume.domain.ResumeVersion;
import com.jobpilot.resume.render.ResumeDocxRenderer;
import com.jobpilot.resume.repository.CoverNoteRepository;
import com.jobpilot.resume.repository.ResumeVersionRepository;
import com.jobpilot.resume.storage.DocumentKind;
import com.jobpilot.resume.validation.DocumentContactPolicy;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = {
        "jobpilot.documents.enabled=true",
        "jobpilot.documents.max-docx-bytes=2097152",
        "jobpilot.documents.max-pdf-bytes=2097152",
        "jobpilot.documents.resume-template-version=resume-postgres-v1",
        "jobpilot.documents.cover-note-template-version=cover-postgres-v1",
        "jobpilot.documents.renderer-version=renderer-postgres-v1",
        "jobpilot.documents.max-preview-characters=4000",
        "jobpilot.documents.stale-after=2m",
        "jobpilot.documents.contact-cache-hmac-key="
                + "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
        "jobpilot.documents.contact.email=student@example.test",
        "jobpilot.documents.contact.phone=+1 202 555 0100",
        "jobpilot.documents.contact.github-url=https://example.test/code",
        "jobpilot.llm.enabled=false"
})
@Import(PostgresDocumentGenerationConcurrencyIT.RendererConfiguration.class)
class PostgresDocumentGenerationConcurrencyIT {
    private static final Path STORAGE = temporaryStorage();

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("jobpilot.documents.storage-root", STORAGE::toString);
    }

    @Autowired private ResumeGenerationService service;
    @Autowired private JobProcessor processor;
    @Autowired private JobAnalysisService analysisService;
    @Autowired private JobRepository jobs;
    @Autowired private JobRequirementRepository requirements;
    @Autowired private JobScoreRepository scores;
    @Autowired private JobAnalysisRepository analyses;
    @Autowired private LlmUsageEventRepository usage;
    @Autowired private LlmBudgetReservationRepository reservations;
    @Autowired private ResumeVersionRepository resumes;
    @Autowired private CoverNoteRepository coverNotes;
    @Autowired private com.jobpilot.candidate.repository.CandidateProfileRepository profiles;
    @Autowired private DocumentCacheKey cacheKeys;
    @Autowired private DocumentContactPolicy contacts;
    @Autowired private DocumentProperties documentProperties;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private ControlledResumeDocxRenderer renderer;
    @MockBean private DocumentGenerationClaimObserver claimObserver;
    @MockBean private LlmProvider provider;

    @BeforeEach
    void clean() throws Exception {
        reset(claimObserver, provider);
        renderer.resetControl();
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
        if (!Files.exists(STORAGE)) return;
        try (var paths = Files.walk(STORAGE)) {
            paths.sorted(Comparator.reverseOrder())
                    .forEach(PostgresDocumentGenerationConcurrencyIT::delete);
        }
    }

    @Test
    void concurrentIdenticalClaimsUseUniqueRaceRecoveryAndAdoptTheWinner() throws Exception {
        long jobId = createJob("postgres-race");
        GenerateDocumentsCommand command = command();
        CyclicBarrier bothCacheMisses = new CyclicBarrier(2);
        AtomicInteger observedMisses = new AtomicInteger();
        doAnswer(invocation -> {
            observedMisses.incrementAndGet();
            bothCacheMisses.await(10, TimeUnit.SECONDS);
            return null;
        }).when(claimObserver).afterCacheMiss(eq(DocumentKind.RESUME), anyString());
        CountDownLatch rendererEntered = new CountDownLatch(1);
        CountDownLatch releaseRenderer = new CountDownLatch(1);
        renderer.block(rendererEntered, releaseRenderer);
        CountDownLatch requestsStarted = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        List<DocumentGenerationResult> results;
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> generate(jobId, command, requestsStarted, start));
            var second = executor.submit(() -> generate(jobId, command, requestsStarted, start));
            assertThat(requestsStarted.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat(rendererEntered.await(15, TimeUnit.SECONDS)).isTrue();
            Thread.sleep(150);
            assertThat(first.isDone()).isFalse();
            assertThat(second.isDone()).isFalse();
            releaseRenderer.countDown();
            results = List.of(first.get(20, TimeUnit.SECONDS),
                    second.get(20, TimeUnit.SECONDS));
        } finally {
            releaseRenderer.countDown();
        }

        assertThat(observedMisses).hasValue(2);
        assertThat(results).extracting(DocumentGenerationResult::status)
                .containsExactlyInAnyOrder(DocumentGenerationStatus.CREATED,
                        DocumentGenerationStatus.CACHED);
        assertThat(results).extracting(DocumentGenerationResult::resumeVersionId)
                .doesNotContainNull().containsOnly(results.getFirst().resumeVersionId());
        assertThat(renderer.calls()).isEqualTo(1);
        assertThat(resumes.findAll()).singleElement().satisfies(winner -> {
            assertThat(winner.getRenderStatus()).isEqualTo(DocumentRenderStatus.COMPLETED);
            assertThat(winner.getAttemptCount()).isEqualTo(1);
            assertValidArtifact(winner);
            assertThat(factOwnerCount("resume_version_skills", winner.getId())).isEqualTo(1);
            assertThat(factOwnerCount("resume_version_projects", winner.getId())).isEqualTo(1);
            assertThat(factOwnerCount("resume_version_project_bullets", winner.getId())).isEqualTo(1);
            assertThat(factOwnerCount("resume_version_languages", winner.getId())).isEqualTo(1);
        });
        assertThat(countFiles("resume.docx")).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "select count(*) from resume_versions where render_status = 'FAILED'",
                Integer.class)).isZero();

        int callsAfterCompletion = renderer.calls();
        DocumentGenerationResult cached = service.generate(jobId, command);
        assertThat(cached.status()).isEqualTo(DocumentGenerationStatus.CACHED);
        assertThat(renderer.calls()).isEqualTo(callsAfterCompletion);
        assertThat(resumes.count()).isEqualTo(1);
        verifyNoInteractions(provider);

        ResumeVersion winner = resumes.findAll().getFirst();
        Files.write(STORAGE.resolve(winner.getDocxPath()), "tampered".getBytes());
        DocumentGenerationResult invalid = service.generate(jobId, command);
        assertThat(invalid.status()).isEqualTo(DocumentGenerationStatus.ARTIFACT_INVALID);
        assertThat(renderer.calls()).isEqualTo(callsAfterCompletion);
    }

    @Test
    void staleInProgressClaimIsRetriedAndReplacesCrashFiles() throws Exception {
        long jobId = createJob("postgres-stale");
        GenerateDocumentsCommand command = command();
        PreparedIdentity identity = prepareIdentity(jobId, command);
        long staleId = insertInProgress(identity, command,
                Instant.now().minus(documentProperties.staleAfter()).minusSeconds(60));
        Path directory = STORAGE.resolve("resumes").resolve(Long.toString(staleId));
        Files.createDirectories(directory);
        Path staleFinal = directory.resolve("resume.docx");
        Path stalePartial = directory.resolve(".jobpilot-stale.partial");
        Files.write(staleFinal, "stale-final".getBytes());
        Files.write(stalePartial, "stale-partial".getBytes());
        Files.setLastModifiedTime(stalePartial,
                FileTime.from(Instant.now().minusSeconds(600)));

        DocumentGenerationResult result = service.generate(jobId, command);

        assertThat(result.status()).isEqualTo(DocumentGenerationStatus.CREATED);
        assertThat(result.resumeVersionId()).isEqualTo(staleId);
        assertThat(resumes.count()).isEqualTo(1);
        ResumeVersion completed = resumes.findById(staleId).orElseThrow();
        assertThat(completed.getRenderStatus()).isEqualTo(DocumentRenderStatus.COMPLETED);
        assertThat(completed.getAttemptCount()).isEqualTo(2);
        assertThat(stalePartial).doesNotExist();
        assertValidArtifact(completed);
        assertThat(countFiles("resume.docx")).isEqualTo(1);
        assertThat(renderer.calls()).isEqualTo(1);
    }

    @Test
    void activeInProgressClaimIsNotStolenByACompetingRequest() throws Exception {
        long jobId = createJob("postgres-active");
        GenerateDocumentsCommand command = command();
        PreparedIdentity identity = prepareIdentity(jobId, command);
        long activeId = insertInProgress(identity, command, Instant.now());
        Path directory = STORAGE.resolve("resumes").resolve(Long.toString(activeId));
        Files.createDirectories(directory);
        Path sentinel = directory.resolve(".jobpilot-active.partial");
        Files.write(sentinel, "active-owner".getBytes());

        try (var executor = Executors.newSingleThreadExecutor()) {
            var competing = executor.submit(() -> service.generate(jobId, command));
            Thread.sleep(250);
            assertThat(competing.isDone()).isFalse();
            assertThat(renderer.calls()).isZero();
            assertThat(sentinel).exists();
            new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                    resumes.findByIdForUpdate(activeId).orElseThrow().fail(
                            com.jobpilot.resume.domain.DocumentFailureCategory.STALE_GENERATION,
                            Instant.now()));
            DocumentGenerationResult result = competing.get(10, TimeUnit.SECONDS);
            assertThat(result.status()).isEqualTo(DocumentGenerationStatus.GENERATION_FAILED);
        }

        ResumeVersion unchanged = resumes.findById(activeId).orElseThrow();
        assertThat(unchanged.getAttemptCount()).isEqualTo(1);
        assertThat(renderer.calls()).isZero();
        assertThat(resumes.count()).isEqualTo(1);
    }

    private DocumentGenerationResult generate(long jobId, GenerateDocumentsCommand command,
                                              CountDownLatch ready, CountDownLatch start)
            throws Exception {
        ready.countDown();
        assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
        return service.generate(jobId, command);
    }

    private PreparedIdentity prepareIdentity(long jobId, GenerateDocumentsCommand command) {
        var analysisResult = analysisService.analyze(jobId, true);
        return new TransactionTemplate(transactionManager).execute(status -> {
            var job = jobs.findById(jobId).orElseThrow();
            var profile = profiles.findByActiveTrue().orElseThrow();
            var analysis = analyses.findById(analysisResult.analysisId()).orElseThrow();
            var candidateFacts = CandidateDocumentFacts.from(profile);
            var jobFacts = JobDocumentFacts.from(job, analysis.getId(), analysis.getCacheKey(),
                    analysisResult.analysis());
            String key = cacheKeys.resume(jobFacts, candidateFacts, command.formats(),
                    command.useLlmDrafting(), contacts.requireValidContact());
            return new PreparedIdentity(job.getId(), profile.getId(), analysis.getId(), key);
        });
    }

    private long insertInProgress(PreparedIdentity identity, GenerateDocumentsCommand command,
                                  Instant updatedAt) {
        return new TransactionTemplate(transactionManager).execute(status -> {
            var job = jobs.findById(identity.jobId()).orElseThrow();
            var profile = profiles.findById(identity.profileId()).orElseThrow();
            var analysis = analyses.findById(identity.analysisId()).orElseThrow();
            ResumeVersion value = ResumeVersion.inProgress(job, profile, analysis,
                    identity.cacheKey(), documentProperties.resumeTemplateVersion(),
                    documentProperties.rendererVersion(), command.formats(),
                    cacheKeys.provider(false), cacheKeys.model(false), updatedAt);
            return resumes.saveAndFlush(value).getId();
        });
    }

    private long createJob(String externalId) {
        return processor.process(new RawJob("synthetic-postgres", externalId,
                "https://example.invalid/jobs/" + externalId, "Java Backend Intern",
                "Synthetic Company", "Bucharest, Romania",
                "Java Spring Boot SQL internship with REST API work and mentorship.",
                "INTERN", Instant.parse("2026-07-19T08:00:00Z"), null,
                "Synthetic PostgreSQL document fixture")).job().getId();
    }

    private GenerateDocumentsCommand command() {
        return new GenerateDocumentsCommand(false, Set.of(DocumentFormat.DOCX), false);
    }

    private void assertValidArtifact(ResumeVersion value) {
        assertThat(value.getDocxPath()).isEqualTo("resumes/" + value.getId() + "/resume.docx");
        assertThat(value.getDocxHash()).matches("[a-f0-9]{64}");
        assertThat(value.getDocxSize()).isPositive();
        Path path = STORAGE.resolve(value.getDocxPath());
        assertThat(path).isRegularFile();
        try {
            byte[] bytes = Files.readAllBytes(path);
            assertThat((long) bytes.length).isEqualTo(value.getDocxSize());
            assertThat(Hashing.sha256(bytes)).isEqualTo(value.getDocxHash());
        } catch (IOException exception) {
            throw new AssertionError(exception);
        }
    }

    private int factOwnerCount(String table, long winnerId) {
        return jdbc.queryForObject("select count(distinct resume_version_id) from " + table
                + " where resume_version_id = ?", Integer.class, winnerId);
    }

    private long countFiles(String filename) throws IOException {
        if (!Files.exists(STORAGE)) return 0;
        try (var paths = Files.walk(STORAGE)) {
            return paths.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().equals(filename)).count();
        }
    }

    private static Path temporaryStorage() {
        try {
            return Files.createTempDirectory("jobpilot-postgres-documents-");
        } catch (IOException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    private static void clearStorage() throws IOException {
        if (!Files.exists(STORAGE)) return;
        try (var paths = Files.walk(STORAGE)) {
            paths.sorted(Comparator.reverseOrder()).filter(path -> !path.equals(STORAGE))
                    .forEach(PostgresDocumentGenerationConcurrencyIT::delete);
        }
    }

    private static void delete(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException exception) {
            throw new AssertionError(exception);
        }
    }

    private record PreparedIdentity(long jobId, long profileId, long analysisId, String cacheKey) { }

    @TestConfiguration
    static class RendererConfiguration {
        @Bean
        @Primary
        ControlledResumeDocxRenderer controlledResumeDocxRenderer() {
            return new ControlledResumeDocxRenderer();
        }
    }

    static class ControlledResumeDocxRenderer extends ResumeDocxRenderer {
        private final AtomicInteger calls = new AtomicInteger();
        private volatile CountDownLatch entered;
        private volatile CountDownLatch release;

        @Override
        public byte[] render(com.jobpilot.resume.domain.ResumeDocumentModel model) {
            calls.incrementAndGet();
            CountDownLatch currentEntered = entered;
            CountDownLatch currentRelease = release;
            if (currentEntered != null && currentRelease != null) {
                currentEntered.countDown();
                try {
                    if (!currentRelease.await(15, TimeUnit.SECONDS)) {
                        throw new AssertionError("Timed out while controlling document rendering");
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(interrupted);
                }
            }
            return super.render(model);
        }

        void block(CountDownLatch entered, CountDownLatch release) {
            this.entered = entered;
            this.release = release;
        }

        void resetControl() {
            calls.set(0);
            entered = null;
            release = null;
        }

        int calls() {
            return calls.get();
        }
    }
}

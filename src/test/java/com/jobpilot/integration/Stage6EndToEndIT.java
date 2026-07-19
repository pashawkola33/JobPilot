package com.jobpilot.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobpilot.applications.application.ApplicationTrackerService;
import com.jobpilot.applications.domain.ApplicationStatus;
import com.jobpilot.applications.repository.ApplicationRepository;
import com.jobpilot.common.HealthController;
import com.jobpilot.config.JobPilotProperties;
import com.jobpilot.jobs.domain.RawJob;
import com.jobpilot.jobs.repository.JobRepository;
import com.jobpilot.llm.api.LlmProvider;
import com.jobpilot.llm.api.LlmProviderException;
import com.jobpilot.llm.api.LlmRequest;
import com.jobpilot.llm.api.LlmResponse;
import com.jobpilot.llm.application.JobAnalysisService;
import com.jobpilot.llm.domain.CandidateMatchStrength;
import com.jobpilot.llm.domain.CandidateStrength;
import com.jobpilot.llm.domain.EvidenceReference;
import com.jobpilot.llm.domain.EvidenceSource;
import com.jobpilot.llm.domain.JobAnalysisData;
import com.jobpilot.llm.domain.JobAnalysisResultStatus;
import com.jobpilot.llm.domain.LlmFailureCategory;
import com.jobpilot.llm.repository.JobAnalysisRepository;
import com.jobpilot.manualurl.application.ManualJobPersistenceService;
import com.jobpilot.manualurl.application.ManualJobUrlService;
import com.jobpilot.manualurl.domain.ManualJobStatus;
import com.jobpilot.manualurl.fetch.ManualAtsResolver;
import com.jobpilot.manualurl.fetch.ManualUrlPolicy;
import com.jobpilot.manualurl.fetch.SafeManualPageFetcher;
import com.jobpilot.manualurl.fetch.ValidatedManualUrl;
import com.jobpilot.manualurl.parse.DeterministicManualJobParser;
import com.jobpilot.resume.application.ApplicationDocumentSelectionService;
import com.jobpilot.resume.application.DocumentGenerationStatus;
import com.jobpilot.resume.application.GenerateDocumentsCommand;
import com.jobpilot.resume.application.ResumeGenerationService;
import com.jobpilot.resume.domain.DocumentFormat;
import com.jobpilot.resume.domain.DocumentRenderStatus;
import com.jobpilot.resume.repository.CoverNoteRepository;
import com.jobpilot.resume.repository.ResumeVersionRepository;
import com.jobpilot.support.TestProperties;
import com.jobpilot.telegram.api.TelegramClient;
import com.jobpilot.telegram.api.TelegramTransportException;
import com.jobpilot.telegram.api.TelegramUpdate;
import com.jobpilot.telegram.commands.TelegramAuthorizationPolicy;
import com.jobpilot.telegram.commands.TelegramCommandDispatcher;
import com.jobpilot.telegram.commands.TelegramCommandParser;
import com.jobpilot.telegram.commands.TelegramMessageRenderer;
import com.jobpilot.telegram.polling.TelegramBotStateRepository;
import com.jobpilot.telegram.polling.TelegramBotStateService;
import com.jobpilot.telegram.polling.TelegramUpdatePoller;
import com.jobpilot.telegram.polling.TelegramUpdateProcessor;
import java.io.ByteArrayInputStream;
import java.net.InetAddress;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.apache.pdfbox.Loader;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = {
        "jobpilot.scheduling.fetch-cron=0 0 0 1 1 *",
        "jobpilot.scheduling.digest-cron=0 0 0 1 1 *",
        "jobpilot.telegram.commands-enabled=false",
        "jobpilot.telegram.bot-username=JobPilotBot",
        "jobpilot.telegram.allowed-chat-id=-100555",
        "jobpilot.telegram.allowed-user-id=777",
        "jobpilot.llm.enabled=true",
        "jobpilot.llm.provider=openai",
        "jobpilot.llm.base-url=https://api.openai.com/v1",
        "jobpilot.llm.api-key=obviously-fake-secret",
        "jobpilot.llm.model=stage6-synthetic-model",
        "jobpilot.llm.connect-timeout=1s",
        "jobpilot.llm.response-timeout=2s",
        "jobpilot.llm.max-input-tokens=100000",
        "jobpilot.llm.max-output-tokens=2000",
        "jobpilot.llm.max-retries=1",
        "jobpilot.llm.request-budget-usd=1",
        "jobpilot.llm.daily-budget-usd=10",
        "jobpilot.llm.monthly-budget-usd=100",
        "jobpilot.llm.input-cost-per-million-tokens=1",
        "jobpilot.llm.output-cost-per-million-tokens=2",
        "jobpilot.documents.enabled=true",
        "jobpilot.documents.max-docx-bytes=2097152",
        "jobpilot.documents.max-pdf-bytes=2097152",
        "jobpilot.documents.resume-template-version=resume-stage6-v1",
        "jobpilot.documents.cover-note-template-version=cover-stage6-v1",
        "jobpilot.documents.renderer-version=renderer-stage6-v1",
        "jobpilot.documents.max-preview-characters=4000",
        "jobpilot.documents.stale-after=2m",
        "jobpilot.documents.contact-cache-hmac-key="
                + "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
        "jobpilot.documents.contact.email=student@example.test",
        "jobpilot.documents.contact.phone=+1 202 555 0100",
        "jobpilot.documents.contact.github-url=https://example.test/code",
        "jobpilot.maintenance.enabled=false"
})
class Stage6EndToEndIT {
    private static final Path STORAGE = temporaryStorage();

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("jobpilot.documents.storage-root", STORAGE::toString);
    }

    @Autowired private ManualJobPersistenceService manualPersistence;
    @Autowired private JobPilotProperties properties;
    @Autowired private JobRepository jobs;
    @Autowired private JobAnalysisService analysis;
    @Autowired private JobAnalysisRepository analyses;
    @Autowired private ResumeGenerationService generation;
    @Autowired private ResumeVersionRepository resumes;
    @Autowired private CoverNoteRepository coverNotes;
    @Autowired private ApplicationRepository applications;
    @Autowired private ApplicationDocumentSelectionService selection;
    @Autowired private ApplicationTrackerService tracker;
    @Autowired private TelegramCommandDispatcher dispatcher;
    @Autowired private TelegramMessageRenderer renderer;
    @Autowired private TelegramBotStateService telegramState;
    @Autowired private TelegramBotStateRepository telegramStates;
    @Autowired private HealthController health;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ObjectMapper mapper;
    @MockBean private LlmProvider provider;

    @BeforeEach
    void clean() throws Exception {
        reset(provider);
        when(provider.execute(any())).thenAnswer(invocation -> {
            LlmRequest request = invocation.getArgument(0);
            if (request.outputSchema().toString().contains("roleSummary")) {
                return new LlmResponse(validAnalysisJson(), 120L, 40L);
            }
            throw new LlmProviderException(LlmFailureCategory.PROVIDER_ERROR,
                    "Synthetic document-draft fallback");
        });
        jdbc.update("delete from application_status_history");
        applications.deleteAll();
        coverNotes.deleteAll();
        resumes.deleteAll();
        jdbc.update("delete from llm_usage_events");
        analyses.deleteAll();
        jdbc.update("delete from llm_budget_reservations");
        jdbc.update("delete from job_requirements");
        jdbc.update("delete from job_scores");
        jobs.deleteAll();
        telegramStates.deleteAll();
        clearStorage();
    }

    @AfterAll
    static void removeStorage() throws Exception {
        if (!Files.exists(STORAGE)) return;
        try (var paths = Files.walk(STORAGE)) {
            paths.sorted(Comparator.reverseOrder()).forEach(Stage6EndToEndIT::delete);
        }
    }

    @Test
    void completeHumanControlledWorkflowSurvivesReplayAndPollerReconstruction() throws Exception {
        URI publicUrl = URI.create("https://jobs.example.test/vacancies/stage-6");
        ManualUrlPolicy policy = mock(ManualUrlPolicy.class);
        ManualAtsResolver ats = mock(ManualAtsResolver.class);
        SafeManualPageFetcher fetcher = mock(SafeManualPageFetcher.class);
        DeterministicManualJobParser parser = mock(DeterministicManualJobParser.class);
        ValidatedManualUrl validated = new ValidatedManualUrl(publicUrl,
                List.of(InetAddress.getByName("93.184.216.34")));
        RawJob synthetic = new RawJob("manual", "stage-6", publicUrl.toString(),
                "Java Backend Intern", "Synthetic Company", "Bucharest, Romania",
                "Java Spring Boot internship building REST APIs with PostgreSQL, SQL, JUnit "
                        + "and mentorship in a collaborative backend team.",
                "Internship", Instant.parse("2026-07-19T08:00:00Z"), null,
                "Synthetic Stage 6 public fixture");
        when(policy.validate(publicUrl.toString())).thenReturn(validated);
        when(ats.fetch(publicUrl)).thenReturn(Optional.of(synthetic));
        ManualJobUrlService manual = new ManualJobUrlService(policy, ats, fetcher, parser,
                manualPersistence, properties);

        var created = manual.submit(publicUrl.toString());
        var duplicate = manual.submit(publicUrl.toString());

        assertThat(created.status()).isEqualTo(ManualJobStatus.CREATED);
        assertThat(duplicate.status()).isEqualTo(ManualJobStatus.ALREADY_EXISTS);
        assertThat(duplicate.jobId()).isEqualTo(created.jobId());
        long jobId = created.jobId();
        assertThat(jdbc.queryForObject("select count(*) from job_requirements where job_id = ?",
                Integer.class, jobId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from job_scores where job_id = ?",
                Integer.class, jobId)).isEqualTo(1);

        var analyzed = analysis.analyze(jobId, true);
        assertThat(analyzed.status()).isEqualTo(JobAnalysisResultStatus.CREATED);
        assertThat(analyzed.analysis().deterministicFallbackUsed()).isFalse();
        assertThat(applications.findByJobId(jobId)).isEmpty();
        var generated = generation.generate(jobId, new GenerateDocumentsCommand(true,
                Set.of(DocumentFormat.DOCX, DocumentFormat.PDF), true));
        assertThat(generated.status()).isIn(DocumentGenerationStatus.FALLBACK,
                DocumentGenerationStatus.BUDGET_EXCEEDED);
        assertThat(applications.findByJobId(jobId)).isEmpty();
        assertThat(analyses.findByJobIdOrderByCreatedAtDesc(jobId)).hasSize(1);
        assertThat(resumes.findByJobIdOrderByGeneratedAtDesc(jobId)).singleElement()
                .satisfies(value -> {
                    assertThat(value.getJob().getId()).isEqualTo(jobId);
                    assertThat(value.getRenderStatus()).isEqualTo(DocumentRenderStatus.COMPLETED);
                    assertThat(value.getProfileVersion()).isPositive();
                });
        assertThat(coverNotes.findByJobIdOrderByGeneratedAtDesc(jobId)).singleElement()
                .satisfies(value -> {
                    assertThat(value.getJob().getId()).isEqualTo(jobId);
                    assertThat(value.getResumeVersion().getId()).isEqualTo(generated.resumeVersionId());
                    assertThat(value.getCandidateProfile().getId())
                            .isEqualTo(resumes.findById(generated.resumeVersionId()).orElseThrow()
                                    .getCandidateProfile().getId());
                });
        try (XWPFDocument ignored = new XWPFDocument(new ByteArrayInputStream(
                generation.downloadResume(generated.resumeVersionId(), DocumentFormat.DOCX).bytes()))) {
            assertThat(ignored.getParagraphs()).isNotEmpty();
        }
        try (var pdf = Loader.loadPDF(generation.downloadResume(
                generated.resumeVersionId(), DocumentFormat.PDF).bytes())) {
            assertThat(pdf.getNumberOfPages()).isBetween(1, 2);
        }

        FakeTelegramClient client = new FakeTelegramClient();
        TelegramUpdatePoller poller = poller(client);
        client.failSends = true;
        client.addBatch(message(1, "/save " + jobId));
        assertThat(poller.pollOnce()).isTrue();
        assertThat(applications.findByJobId(jobId).orElseThrow().getStatus())
                .isEqualTo(ApplicationStatus.SAVED);
        assertThat(telegramState.load().orElseThrow().lastProcessedUpdateId()).isEqualTo(1L);

        client.failSends = false;
        client.addBatch(message(2, "/analyze " + jobId),
                message(3, "/documents " + jobId + " all both"),
                message(4, "/resumes " + jobId), message(5, "/covernotes " + jobId));
        poller.pollOnce();
        assertThat(analyses.findByJobIdOrderByCreatedAtDesc(jobId)).hasSize(1);
        assertThat(resumes.findByJobIdOrderByGeneratedAtDesc(jobId)).hasSize(1);
        assertThat(coverNotes.findByJobIdOrderByGeneratedAtDesc(jobId)).hasSize(1);
        verify(provider, times(3)).execute(any());

        client.addBatch(message(6, "/selectdocs " + jobId + " "
                + generated.resumeVersionId() + " " + generated.coverNoteId()));
        poller.pollOnce();
        var selected = applications.findByJobId(jobId).orElseThrow();
        assertThat(selected.getStatus()).isEqualTo(ApplicationStatus.SAVED);
        assertThat(selected.getResumeVersion().getId()).isEqualTo(generated.resumeVersionId());
        assertThat(selected.getCoverNote().getId()).isEqualTo(generated.coverNoteId());
        assertThat(tracker.history(jobId)).hasSize(1);

        client.addBatch(message(7, "/applied " + jobId),
                message(8, "/interview " + jobId + " 2026-08-01T14:30:00+03:00"),
                message(9, "/followup " + jobId + " 2026-08-05"),
                message(10, "/offer " + jobId), message(11, "/history " + jobId));
        poller.pollOnce();
        var offered = applications.findByJobId(jobId).orElseThrow();
        assertThat(offered.getStatus()).isEqualTo(ApplicationStatus.OFFER);
        assertThat(offered.getApplicationDate()).isNotNull();
        assertThat(offered.getInterviewDate()).isEqualTo(Instant.parse("2026-08-01T11:30:00Z"));
        assertThat(offered.getNextFollowUpDate()).hasToString("2026-08-05");
        assertThat(tracker.history(jobId)).extracting(value -> value.newStatus())
                .containsExactly(ApplicationStatus.SAVED, ApplicationStatus.APPLIED,
                        ApplicationStatus.INTERVIEW, ApplicationStatus.OFFER);

        long resumeCount = resumes.count();
        client.addBatch(message(11, "/documents " + jobId + " all both"),
                message(12, "/documents " + jobId + " all both"));
        TelegramUpdatePoller reconstructed = poller(client);
        reconstructed.pollOnce();
        assertThat(client.requestedOffsets).contains(12L);
        assertThat(resumes.count()).isEqualTo(resumeCount);
        verify(provider, times(3)).execute(any());
        assertThat(telegramState.load().orElseThrow().lastProcessedUpdateId()).isEqualTo(12L);

        client.addBatch(message(13, -100555, 999, "/withdraw " + jobId),
                message(14, "/status " + jobId));
        reconstructed.pollOnce();
        assertThat(applications.findByJobId(jobId).orElseThrow().getStatus())
                .isEqualTo(ApplicationStatus.OFFER);
        assertThat(telegramState.load().orElseThrow().lastProcessedUpdateId()).isEqualTo(14L);
        assertThat(client.sentHtml).allSatisfy(html -> {
            assertThat(html.length()).isLessThanOrEqualTo(4096);
            assertThat(html).doesNotContain("DOCUMENT_CONTACT", "contact-cache", "sha256",
                    STORAGE.toString(), "student@example.test", "obviously-fake");
        });
        assertThat(health.health().getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(jdbc.queryForObject(
                "select count(*) from flyway_schema_history where success", Integer.class))
                .isEqualTo(5);
    }

    private TelegramUpdatePoller poller(FakeTelegramClient client) {
        JobPilotProperties telegramProperties = TestProperties.create(
                new JobPilotProperties.Telegram("obviously-fake-token", "-100555",
                        "JobPilotBot", true, "-100555", "777", Duration.ofSeconds(1),
                        Duration.ZERO, 50, 3, false));
        TelegramUpdateProcessor processor = new TelegramUpdateProcessor(client,
                new TelegramAuthorizationPolicy(telegramProperties),
                new TelegramCommandParser(telegramProperties), dispatcher, renderer,
                telegramProperties);
        return new TelegramUpdatePoller(client, processor, telegramState, telegramProperties);
    }

    private String validAnalysisJson() {
        JobAnalysisData data = new JobAnalysisData("Synthetic Java backend internship",
                List.of("Java", "Spring Boot", "SQL"), List.of(),
                List.of("Build REST APIs"), null, null, null,
                "Bucharest, Romania", null,
                List.of(new CandidateStrength("spring-boot", CandidateMatchStrength.MATCH)),
                List.of(), List.of("Work authorization is unknown"),
                List.of(
                        new EvidenceReference(EvidenceSource.VACANCY,
                                "job.description", "Java Spring Boot internship"),
                        new EvidenceReference(EvidenceSource.CANDIDATE_SKILL,
                                "spring-boot", "Spring Boot. Verified technical skill")),
                80, false);
        try {
            return mapper.writeValueAsString(data);
        } catch (JsonProcessingException exception) {
            throw new AssertionError(exception);
        }
    }

    private TelegramUpdate message(long updateId, String text) {
        return message(updateId, -100555, 777, text);
    }

    private TelegramUpdate message(long updateId, long chat, long user, String text) {
        return new TelegramUpdate(updateId,
                new TelegramUpdate.TelegramMessage(updateId,
                        new TelegramUpdate.TelegramUser(user),
                        new TelegramUpdate.TelegramChat(chat), text), null);
    }

    private static Path temporaryStorage() {
        try {
            return Files.createTempDirectory("jobpilot-stage6-e2e-");
        } catch (java.io.IOException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    private static void clearStorage() throws Exception {
        if (!Files.exists(STORAGE)) return;
        try (var paths = Files.walk(STORAGE)) {
            paths.sorted(Comparator.reverseOrder()).filter(path -> !path.equals(STORAGE))
                    .forEach(Stage6EndToEndIT::delete);
        }
    }

    private static void delete(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (java.io.IOException exception) {
            throw new AssertionError(exception);
        }
    }

    private static final class FakeTelegramClient implements TelegramClient {
        private final ArrayDeque<List<TelegramUpdate>> batches = new ArrayDeque<>();
        private final List<Long> requestedOffsets = new ArrayList<>();
        private final List<String> sentHtml = new ArrayList<>();
        private boolean failSends;

        void addBatch(TelegramUpdate... updates) {
            batches.add(List.of(updates));
        }

        @Override
        public List<TelegramUpdate> getUpdates(Long offset, Duration timeout, int limit) {
            requestedOffsets.add(offset);
            return batches.isEmpty() ? List.of() : batches.removeFirst();
        }

        @Override
        public void sendMessage(String chatId, String html,
                                List<List<Map<String, String>>> buttons) {
            if (failSends) throw new TelegramTransportException(
                    TelegramTransportException.Operation.SEND_MESSAGE);
            sentHtml.add(html);
        }

        @Override
        public void answerCallbackQuery(String callbackQueryId, String text) {
        }
    }
}

package com.jobpilot.resume.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;

import com.jobpilot.applications.domain.ApplicationStatus;
import com.jobpilot.applications.domain.ApplicationStatusChangeSource;
import com.jobpilot.applications.repository.ApplicationRepository;
import com.jobpilot.applications.application.ApplicationTrackerService;
import com.jobpilot.jobs.domain.RawJob;
import com.jobpilot.jobs.repository.JobRequirementRepository;
import com.jobpilot.jobs.repository.JobRepository;
import com.jobpilot.jobs.repository.JobScoreRepository;
import com.jobpilot.jobs.service.JobProcessor;
import com.jobpilot.llm.api.LlmProvider;
import com.jobpilot.llm.repository.JobAnalysisRepository;
import com.jobpilot.llm.repository.LlmBudgetReservationRepository;
import com.jobpilot.llm.repository.LlmUsageEventRepository;
import com.jobpilot.resume.domain.DocumentFormat;
import com.jobpilot.resume.domain.DocumentRenderStatus;
import com.jobpilot.resume.repository.CoverNoteRepository;
import com.jobpilot.resume.repository.ResumeVersionRepository;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
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
class ResumeGenerationServiceTest {
    private static final Path STORAGE = temporaryStorage();

    @DynamicPropertySource
    static void storage(DynamicPropertyRegistry registry) {
        registry.add("jobpilot.documents.storage-root", STORAGE::toString);
    }

    @Autowired private ResumeGenerationService service;
    @Autowired private ApplicationDocumentSelectionService selection;
    @Autowired private ApplicationTrackerService tracker;
    @Autowired private JobProcessor processor;
    @Autowired private JobRepository jobs;
    @Autowired private JobRequirementRepository requirements;
    @Autowired private JobScoreRepository scores;
    @Autowired private ResumeVersionRepository resumes;
    @Autowired private CoverNoteRepository coverNotes;
    @Autowired private JobAnalysisRepository analyses;
    @Autowired private LlmBudgetReservationRepository reservations;
    @Autowired private LlmUsageEventRepository usage;
    @Autowired private ApplicationRepository applications;
    @Autowired private JdbcTemplate jdbc;
    @MockBean private LlmProvider provider;

    @BeforeEach
    void cleanDatabase() throws Exception {
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
    void concurrentIdenticalGenerationCreatesAtMostOneCompletedVersion() throws Exception {
        long jobId = processor.process(new RawJob("synthetic", "stage5-concurrent",
                "https://example.invalid/jobs/stage5-concurrent", "Java Backend Intern",
                "Synthetic Company", "Bucharest, Romania",
                "Java Spring Boot SQL internship with REST API work and mentorship.",
                "INTERN", Instant.parse("2026-07-19T08:00:00Z"), null,
                "Synthetic concurrent fixture")).job().getId();
        GenerateDocumentsCommand command = new GenerateDocumentsCommand(false,
                Set.of(DocumentFormat.DOCX, DocumentFormat.PDF), false);
        CountDownLatch start = new CountDownLatch(1);

        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> {
                start.await(5, TimeUnit.SECONDS);
                return service.generate(jobId, command);
            });
            var second = executor.submit(() -> {
                start.await(5, TimeUnit.SECONDS);
                return service.generate(jobId, command);
            });
            start.countDown();
            DocumentGenerationResult a = first.get(15, TimeUnit.SECONDS);
            DocumentGenerationResult b = second.get(15, TimeUnit.SECONDS);

            assertThat(java.util.List.of(a.status(), b.status()))
                    .isSubsetOf(DocumentGenerationStatus.CREATED,
                            DocumentGenerationStatus.CACHED);
            assertThat(java.util.List.of(a.status(), b.status()))
                    .contains(DocumentGenerationStatus.CREATED);
            assertThat(resumes.count()).isEqualTo(1);
            assertThat(resumes.findAll()).singleElement().satisfies(value ->
                    assertThat(value.getRenderStatus()).isEqualTo(DocumentRenderStatus.COMPLETED));
        }
        verifyNoInteractions(provider);
    }

    private static Path temporaryStorage() {
        try {
            return Files.createTempDirectory("jobpilot-stage5-");
        } catch (java.io.IOException exception) {
            throw new ExceptionInInitializerError(exception);
        }
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

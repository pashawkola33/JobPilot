package com.jobpilot.jobs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jobpilot.applications.domain.ApplicationRecord;
import com.jobpilot.applications.domain.ApplicationStatus;
import com.jobpilot.applications.domain.ApplicationStatusChangeSource;
import com.jobpilot.applications.repository.ApplicationRepository;
import com.jobpilot.applications.repository.ApplicationStatusHistoryRepository;
import com.jobpilot.applications.application.ApplicationTrackerService;
import com.jobpilot.candidate.domain.CandidateProfile;
import com.jobpilot.candidate.domain.CandidateSkillCategory;
import com.jobpilot.candidate.domain.ProjectType;
import com.jobpilot.candidate.repository.CandidateLanguageRepository;
import com.jobpilot.candidate.repository.CandidateProfileRepository;
import com.jobpilot.candidate.repository.CandidateProjectBulletRepository;
import com.jobpilot.candidate.repository.CandidateProjectRepository;
import com.jobpilot.candidate.repository.CandidateSkillRepository;
import com.jobpilot.config.JobPilotProperties;
import com.jobpilot.jobs.domain.Job;
import com.jobpilot.jobs.domain.JobStatus;
import com.jobpilot.jobs.domain.RawJob;
import com.jobpilot.jobs.domain.RemoteType;
import com.jobpilot.jobs.repository.JobRepository;
import com.jobpilot.jobs.repository.JobRequirementRepository;
import com.jobpilot.jobs.repository.JobScoreRepository;
import com.jobpilot.jobs.service.JobProcessor;
import com.jobpilot.llm.domain.LlmOperationType;
import com.jobpilot.llm.domain.LlmUsageEvent;
import com.jobpilot.llm.domain.LlmUsageStatus;
import com.jobpilot.llm.domain.JobAnalysis;
import com.jobpilot.llm.domain.JobAnalysisData;
import com.jobpilot.llm.domain.JobAnalysisJson;
import com.jobpilot.llm.domain.CandidateStrength;
import com.jobpilot.llm.domain.CandidateMatchStrength;
import com.jobpilot.llm.domain.EvidenceReference;
import com.jobpilot.llm.domain.EvidenceSource;
import com.jobpilot.llm.budget.LlmBudgetReservation;
import com.jobpilot.llm.budget.LlmBudgetControl;
import com.jobpilot.llm.budget.LlmBudgetDecision;
import com.jobpilot.llm.budget.LlmBudgetReservationResult;
import com.jobpilot.llm.budget.LlmBudgetReservationStatus;
import com.jobpilot.llm.budget.LlmBudgetService;
import com.jobpilot.llm.budget.LlmCostCalculator;
import com.jobpilot.llm.repository.JobAnalysisRepository;
import com.jobpilot.llm.repository.LlmBudgetReservationRepository;
import com.jobpilot.llm.repository.LlmBudgetControlRepository;
import com.jobpilot.llm.repository.LlmUsageEventRepository;
import com.jobpilot.manualurl.application.ManualJobPersistenceService;
import com.jobpilot.resume.domain.CoverNote;
import com.jobpilot.resume.domain.ResumeVersion;
import com.jobpilot.resume.repository.CoverNoteRepository;
import com.jobpilot.resume.repository.CoverNoteFactReferenceRepository;
import com.jobpilot.resume.repository.ResumeVersionLanguageRepository;
import com.jobpilot.resume.repository.ResumeVersionProjectBulletRepository;
import com.jobpilot.resume.repository.ResumeVersionProjectRepository;
import com.jobpilot.resume.repository.ResumeVersionRepository;
import com.jobpilot.resume.repository.ResumeVersionSkillRepository;
import com.jobpilot.telegram.polling.TelegramBotState;
import com.jobpilot.telegram.polling.TelegramBotStateRepository;
import com.jobpilot.support.TestProperties;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Runs Flyway and the repositories against a real PostgreSQL container.
 * Skipped automatically when Docker is not available on the machine.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@Transactional
class PostgresPersistenceIT {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    private JobRepository jobs;
    @Autowired
    private JobRequirementRepository requirements;
    @Autowired
    private JobScoreRepository scores;
    @Autowired
    private JobProcessor processor;
    @Autowired
    private ManualJobPersistenceService manualJobPersistence;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private CandidateProfileRepository candidateProfiles;
    @Autowired
    private CandidateSkillRepository candidateSkills;
    @Autowired
    private CandidateLanguageRepository candidateLanguages;
    @Autowired
    private CandidateProjectRepository candidateProjects;
    @Autowired
    private CandidateProjectBulletRepository candidateProjectBullets;
    @Autowired
    private ApplicationRepository applications;
    @Autowired
    private ApplicationStatusHistoryRepository applicationHistory;
    @Autowired
    private ApplicationTrackerService applicationTracker;
    @Autowired
    private ResumeVersionRepository resumeVersions;
    @Autowired
    private ResumeVersionSkillRepository resumeVersionSkills;
    @Autowired
    private ResumeVersionProjectRepository resumeVersionProjects;
    @Autowired
    private ResumeVersionProjectBulletRepository resumeVersionProjectBullets;
    @Autowired
    private ResumeVersionLanguageRepository resumeVersionLanguages;
    @Autowired
    private CoverNoteRepository coverNotes;
    @Autowired
    private CoverNoteFactReferenceRepository coverNoteFactReferences;
    @Autowired
    private LlmUsageEventRepository llmUsageEvents;
    @Autowired
    private JobAnalysisRepository jobAnalyses;
    @Autowired
    private LlmBudgetReservationRepository llmBudgetReservations;
    @Autowired
    private LlmBudgetControlRepository llmBudgetControls;
    @Autowired
    private JobAnalysisJson jobAnalysisJson;
    @Autowired
    private TelegramBotStateRepository telegramBotStates;
    @Autowired
    private EntityManager entityManager;
    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    void flywayMigratesTheSchemaOnRealPostgres() {
        Integer applied = jdbcTemplate.queryForObject(
                "select count(*) from flyway_schema_history where success", Integer.class);
        assertThat(applied).isEqualTo(5);
        assertThat(jdbcTemplate.queryForList(
                "select table_name from information_schema.tables where table_schema = 'public'",
                String.class))
                .contains("jobs", "job_requirements", "job_scores", "source_fetch_logs",
                        "candidate_profiles", "candidate_skills", "candidate_languages",
                        "candidate_projects", "candidate_project_bullets", "applications",
                        "resume_versions", "resume_version_skills", "resume_version_projects",
                        "resume_version_project_bullets", "resume_version_languages",
                        "cover_notes", "cover_note_fact_references", "llm_usage_events",
                        "telegram_bot_state", "application_status_history",
                        "llm_budget_control", "llm_budget_reservations", "job_analyses");
    }

    @Test
    void roundTripsAProcessedJobThroughAllRepositories() {
        var result = processor.process(raw("rt-1", "https://example.com/jobs/rt-1",
                "Java internship in Bucharest, Romania with mentorship. Java, Spring Boot, SQL."));

        assertThat(result.newlyCreated()).isTrue();
        assertThat(jobs.findByCanonicalUrl("https://example.com/jobs/rt-1")).isPresent();
        assertThat(requirements.findByJobId(result.job().getId())).isPresent();
        assertThat(scores.findByJobId(result.job().getId())).isPresent();
    }

    @Test
    void persistsManualVacancyThroughExistingPipelineAndDeduplicatesIt() {
        RawJob manual = new RawJob(
                "manual-schema-org", "manual-pg-1",
                "https://PUBLIC.example/jobs/manual-pg-1?utm_source=feed&language=java",
                "Java Backend Intern", "Public Example", "Bucharest, Romania",
                "Java backend internship with mentorship using Java, Spring Boot, SQL, and Docker. "
                        + "Candidates collaborate with the engineering team and build production services.",
                "INTERN", Instant.parse("2026-07-18T10:00:00Z"), null,
                "deterministic schema.org fixture");

        var created = manualJobPersistence.persist(manual);
        var duplicate = manualJobPersistence.persist(manual);

        assertThat(created.newlyCreated()).isTrue();
        assertThat(duplicate.newlyCreated()).isFalse();
        assertThat(duplicate.job().getId()).isEqualTo(created.job().getId());
        assertThat(created.job().getCanonicalUrl())
                .isEqualTo("https://public.example/jobs/manual-pg-1?language=java");
        assertThat(jobs.findByCanonicalUrl(created.job().getCanonicalUrl())).isPresent();
        assertThat(requirements.findByJobId(created.job().getId()))
                .hasValueSatisfying(requirement -> assertThat(requirement.toValue().technologies())
                        .contains("Spring Boot", "Docker"));
        assertThat(scores.findByJobId(created.job().getId())).isPresent();
        assertThat(jobs.count()).isEqualTo(1);
    }

    @Test
    void enforcesCanonicalUrlUniqueness() {
        jobs.saveAndFlush(job("u1", "https://example.com/jobs/unique", Instant.parse("2026-07-16T10:00:00Z")));

        assertThatThrownBy(() -> jobs.saveAndFlush(
                job("u2", "https://example.com/jobs/unique", Instant.parse("2026-07-16T10:00:00Z"))))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void expiresStaleJobs() {
        jobs.saveAndFlush(job("x1", "https://example.com/jobs/x1", Instant.parse("2026-05-01T12:00:00Z")));
        jobs.saveAndFlush(job("x2", "https://example.com/jobs/x2", Instant.parse("2026-07-16T12:00:00Z")));

        int expired = jobs.expireStale(Instant.parse("2026-06-17T12:00:00Z"));

        assertThat(expired).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "select status from jobs where external_id = 'x1'", String.class))
                .isEqualTo(JobStatus.EXPIRED.name());
    }

    @Test
    void cascadesJobDeletionToRequirementsAndScores() {
        var result = processor.process(raw("cd-1", "https://example.com/jobs/cd-1",
                "Java internship in Bucharest with mentorship. Java and SQL."));
        Long jobId = result.job().getId();

        jobs.deleteById(jobId);
        jobs.flush();

        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from job_requirements where job_id = ?", Integer.class, jobId)).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from job_scores where job_id = ?", Integer.class, jobId)).isZero();
    }

    @Test
    void roundTripsCandidateProfileAndAllVerifiedFactTypes() {
        CandidateProfile profile = candidateProfiles.findByActiveTrue().orElseThrow();
        entityManager.flush();
        entityManager.clear();

        CandidateProfile reloaded = candidateProfiles.findByProfileVersion(1).orElseThrow();

        assertThat(reloaded.getFullName()).isEqualTo("Pavlo Sushkov");
        assertThat(reloaded.getCommercialJavaExperienceYears()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(candidateSkills.findByCandidateProfileIdOrderByDisplayOrder(reloaded.getId()))
                .hasSize(65).first().extracting(skill -> skill.getStableKey()).isEqualTo("java-17");
        assertThat(candidateLanguages.findByCandidateProfileIdOrderByDisplayOrder(reloaded.getId()))
                .extracting(language -> language.getLanguage())
                .containsExactly("English", "Ukrainian", "Russian", "German");
        var projects = candidateProjects.findByCandidateProfileIdOrderByDisplayOrder(reloaded.getId());
        assertThat(projects).hasSize(4);
        assertThat(projects.getFirst().getTechnologies()).contains("Java 21", "Spring Boot", "React");
        assertThat(candidateProjectBullets.findByProjectIdOrderByDisplayOrder(projects.getFirst().getId()))
                .hasSize(21).first().extracting(bullet -> bullet.getStableKey())
                .isEqualTo("personal-platform");
    }

    @Test
    void databaseEnforcesSingleActiveCandidateProfile() {
        CandidateProfile secondActive = profile(90, true);

        assertThatThrownBy(() -> candidateProfiles.saveAndFlush(secondActive))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void persistsApplicationStatusAndEnforcesOneApplicationPerJob() {
        Instant now = Instant.parse("2026-07-19T10:00:00Z");
        Job job = jobs.saveAndFlush(job("application", "https://example.com/jobs/application", now));
        ApplicationRecord saved = applications.saveAndFlush(new ApplicationRecord(
                job, ApplicationStatus.SAVED, null, null, null, "Review vacancy",
                null, null, null, now, now));

        assertThat(applications.findByJobId(job.getId())).contains(saved);
        assertThat(saved.getStatus()).isEqualTo(ApplicationStatus.SAVED);
        assertThatThrownBy(() -> applications.saveAndFlush(new ApplicationRecord(
                job, ApplicationStatus.APPLIED, now, null, null, null,
                null, null, null, now, now)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void tracksApplicationTransitionsAndImmutableHistoryWithOptimisticVersioning() {
        Instant now = Instant.parse("2026-07-19T10:30:00Z");
        Job job = jobs.saveAndFlush(job("tracker-pg", "https://example.com/jobs/tracker-pg", now));

        var saved = applicationTracker.transition(job.getId(), ApplicationStatus.SAVED,
                null, null, ApplicationStatusChangeSource.TELEGRAM_COMMAND);
        applicationTracker.transition(job.getId(), ApplicationStatus.APPLIED,
                null, null, ApplicationStatusChangeSource.TELEGRAM_COMMAND);
        var interview = applicationTracker.transition(job.getId(), ApplicationStatus.INTERVIEW,
                Instant.parse("2026-08-01T10:00:00Z"), null,
                ApplicationStatusChangeSource.TELEGRAM_COMMAND);
        var duplicate = applicationTracker.transition(job.getId(), ApplicationStatus.INTERVIEW,
                Instant.parse("2026-08-01T10:00:00Z"), null,
                ApplicationStatusChangeSource.TELEGRAM_CALLBACK);

        entityManager.flush();
        assertThat(interview.application().status()).isEqualTo(ApplicationStatus.INTERVIEW);
        assertThat(duplicate.changed()).isFalse();
        assertThat(applicationHistory.findByApplicationIdOrderByChangedAtAscIdAsc(
                saved.application().applicationId()))
                .extracting(change -> change.getNewStatus())
                .containsExactly(ApplicationStatus.SAVED, ApplicationStatus.APPLIED,
                        ApplicationStatus.INTERVIEW);
        assertThat(jdbcTemplate.queryForObject(
                "select version from applications where id = ?", Long.class,
                saved.application().applicationId())).isGreaterThanOrEqualTo(2L);
    }

    @Test
    void persistsResumeVersionFactReferencesAndCascadesResumeDeletion() {
        Instant now = Instant.parse("2026-07-19T11:00:00Z");
        Job job = jobs.saveAndFlush(job("resume", "https://example.com/jobs/resume", now));
        CandidateProfile profile = candidateProfiles.findByActiveTrue().orElseThrow();
        var skill = candidateSkills.findByCandidateProfileIdOrderByDisplayOrder(profile.getId()).getFirst();
        var language = candidateLanguages.findByCandidateProfileIdOrderByDisplayOrder(profile.getId())
                .stream().filter(value -> value.isAllowedInCv()).findFirst().orElseThrow();
        var project = candidateProjects.findByCandidateProfileIdOrderByDisplayOrder(profile.getId()).getFirst();
        var bullet = candidateProjectBullets.findByProjectIdOrderByDisplayOrder(project.getId()).getFirst();
        ResumeVersion resume = new ResumeVersion(job, profile, profile.getProfileVersion(),
                "JAVA DEVELOPER INTERN", "Verified summary", "Plain text preview",
                "Reordered verified skills", "Explain selected project facts", null, null,
                "c".repeat(64), now);
        resume.selectSkill(skill, 0);
        resume.selectProject(project, 0);
        resume.selectProjectBullet(bullet, 0);
        resume.selectLanguage(language, 0);
        ResumeVersion saved = resumeVersions.saveAndFlush(resume);

        assertThat(resumeVersions.findById(saved.getId())).isPresent();
        assertThat(resumeVersionSkills.findByResumeVersionIdOrderByDisplayOrder(saved.getId()))
                .singleElement().extracting(reference -> reference.getCandidateSkill().getId())
                .isEqualTo(skill.getId());
        assertThat(resumeVersionProjects.findByResumeVersionIdOrderByDisplayOrder(saved.getId()))
                .singleElement().extracting(reference -> reference.getCandidateProject().getId())
                .isEqualTo(project.getId());
        assertThat(resumeVersionProjectBullets.findByResumeVersionIdOrderByDisplayOrder(saved.getId()))
                .singleElement().extracting(reference -> reference.getCandidateProjectBullet().getId())
                .isEqualTo(bullet.getId());
        assertThat(resumeVersionLanguages.count()).isEqualTo(1);

        resumeVersions.delete(saved);
        resumeVersions.flush();
        assertThat(count("resume_version_skills", "resume_version_id", saved.getId())).isZero();
        assertThat(count("resume_version_projects", "resume_version_id", saved.getId())).isZero();
        assertThat(count("resume_version_project_bullets", "resume_version_id", saved.getId())).isZero();
        assertThat(count("resume_version_languages", "resume_version_id", saved.getId())).isZero();
        assertThat(candidateSkills.existsById(skill.getId())).isTrue();
    }

    @Test
    void persistsCoverNoteLinkedToVerifiedProfileAndResume() {
        Instant now = Instant.parse("2026-07-19T12:00:00Z");
        Job job = jobs.saveAndFlush(job("cover", "https://example.com/jobs/cover", now));
        CandidateProfile profile = candidateProfiles.findByActiveTrue().orElseThrow();
        ResumeVersion resume = resumeVersions.saveAndFlush(new ResumeVersion(
                job, profile, profile.getProfileVersion(), "JAVA DEVELOPER INTERN",
                "Verified summary", "Preview", "Changes", "Claims", null, null,
                "d".repeat(64), now));

        CoverNote note = new CoverNote(job, profile, resume,
                "Factual cover note", "e".repeat(64), now);
        note.referenceProfile(profile, 0);
        CoverNote saved = coverNotes.saveAndFlush(note);

        assertThat(coverNotes.findByJobIdOrderByGeneratedAtDesc(job.getId()))
                .singleElement().extracting(CoverNote::getContent).isEqualTo("Factual cover note");
        assertThat(saved.getResumeVersion().getId()).isEqualTo(resume.getId());
        assertThat(coverNoteFactReferences.count()).isEqualTo(1);

        ApplicationRecord application = ApplicationRecord.create(job, ApplicationStatus.SAVED, now);
        application.selectDocuments(resume, saved, now.plusSeconds(1));
        applications.saveAndFlush(application);
        entityManager.clear();
        ApplicationRecord stored = applications.findByJobId(job.getId()).orElseThrow();
        assertThat(stored.getResumeVersion().getId()).isEqualTo(resume.getId());
        assertThat(stored.getCoverNote().getId()).isEqualTo(saved.getId());
        assertThat(stored.getStatus()).isEqualTo(ApplicationStatus.SAVED);
    }

    @Test
    void persistsLlmUsageAccountingWithoutRawPayloadsOrSecrets() {
        Instant now = Instant.parse("2026-07-19T13:00:00Z");
        Job job = jobs.saveAndFlush(job("llm", "https://example.com/jobs/llm", now));
        LlmUsageEvent saved = llmUsageEvents.saveAndFlush(new LlmUsageEvent(
                job, LlmOperationType.REQUIREMENT_EXTRACTION, "compatible-provider", "model-a",
                120L, 40L, false, new BigDecimal("0.001234"),
                LlmUsageStatus.SUCCEEDED, false, null, now));

        assertThat(llmUsageEvents.findById(saved.getId())).isPresent();
        assertThat(llmUsageEvents.sumEstimatedCostBetween(
                Instant.parse("2026-07-01T00:00:00Z"), Instant.parse("2026-08-01T00:00:00Z")))
                .isEqualByComparingTo("0.001234");
        assertThat(jdbcTemplate.queryForObject(
                "select numeric_scale from information_schema.columns "
                        + "where table_name = 'llm_usage_events' and column_name = 'estimated_cost_usd'",
                Integer.class)).isEqualTo(8);
    }

    @Test
    void persistsStructuredAnalysisReservationAndDeterministicCacheIdentity() {
        Instant now = Instant.parse("2026-07-19T13:30:00Z");
        Job job = jobs.saveAndFlush(job("analysis", "https://example.com/jobs/analysis", now));
        CandidateProfile profile = candidateProfiles.findByActiveTrue().orElseThrow();
        LlmBudgetReservation reservation = llmBudgetReservations.saveAndFlush(
                new LlmBudgetReservation("1".repeat(64), job, LlmOperationType.JOB_ANALYSIS,
                        "synthetic-provider", "model-a", java.time.LocalDate.parse("2026-07-19"),
                        1000, 500, 1, new BigDecimal("0.00200000"), now,
                        now.plusSeconds(120)));
        JobAnalysis analysis = new JobAnalysis(job, profile, LlmOperationType.JOB_ANALYSIS,
                "synthetic-provider", "model-a", "job-analysis-v1", "2".repeat(64),
                profile.getSourceHash(), "3".repeat(64), now);
        analysis.attachReservation(reservation);
        analysis.complete(new JobAnalysisData("Synthetic Java internship", List.of("Java"),
                List.of(), List.of(), null, null, null, "Bucharest", null,
                List.of(new CandidateStrength("java-17", CandidateMatchStrength.MATCH)),
                List.of(), List.of("Work authorization is unknown"),
                List.of(new EvidenceReference(EvidenceSource.VACANCY,
                        "job.description", "Java internship")), 80, false), jobAnalysisJson, now);
        jobAnalyses.saveAndFlush(analysis);

        entityManager.clear();
        JobAnalysis stored = jobAnalyses.findByCacheKey("3".repeat(64)).orElseThrow();
        assertThat(stored.getCandidateProfileVersion()).isEqualTo(profile.getProfileVersion());
        assertThat(stored.getReservation().getRequestKey()).isEqualTo("1".repeat(64));
        assertThat(stored.toData(jobAnalysisJson).mustHaveRequirements()).containsExactly("Java");
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from information_schema.columns where table_name = 'job_analyses' "
                        + "and column_name in ('api_key', 'raw_prompt', 'raw_response')",
                Integer.class)).isZero();
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void databaseLockSerializesRetryAwareBudgetsAcrossServiceInstances() throws Exception {
        List<Long> createdJobIds = new ArrayList<>();
        try {
            Job overFirst = committedJob("budget-over-a", createdJobIds);
            Job overSecond = committedJob("budget-over-b", createdJobIds);
            Clock overClock = fixedClock("2026-07-22T10:00:00Z");
            LlmBudgetService overA = budgetService("0.00600000", overClock);
            LlmBudgetService overB = budgetService("0.00600000", overClock);

            List<LlmBudgetReservationResult> over = overlappingReservations(
                    overA, overFirst, "a".repeat(64), overB, overSecond, "b".repeat(64));

            assertThat(over).extracting(LlmBudgetReservationResult::decision)
                    .containsExactlyInAnyOrder(LlmBudgetDecision.RESERVED,
                            LlmBudgetDecision.DAILY_LIMIT);
            List<LlmBudgetReservation> overPersisted = reservationsOn(LocalDate.parse("2026-07-22"));
            assertThat(overPersisted).singleElement().satisfies(reservation -> {
                assertThat(reservation.getStatus()).isEqualTo(LlmBudgetReservationStatus.RESERVED);
                assertThat(reservation.getReservedCostUsd()).isEqualByComparingTo("0.00400000");
                assertThat(reservation.getMaxAttempts()).isEqualTo(2);
            });
            assertThat(overPersisted).extracting(LlmBudgetReservation::getRequestKey)
                    .doesNotHaveDuplicates();
            assertThat(llmBudgetReservations.committedForDay(LocalDate.parse("2026-07-22")))
                    .isEqualByComparingTo("0.00400000");

            Job exactFirst = committedJob("budget-exact-a", createdJobIds);
            Job exactSecond = committedJob("budget-exact-b", createdJobIds);
            Clock exactClock = fixedClock("2026-07-23T10:00:00Z");
            List<LlmBudgetReservationResult> exact = overlappingReservations(
                    budgetService("0.00800000", exactClock), exactFirst, "c".repeat(64),
                    budgetService("0.00800000", exactClock), exactSecond, "d".repeat(64));

            assertThat(exact).extracting(LlmBudgetReservationResult::decision)
                    .containsOnly(LlmBudgetDecision.RESERVED).hasSize(2);
            assertThat(reservationsOn(LocalDate.parse("2026-07-23"))).hasSize(2);
            assertThat(llmBudgetReservations.committedForDay(LocalDate.parse("2026-07-23")))
                    .isEqualByComparingTo("0.00800000");

            Job releasedFirst = committedJob("budget-release-a", createdJobIds);
            Job releasedSecond = committedJob("budget-release-b", createdJobIds);
            LlmBudgetService releaseService = budgetService(
                    "0.00500000", fixedClock("2026-07-24T10:00:00Z"));
            LlmBudgetReservation released = releaseService.reserve(releasedFirst,
                    LlmOperationType.JOB_ANALYSIS, "e".repeat(64)).reservation();
            new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                    releaseService.reconcileWithinTransaction(
                            released.getId(), new BigDecimal("0.00100000")));

            assertThat(releaseService.reserve(releasedSecond, LlmOperationType.JOB_ANALYSIS,
                    "f".repeat(64)).decision()).isEqualTo(LlmBudgetDecision.RESERVED);
            assertThat(llmBudgetReservations.committedForDay(LocalDate.parse("2026-07-24")))
                    .isEqualByComparingTo("0.00500000");
        } finally {
            new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                List<LlmBudgetReservation> createdReservations = llmBudgetReservations.findAll()
                        .stream().filter(reservation -> createdJobIds.contains(
                                reservation.getJob().getId())).toList();
                llmBudgetReservations.deleteAll(createdReservations);
                llmBudgetReservations.flush();
                jobs.deleteAllById(createdJobIds);
                jobs.flush();
            });
        }
    }

    @Test
    void persistsTelegramLongPollingStateWithoutCredentials() {
        Instant now = Instant.parse("2026-07-19T14:00:00Z");

        TelegramBotState storedState = new TelegramBotState(12345L, now);
        storedState.recordFailure(12346L, now.plusSeconds(1));
        telegramBotStates.saveAndFlush(storedState);

        assertThat(telegramBotStates.findById(TelegramBotState.LONG_POLLING_KEY))
                .hasValueSatisfying(state -> {
                    assertThat(state.getLastProcessedUpdateId()).isEqualTo(12345L);
                    assertThat(state.getFailedUpdateId()).isEqualTo(12346L);
                    assertThat(state.getFailedAttempts()).isEqualTo(1);
                });
    }

    @Test
    void enforcesForeignKeysAndCascadesCandidateChildren() {
        assertThatThrownBy(() -> jdbcTemplate.update(
                "insert into candidate_skills "
                        + "(candidate_profile_id, stable_key, normalized_name, display_name, category, "
                        + "evidence_text, active, display_order) values (?, ?, ?, ?, ?, ?, ?, ?)",
                Long.MAX_VALUE, "missing-profile", "missing", "Missing", "JAVA",
                "Evidence", true, 0))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void deletingUnreferencedProfileCascadesAllCandidateFactRows() {
        CandidateProfile profile = profile(91, false);
        profile.addSkill("test-skill", "test skill", "Test skill", CandidateSkillCategory.JAVA,
                "Verified test evidence", true, 0);
        profile.addLanguage("test-language", "Test language",
                com.jobpilot.candidate.domain.CandidateLanguageLevel.BASIC, false, true, 0);
        var project = profile.addProject("test-project", "Test project", "Verified personal project",
                ProjectType.PERSONAL_PROJECT, List.of("Java"), true, 0);
        project.addBullet("test-bullet", "Verified project fact", List.of("Java"), true, 0);
        candidateProfiles.saveAndFlush(profile);
        Long profileId = profile.getId();
        Long projectId = project.getId();

        candidateProfiles.delete(profile);
        candidateProfiles.flush();

        assertThat(count("candidate_skills", "candidate_profile_id", profileId)).isZero();
        assertThat(count("candidate_languages", "candidate_profile_id", profileId)).isZero();
        assertThat(count("candidate_projects", "candidate_profile_id", profileId)).isZero();
        assertThat(count("candidate_project_bullets", "project_id", projectId)).isZero();
    }

    private List<LlmBudgetReservationResult> overlappingReservations(
            LlmBudgetService firstService, Job firstJob, String firstKey,
            LlmBudgetService secondService, Job secondJob, String secondKey) throws Exception {
        CountDownLatch controlLocked = new CountDownLatch(1);
        CountDownLatch releaseControl = new CountDownLatch(1);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(3)) {
            Future<?> lockHolder = executor.submit(() ->
                    new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                        llmBudgetControls.findByIdForUpdate(LlmBudgetControl.SINGLETON_ID)
                                .orElseThrow();
                        controlLocked.countDown();
                        await(releaseControl);
                    }));
            assertThat(controlLocked.await(5, TimeUnit.SECONDS)).isTrue();
            Future<LlmBudgetReservationResult> first = executor.submit(() -> {
                ready.countDown();
                start.await();
                return firstService.reserve(firstJob, LlmOperationType.JOB_ANALYSIS, firstKey);
            });
            Future<LlmBudgetReservationResult> second = executor.submit(() -> {
                ready.countDown();
                start.await();
                return secondService.reserve(secondJob, LlmOperationType.JOB_ANALYSIS, secondKey);
            });
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            try {
                Thread.sleep(150);
                assertThat(first.isDone()).isFalse();
                assertThat(second.isDone()).isFalse();
            } finally {
                releaseControl.countDown();
            }
            List<LlmBudgetReservationResult> results = List.of(
                    first.get(5, TimeUnit.SECONDS), second.get(5, TimeUnit.SECONDS));
            lockHolder.get(5, TimeUnit.SECONDS);
            return results;
        }
    }

    private LlmBudgetService budgetService(String dailyBudget, Clock clock) {
        JobPilotProperties.Llm llm = new JobPilotProperties.Llm(true, "openai",
                "https://api.openai.com/v1", "obviously-fake-secret", "model-a",
                Duration.ofSeconds(1), Duration.ofSeconds(2), 1_000, 500, 1,
                new BigDecimal("0.00400000"), new BigDecimal(dailyBudget),
                new BigDecimal("1.00000000"), BigDecimal.ONE, new BigDecimal("2"));
        JobPilotProperties properties = TestProperties.create(llm);
        return new LlmBudgetService(llmBudgetControls, llmBudgetReservations,
                new LlmCostCalculator(properties), llmUsageEvents, properties, clock,
                transactionManager);
    }

    private Job committedJob(String externalId, List<Long> createdJobIds) {
        Job saved = new TransactionTemplate(transactionManager).execute(status ->
                jobs.saveAndFlush(job(externalId, "https://example.invalid/jobs/" + externalId,
                        Instant.parse("2026-07-22T09:00:00Z"))));
        createdJobIds.add(saved.getId());
        return saved;
    }

    private List<LlmBudgetReservation> reservationsOn(LocalDate day) {
        return llmBudgetReservations.findAll().stream()
                .filter(reservation -> reservation.getBudgetDay().equals(day)).toList();
    }

    private Clock fixedClock(String instant) {
        return Clock.fixed(Instant.parse(instant), ZoneOffset.UTC);
    }

    private void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("Timed out while proving PostgreSQL lock overlap");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        }
    }

    private RawJob raw(String externalId, String url, String description) {
        return new RawJob("greenhouse", externalId, url, "Java Intern", "Example",
                "Bucharest, Romania", description, null, Instant.parse("2026-07-16T10:00:00Z"),
                null, description);
    }

    private Job job(String externalId, String url, Instant seenAt) {
        return new Job("greenhouse", externalId, url, "Java Intern", "Example", "Bucharest",
                RemoteType.ONSITE, null, "Java internship description", null, null,
                "a".repeat(64), "b".repeat(64), externalId + "-fingerprint", seenAt);
    }

    private CandidateProfile profile(int version, boolean active) {
        return new CandidateProfile(version, "Test Candidate", "Bucharest, Romania",
                "Test University", "BSc in Informatics", 2025, null, true, false,
                BigDecimal.ZERO, Integer.toHexString(version).repeat(16),
                Instant.parse("2026-07-19T00:00:00Z"), active);
    }

    private int count(String table, String foreignKey, Long id) {
        return jdbcTemplate.queryForObject(
                "select count(*) from " + table + " where " + foreignKey + " = ?",
                Integer.class, id);
    }
}

package com.jobpilot.jobs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jobpilot.applications.domain.ApplicationRecord;
import com.jobpilot.applications.domain.ApplicationStatus;
import com.jobpilot.applications.repository.ApplicationRepository;
import com.jobpilot.candidate.domain.CandidateProfile;
import com.jobpilot.candidate.domain.CandidateSkillCategory;
import com.jobpilot.candidate.domain.ProjectType;
import com.jobpilot.candidate.repository.CandidateLanguageRepository;
import com.jobpilot.candidate.repository.CandidateProfileRepository;
import com.jobpilot.candidate.repository.CandidateProjectBulletRepository;
import com.jobpilot.candidate.repository.CandidateProjectRepository;
import com.jobpilot.candidate.repository.CandidateSkillRepository;
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
import com.jobpilot.llm.repository.LlmUsageEventRepository;
import com.jobpilot.resume.domain.CoverNote;
import com.jobpilot.resume.domain.ResumeVersion;
import com.jobpilot.resume.repository.CoverNoteRepository;
import com.jobpilot.resume.repository.ResumeVersionProjectBulletRepository;
import com.jobpilot.resume.repository.ResumeVersionProjectRepository;
import com.jobpilot.resume.repository.ResumeVersionRepository;
import com.jobpilot.resume.repository.ResumeVersionSkillRepository;
import com.jobpilot.telegram.polling.TelegramBotState;
import com.jobpilot.telegram.polling.TelegramBotStateRepository;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
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
    private ResumeVersionRepository resumeVersions;
    @Autowired
    private ResumeVersionSkillRepository resumeVersionSkills;
    @Autowired
    private ResumeVersionProjectRepository resumeVersionProjects;
    @Autowired
    private ResumeVersionProjectBulletRepository resumeVersionProjectBullets;
    @Autowired
    private CoverNoteRepository coverNotes;
    @Autowired
    private LlmUsageEventRepository llmUsageEvents;
    @Autowired
    private TelegramBotStateRepository telegramBotStates;
    @Autowired
    private EntityManager entityManager;

    @Test
    void flywayMigratesTheSchemaOnRealPostgres() {
        Integer applied = jdbcTemplate.queryForObject(
                "select count(*) from flyway_schema_history where success", Integer.class);
        assertThat(applied).isEqualTo(2);
        assertThat(jdbcTemplate.queryForList(
                "select table_name from information_schema.tables where table_schema = 'public'",
                String.class))
                .contains("jobs", "job_requirements", "job_scores", "source_fetch_logs",
                        "candidate_profiles", "candidate_skills", "candidate_languages",
                        "candidate_projects", "candidate_project_bullets", "applications",
                        "resume_versions", "resume_version_skills", "resume_version_projects",
                        "resume_version_project_bullets", "cover_notes", "llm_usage_events",
                        "telegram_bot_state");
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
    void persistsResumeVersionFactReferencesAndCascadesResumeDeletion() {
        Instant now = Instant.parse("2026-07-19T11:00:00Z");
        Job job = jobs.saveAndFlush(job("resume", "https://example.com/jobs/resume", now));
        CandidateProfile profile = candidateProfiles.findByActiveTrue().orElseThrow();
        var skill = candidateSkills.findByCandidateProfileIdOrderByDisplayOrder(profile.getId()).getFirst();
        var project = candidateProjects.findByCandidateProfileIdOrderByDisplayOrder(profile.getId()).getFirst();
        var bullet = candidateProjectBullets.findByProjectIdOrderByDisplayOrder(project.getId()).getFirst();
        ResumeVersion resume = new ResumeVersion(job, profile, profile.getProfileVersion(),
                "JAVA DEVELOPER INTERN", "Verified summary", "Plain text preview",
                "Reordered verified skills", "Explain selected project facts", null, null,
                "c".repeat(64), now);
        resume.selectSkill(skill, 0);
        resume.selectProject(project, 0);
        resume.selectProjectBullet(bullet, 0);
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

        resumeVersions.delete(saved);
        resumeVersions.flush();
        assertThat(count("resume_version_skills", "resume_version_id", saved.getId())).isZero();
        assertThat(count("resume_version_projects", "resume_version_id", saved.getId())).isZero();
        assertThat(count("resume_version_project_bullets", "resume_version_id", saved.getId())).isZero();
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

        CoverNote saved = coverNotes.saveAndFlush(new CoverNote(
                job, profile, resume, "Factual cover note", "e".repeat(64), now));

        assertThat(coverNotes.findByJobIdOrderByGeneratedAtDesc(job.getId()))
                .singleElement().extracting(CoverNote::getContent).isEqualTo("Factual cover note");
        assertThat(saved.getResumeVersion().getId()).isEqualTo(resume.getId());
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
    }

    @Test
    void persistsTelegramLongPollingStateWithoutCredentials() {
        Instant now = Instant.parse("2026-07-19T14:00:00Z");

        telegramBotStates.saveAndFlush(new TelegramBotState(12345L, now));

        assertThat(telegramBotStates.findById(TelegramBotState.LONG_POLLING_KEY))
                .hasValueSatisfying(state -> {
                    assertThat(state.getLastProcessedUpdateId()).isEqualTo(12345L);
                    assertThat(state.getUpdatedAt()).isEqualTo(now);
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

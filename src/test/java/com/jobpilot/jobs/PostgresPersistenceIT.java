package com.jobpilot.jobs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jobpilot.jobs.domain.Job;
import com.jobpilot.jobs.domain.JobStatus;
import com.jobpilot.jobs.domain.RawJob;
import com.jobpilot.jobs.domain.RemoteType;
import com.jobpilot.jobs.repository.JobRepository;
import com.jobpilot.jobs.repository.JobRequirementRepository;
import com.jobpilot.jobs.repository.JobScoreRepository;
import com.jobpilot.jobs.service.JobProcessor;
import java.time.Instant;
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

    @Test
    void flywayMigratesTheSchemaOnRealPostgres() {
        Integer applied = jdbcTemplate.queryForObject(
                "select count(*) from flyway_schema_history where success", Integer.class);
        assertThat(applied).isEqualTo(1);
        assertThat(jdbcTemplate.queryForList(
                "select table_name from information_schema.tables where table_schema = 'public'",
                String.class))
                .contains("jobs", "job_requirements", "job_scores", "source_fetch_logs");
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
}

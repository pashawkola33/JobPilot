package com.jobpilot.jobs.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.jobpilot.jobs.domain.Job;
import com.jobpilot.jobs.domain.JobStatus;
import com.jobpilot.jobs.domain.RemoteType;
import com.jobpilot.jobs.domain.RawJob;
import com.jobpilot.jobs.service.JobDeduplicationService;
import com.jobpilot.jobs.service.JobNormalizer;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:jobpilot;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.enabled=true"
})
@Transactional
class JobRepositoryTest {
    @Autowired
    private JobRepository repository;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private JobNormalizer normalizer;
    @Autowired
    private JobDeduplicationService deduplication;

    @Test
    void flywayCreatesPhaseOneAndPhaseTwoTablesAndRepositoryRoundTripsAJob() {
        List<String> tables = jdbcTemplate.queryForList(
                "select table_name from information_schema.tables where table_schema = 'public'",
                String.class);
        assertThat(tables).contains("jobs", "job_requirements", "job_scores", "source_fetch_logs",
                "candidate_profiles", "candidate_skills", "candidate_languages", "candidate_projects",
                "candidate_project_bullets", "applications", "resume_versions",
                "resume_version_skills", "resume_version_projects",
                "resume_version_project_bullets", "resume_version_languages",
                "cover_notes", "cover_note_fact_references", "llm_usage_events",
                "telegram_bot_state");

        Instant now = Instant.parse("2026-07-17T12:00:00Z");
        Job saved = repository.saveAndFlush(new Job("greenhouse", "123", "https://example.com/jobs/123",
                "Java Intern", "Example", "Bucharest, Romania", RemoteType.HYBRID, "Internship",
                "Java and Spring Boot internship", now, null, "a".repeat(64), "b".repeat(64),
                "c".repeat(64), now));

        assertThat(repository.findByCanonicalUrl("https://example.com/jobs/123"))
                .contains(saved);
        assertThat(repository.findBySourceAndExternalId("greenhouse", "123"))
                .contains(saved);
    }

    @Test
    void detectsCrossSourceDuplicateByNormalizedCompanyTitleAndLocation() {
        Job first = normalizer.normalize(new RawJob("greenhouse", "one", "https://a.example/jobs/one",
                "Junior Java Developer", "Acme S.A.", "Bucharest", "First description", null,
                null, null, "payload-one"));
        repository.saveAndFlush(first);
        Job second = normalizer.normalize(new RawJob("lever", "two", "https://b.example/postings/two",
                "Junior Java Developer", "Acme S.A.", "Bucharest", "Different description", null,
                null, null, "payload-two"));

        assertThat(deduplication.findDuplicate(second)).contains(first);
    }

    @Test
    void expiresOnlyStaleNewAndReviewedJobs() {
        Instant stale = Instant.parse("2026-05-01T12:00:00Z");
        Instant fresh = Instant.parse("2026-07-16T12:00:00Z");
        repository.saveAndFlush(job("e1", stale, JobStatus.NEW));
        repository.saveAndFlush(job("e2", stale, JobStatus.SAVED));
        repository.saveAndFlush(job("e3", fresh, JobStatus.NEW));

        int expired = repository.expireStale(Instant.parse("2026-06-17T12:00:00Z"));

        assertThat(expired).isEqualTo(1);
        // The bulk update bypasses the persistence context, so read the rows directly.
        assertThat(status("e1")).isEqualTo("EXPIRED");
        assertThat(status("e2")).isEqualTo("SAVED");
        assertThat(status("e3")).isEqualTo("NEW");
    }

    private Job job(String externalId, Instant seenAt, JobStatus status) {
        Job job = new Job("greenhouse", externalId, "https://example.com/jobs/" + externalId,
                "Java Intern " + externalId, "Example", "Bucharest", RemoteType.ONSITE, null,
                "Java internship description", null, null, "a".repeat(64), "b".repeat(64),
                externalId + "-fingerprint", seenAt);
        job.changeStatus(status);
        return job;
    }

    private String status(String externalId) {
        return jdbcTemplate.queryForObject(
                "select status from jobs where external_id = ?", String.class, externalId);
    }

    @Test
    void doesNotDeduplicateDifferentCompaniesSharingBoilerplateDescriptions() {
        Job first = normalizer.normalize(new RawJob("greenhouse", "b1", "https://a.example/jobs/b1",
                "Java Intern", "Acme", "Bucharest", "We hire motivated students.", null,
                null, null, "payload-b1"));
        repository.saveAndFlush(first);
        Job second = normalizer.normalize(new RawJob("lever", "b2", "https://b.example/postings/b2",
                "QA Intern", "Globex", "Cluj", "We hire motivated students.", null,
                null, null, "payload-b2"));

        assertThat(deduplication.findDuplicate(second)).isEmpty();
    }

    @Test
    void deduplicatesSameCompanyPostingsWithIdenticalDescriptions() {
        Job first = normalizer.normalize(new RawJob("greenhouse", "c1", "https://a.example/jobs/c1",
                "Java Intern", "Acme", "Bucharest", "Identical role description.", null,
                null, null, "payload-c1"));
        repository.saveAndFlush(first);
        Job second = normalizer.normalize(new RawJob("lever", "c2", "https://b.example/postings/c2",
                "Java Internship", "Acme", "Bucharest", "Identical role description.", null,
                null, null, "payload-c2"));

        assertThat(deduplication.findDuplicate(second)).contains(first);
    }
}

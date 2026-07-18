package com.jobpilot.jobs.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.jobpilot.jobs.domain.Job;
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
    void flywayCreatesOnlyPhaseOneTablesAndRepositoryRoundTripsAJob() {
        List<String> tables = jdbcTemplate.queryForList(
                "select table_name from information_schema.tables where table_schema = 'public'",
                String.class);
        assertThat(tables).contains("jobs", "job_requirements", "job_scores", "source_fetch_logs");
        assertThat(tables).doesNotContain("applications", "resume_versions", "cover_notes", "candidate_profiles");

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
}

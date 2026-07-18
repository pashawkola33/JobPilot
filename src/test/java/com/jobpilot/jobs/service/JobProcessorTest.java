package com.jobpilot.jobs.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.jobpilot.jobs.domain.RawJob;
import com.jobpilot.jobs.repository.JobRepository;
import com.jobpilot.jobs.repository.JobRequirementRepository;
import com.jobpilot.jobs.repository.JobScoreRepository;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:jobpilot-processor;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.enabled=true"
})
@Transactional
class JobProcessorTest {
    @Autowired
    private JobProcessor processor;
    @Autowired
    private JobRepository jobs;
    @Autowired
    private JobRequirementRepository requirements;
    @Autowired
    private JobScoreRepository scores;

    @Test
    void rescoresAnExistingJobWhenItsDescriptionChanges() {
        var first = processor.process(raw("Requires 3 years of experience with Java."));
        assertThat(first.newlyCreated()).isTrue();

        var updated = processor.process(raw("Java internship with structured mentorship in Bucharest, "
                + "Romania. No experience required. Java, Spring Boot, SQL, PostgreSQL and JUnit."));

        assertThat(updated.newlyCreated()).isFalse();
        assertThat(jobs.count()).isEqualTo(1);
        assertThat(updated.job().getId()).isEqualTo(first.job().getId());
        assertThat(updated.score().score()).isNotEqualTo(first.score().score());
        assertThat(scores.findByJobId(first.job().getId()).orElseThrow().getScore())
                .isEqualTo(updated.score().score());
        assertThat(requirements.findByJobId(first.job().getId()).orElseThrow()
                .toValue().internshipOrTrainee()).isTrue();
        assertThat(jobs.findById(first.job().getId()).orElseThrow().getDescription())
                .contains("internship");
    }

    @Test
    void unchangedJobIsRecordedAsSeenWithoutANewRowOrScore() {
        var first = processor.process(raw("Java internship in Bucharest with mentorship."));
        var again = processor.process(raw("Java internship in Bucharest with mentorship."));

        assertThat(again.newlyCreated()).isFalse();
        assertThat(again.job().getId()).isEqualTo(first.job().getId());
        assertThat(again.score().score()).isEqualTo(first.score().score());
        assertThat(jobs.count()).isEqualTo(1);
        assertThat(scores.count()).isEqualTo(1);
        assertThat(requirements.count()).isEqualTo(1);
    }

    private RawJob raw(String description) {
        return new RawJob("greenhouse", "42", "https://example.com/jobs/42", "Java Developer",
                "Example", "Bucharest, Romania", description, null,
                Instant.parse("2026-07-16T10:00:00Z"), null, description);
    }
}

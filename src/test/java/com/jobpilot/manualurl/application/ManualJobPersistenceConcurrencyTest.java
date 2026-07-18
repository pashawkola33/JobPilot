package com.jobpilot.manualurl.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.jobpilot.jobs.domain.RawJob;
import com.jobpilot.jobs.repository.JobRepository;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:manual-concurrency;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.enabled=true"
})
class ManualJobPersistenceConcurrencyTest {
    @Autowired
    private ManualJobPersistenceService persistence;
    @Autowired
    private JobRepository jobs;

    @BeforeEach
    @AfterEach
    void cleanJobs() {
        jobs.deleteAll();
        jobs.flush();
    }

    @Test
    void concurrentDuplicateSubmissionsCreateOneJobRow() throws Exception {
        var executor = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch start = new CountDownLatch(1);
            var first = executor.submit(() -> {
                start.await();
                return persistence.persist(raw());
            });
            var second = executor.submit(() -> {
                start.await();
                return persistence.persist(raw());
            });
            start.countDown();

            var results = java.util.List.of(first.get(), second.get());

            assertThat(jobs.count()).isOne();
            assertThat(results).filteredOn(result -> result.newlyCreated()).hasSize(1);
            assertThat(results).extracting(result -> result.job().getId()).doesNotContainNull().hasSize(2);
        } finally {
            executor.shutdownNow();
        }
    }

    private RawJob raw() {
        return new RawJob("manual", "manual-concurrent",
                "https://public.example/jobs/manual-concurrent", "Java Developer Intern", "Example",
                "Bucharest, Romania", "Java internship with Spring Boot, SQL, testing and mentorship.",
                "Internship", Instant.parse("2026-07-18T10:00:00Z"), null, "fixture");
    }
}

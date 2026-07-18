package com.jobpilot.support;

import com.jobpilot.config.JobPilotProperties;
import java.time.Duration;
import java.util.List;

public final class TestProperties {
    private TestProperties() {
    }

    public static JobPilotProperties create() {
        return create(new JobPilotProperties.Telegram("", ""));
    }

    public static JobPilotProperties create(JobPilotProperties.Telegram telegram) {
        return new JobPilotProperties(
                telegram,
                new JobPilotProperties.Sources(List.of(), List.of()),
                new JobPilotProperties.Candidate("Romania",
                        List.of("Bucharest", "București", "Romania", "Remote Romania"),
                        List.of("Java", "Spring Boot", "REST", "SQL", "PostgreSQL", "JPA", "Maven", "JUnit"),
                        List.of("React", "TypeScript", "JavaScript", "HTML", "CSS", "Git", "CI/CD", "GitHub Actions"),
                        true, false, 0),
                new JobPilotProperties.Http(Duration.ofSeconds(1), Duration.ofSeconds(1), 2_097_152),
                new JobPilotProperties.ManualUrl(Duration.ofSeconds(1), Duration.ofSeconds(1),
                        3, 1_048_576, 500, 100_000),
                new JobPilotProperties.Scheduling("0 0 */6 * * *", "0 0 9 * * *", 30),
                List.of("Java Internship", "Java Developer Intern", "Software Engineer Intern"),
                List.of("Bucharest", "Romania"));
    }
}

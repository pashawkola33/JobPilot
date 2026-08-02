package com.jobpilot.jobs.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:jobpilot-live-smoke;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.enabled=true",
        "jobpilot.scheduling.fetch-cron=-",
        "jobpilot.scheduling.digest-cron=-"
})
@ActiveProfiles("development")
@EnabledIfSystemProperty(named = "jobpilot.live-smoke", matches = "true")
class LiveVacancySmokeTest {
    @Autowired private LiveVacancySmokeService smoke;

    @Test
    void reportsActualRegistryVolumeWithoutWeakeningEligibility() {
        JobIngestionReport report = smoke.collect();

        assertThat(report.totalVacanciesFetched()).isPositive();
        assertThat(report.totalUniqueVacanciesBeforeEligibilityFiltering()).isPositive();
        assertThat(report.rawTargetMet()).isTrue();
        assertThat(report.locationEligibleTargetMet()).isTrue();
        assertThat(report.finalUniqueEligibleVacancies())
                .isEqualTo(report.earlyCareerEligibleVacancies())
                .isLessThanOrEqualTo(report.bucharestLocalVacancies()
                        + report.remoteVacanciesEligibleFromRomania());
    }
}

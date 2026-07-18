package com.jobpilot.candidate.service;

import static com.jobpilot.candidate.CandidateProfileTestData.withVersion;
import static org.assertj.core.api.Assertions.assertThat;

import com.jobpilot.candidate.config.CandidateProfileProperties;
import com.jobpilot.candidate.domain.CandidateProfile;
import com.jobpilot.candidate.repository.CandidateProfileRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:candidate-bootstrap;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.enabled=true"
})
@Transactional
class CandidateProfileBootstrapIntegrationTest {
    @Autowired
    private CandidateProfileBootstrapService bootstrap;
    @Autowired
    private CandidateProfileRepository profiles;
    @Autowired
    private CandidateProfileProperties configuredProfile;

    @Test
    void validProfileIsBootstrappedAndRepeatedBootstrapIsIdempotent() {
        CandidateProfile active = profiles.findByActiveTrue().orElseThrow();
        long before = profiles.count();

        CandidateProfileBootstrapResult result = bootstrap.bootstrap(configuredProfile);

        assertThat(result.created()).isFalse();
        assertThat(result.profileId()).isEqualTo(active.getId());
        assertThat(profiles.count()).isEqualTo(before);
        assertThat(profiles.countByActiveTrue()).isOne();
        assertThat(active.getSkills()).hasSize(65);
        assertThat(active.getLanguages()).hasSize(4);
        assertThat(active.getProjects()).hasSize(4);
    }

    @Test
    void higherVersionCreatesNewActiveVersionAndPreservesPreviousFacts() {
        CandidateProfile previous = profiles.findByActiveTrue().orElseThrow();
        String originalName = previous.getFullName();
        String originalSourceHash = previous.getSourceHash();
        String originalSkill = previous.getSkills().getFirst().getDisplayName();
        String originalBullet = previous.getProjects().getFirst().getBullets().getFirst().getVerifiedText();

        CandidateProfileBootstrapResult result = bootstrap.bootstrap(withVersion(configuredProfile, 2));

        assertThat(result.created()).isTrue();
        assertThat(profiles.count()).isEqualTo(2);
        assertThat(profiles.countByActiveTrue()).isOne();
        CandidateProfile current = profiles.findByActiveTrue().orElseThrow();
        CandidateProfile storedPrevious = profiles.findByProfileVersion(1).orElseThrow();
        assertThat(current.getProfileVersion()).isEqualTo(2);
        assertThat(storedPrevious.isActive()).isFalse();
        assertThat(storedPrevious.getFullName()).isEqualTo(originalName);
        assertThat(storedPrevious.getSourceHash()).isEqualTo(originalSourceHash);
        assertThat(storedPrevious.getSkills().getFirst().getDisplayName()).isEqualTo(originalSkill);
        assertThat(storedPrevious.getProjects().getFirst().getBullets().getFirst().getVerifiedText())
                .isEqualTo(originalBullet);
    }
}

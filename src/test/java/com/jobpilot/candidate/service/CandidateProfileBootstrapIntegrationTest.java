package com.jobpilot.candidate.service;

import static com.jobpilot.candidate.CandidateProfileTestData.withVersion;
import static com.jobpilot.candidate.CandidateProfileTestData.withCandidateKey;
import static org.assertj.core.api.Assertions.assertThat;

import com.jobpilot.candidate.config.CandidateProfileProperties;
import com.jobpilot.candidate.domain.Candidate;
import com.jobpilot.candidate.domain.CandidateProfile;
import com.jobpilot.candidate.repository.CandidateProfileRepository;
import com.jobpilot.candidate.repository.CandidateRepository;
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
    private CandidateRepository candidates;
    @Autowired
    private CandidateProfileProperties configuredProfile;

    @Test
    void validProfileIsBootstrappedAndRepeatedBootstrapIsIdempotent() {
        CandidateProfile active = activeConfiguredProfile();
        long before = profiles.count();

        CandidateProfileBootstrapResult result = bootstrap.bootstrap(configuredProfile);

        assertThat(result.created()).isFalse();
        assertThat(result.profileId()).isEqualTo(active.getId());
        assertThat(profiles.count()).isEqualTo(before);
        assertThat(profiles.findByCandidateIdAndActiveTrue(configuredCandidate().getId()))
                .contains(active);
        assertThat(active.getSkills()).hasSize(65);
        assertThat(active.getLanguages()).hasSize(4);
        assertThat(active.getProjects()).hasSize(4);
    }

    @Test
    void bootstrappedProfileIsOwnedByTheConfiguredCandidate() {
        CandidateProfile active = activeConfiguredProfile();
        Candidate owner = configuredCandidate();

        assertThat(active.getCandidate().getId()).isEqualTo(owner.getId());
        assertThat(owner.getStableKey()).isEqualTo("default");
        assertThat(profiles.findByCandidateIdAndActiveTrue(owner.getId()))
                .contains(active);
        assertThat(profiles.findByCandidateIdAndProfileVersion(owner.getId(), 1))
                .contains(active);
    }

    /** The migration seeds the owner, so bootstrap resolves it instead of adding another. */
    @Test
    void repeatedBootstrapReusesTheSeededCandidateRow() {
        long before = candidates.count();

        bootstrap.bootstrap(configuredProfile);
        bootstrap.bootstrap(withVersion(configuredProfile, 2));

        assertThat(candidates.count()).isEqualTo(before);
        assertThat(candidates.findAll()).extracting(Candidate::getStableKey)
                .containsExactly("default");
        assertThat(profiles.findAll()).extracting(profile -> profile.getCandidate().getId())
                .containsOnly(candidates.findByStableKey("default").orElseThrow().getId());
    }

    @Test
    void candidatesIndependentlyBootstrapTheSameVersionAndRemainActive() {
        Candidate owner = candidates.findByStableKey("default").orElseThrow();
        CandidateProfile configured = profiles.findByCandidateIdAndActiveTrue(owner.getId()).orElseThrow();
        CandidateProfileBootstrapResult result = bootstrap.bootstrap(
                withCandidateKey(configuredProfile, "second-candidate"));
        Candidate other = candidates.findByStableKey("second-candidate").orElseThrow();
        CandidateProfile otherProfile = profiles.findByCandidateIdAndActiveTrue(other.getId())
                .orElseThrow();

        assertThat(result.created()).isTrue();
        assertThat(otherProfile.getProfileVersion()).isEqualTo(configured.getProfileVersion());
        assertThat(otherProfile.getId()).isNotEqualTo(configured.getId());
        assertThat(profiles.findByCandidateIdAndProfileVersion(other.getId(), 1))
                .contains(otherProfile);
        assertThat(profiles.findByCandidateIdAndActiveTrue(owner.getId())).contains(configured);
        assertThat(profiles.findAll()).filteredOn(CandidateProfile::isActive).hasSize(2);
    }

    @Test
    void higherVersionCreatesNewActiveVersionAndPreservesPreviousFacts() {
        Candidate owner = configuredCandidate();
        CandidateProfile previous = activeConfiguredProfile();
        String originalName = previous.getFullName();
        String originalSourceHash = previous.getSourceHash();
        String originalSkill = previous.getSkills().getFirst().getDisplayName();
        String originalBullet = previous.getProjects().getFirst().getBullets().getFirst().getVerifiedText();

        CandidateProfileBootstrapResult result = bootstrap.bootstrap(withVersion(configuredProfile, 2));

        assertThat(result.created()).isTrue();
        assertThat(profiles.count()).isEqualTo(2);
        CandidateProfile current = profiles.findByCandidateIdAndActiveTrue(owner.getId()).orElseThrow();
        CandidateProfile storedPrevious = profiles.findByCandidateIdAndProfileVersion(owner.getId(), 1)
                .orElseThrow();
        assertThat(current.getProfileVersion()).isEqualTo(2);
        assertThat(storedPrevious.isActive()).isFalse();
        assertThat(storedPrevious.getFullName()).isEqualTo(originalName);
        assertThat(storedPrevious.getSourceHash()).isEqualTo(originalSourceHash);
        assertThat(storedPrevious.getSkills().getFirst().getDisplayName()).isEqualTo(originalSkill);
        assertThat(storedPrevious.getProjects().getFirst().getBullets().getFirst().getVerifiedText())
                .isEqualTo(originalBullet);
    }

    private Candidate configuredCandidate() {
        return candidates.findByStableKey(configuredProfile.candidateKey()).orElseThrow();
    }

    private CandidateProfile activeConfiguredProfile() {
        return profiles.findByCandidateIdAndActiveTrue(configuredCandidate().getId()).orElseThrow();
    }
}

package com.jobpilot.candidate.service;

import static com.jobpilot.candidate.CandidateProfileTestData.withVersion;
import static org.assertj.core.api.Assertions.assertThat;

import com.jobpilot.candidate.config.CandidateProfileProperties;
import com.jobpilot.candidate.domain.Candidate;
import com.jobpilot.candidate.domain.CandidateProfile;
import com.jobpilot.candidate.repository.CandidateProfileRepository;
import com.jobpilot.candidate.repository.CandidateRepository;
import java.math.BigDecimal;
import java.time.Instant;
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
    private static final Instant NOW = Instant.parse("2026-07-19T00:00:00Z");

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
    void bootstrappedProfileIsOwnedByTheConfiguredCandidate() {
        CandidateProfile active = profiles.findByActiveTrue().orElseThrow();
        Candidate owner = candidates.findByStableKey(configuredProfile.candidateKey()).orElseThrow();

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

    /**
     * Candidate-scoped lookups must not answer with another candidate's profile. The second
     * candidate's version is stored inactive because profile_version and active_slot are still
     * globally unique in the schema: one active profile per installation is exactly the
     * single-candidate limitation this PR does not yet lift.
     */
    @Test
    void candidateScopedLookupsDoNotCrossCandidates() {
        Candidate owner = candidates.findByStableKey("default").orElseThrow();
        CandidateProfile configured = profiles.findByCandidateIdAndActiveTrue(owner.getId()).orElseThrow();
        Candidate other = candidates.saveAndFlush(new Candidate("second-candidate", NOW));
        CandidateProfile otherProfile = profiles.saveAndFlush(new CandidateProfile(other, 2,
                "Other Candidate", "Bucharest, Romania", "Other University", "BSc", 2025, null,
                true, false, BigDecimal.ZERO, "other-source-hash", NOW, false));

        assertThat(profiles.findByCandidateIdAndProfileVersion(owner.getId(), 2)).isEmpty();
        assertThat(profiles.findByCandidateIdAndProfileVersion(other.getId(), 2))
                .contains(otherProfile);
        assertThat(profiles.findByCandidateIdAndProfileVersion(other.getId(), 1)).isEmpty();
        assertThat(profiles.findByCandidateIdAndActiveTrue(other.getId())).isEmpty();
        assertThat(profiles.findByCandidateIdAndActiveTrue(owner.getId())).contains(configured);
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

package com.jobpilot.candidate.repository;

import com.jobpilot.candidate.domain.CandidateProfile;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CandidateProfileRepository extends JpaRepository<CandidateProfile, Long> {
    Optional<CandidateProfile> findByCandidateIdAndActiveTrue(Long candidateId);

    Optional<CandidateProfile> findByCandidateIdAndProfileVersion(Long candidateId, int profileVersion);

    /**
     * Legacy installation-wide lookups. The database permits version reuse and one active profile
     * per candidate, so these methods are safe only while their callers operate with one configured
     * candidate. {@code JobAnalysisService} and {@code ResumeGenerationService} are migrated to
     * candidate-scoped access when candidate context reaches those workflows.
     */
    Optional<CandidateProfile> findByActiveTrue();

    Optional<CandidateProfile> findByProfileVersion(int profileVersion);

    long countByActiveTrue();
}

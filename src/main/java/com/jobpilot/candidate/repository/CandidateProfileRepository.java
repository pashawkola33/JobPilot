package com.jobpilot.candidate.repository;

import com.jobpilot.candidate.domain.CandidateProfile;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CandidateProfileRepository extends JpaRepository<CandidateProfile, Long> {
    Optional<CandidateProfile> findByCandidateIdAndActiveTrue(Long candidateId);

    Optional<CandidateProfile> findByCandidateIdAndProfileVersion(Long candidateId, int profileVersion);

    /**
     * Installation-wide lookups, meaning "the one active profile in the whole system". Still
     * correct only because a single candidate exists; {@code JobAnalysisService} and
     * {@code ResumeGenerationService} read the active profile this way and are migrated to
     * candidate-scoped access in a later phase.
     */
    Optional<CandidateProfile> findByActiveTrue();

    Optional<CandidateProfile> findByProfileVersion(int profileVersion);

    long countByActiveTrue();
}

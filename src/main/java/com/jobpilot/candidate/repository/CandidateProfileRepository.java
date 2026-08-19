package com.jobpilot.candidate.repository;

import com.jobpilot.candidate.domain.CandidateProfile;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CandidateProfileRepository extends JpaRepository<CandidateProfile, Long> {
    Optional<CandidateProfile> findByCandidateIdAndActiveTrue(Long candidateId);

    Optional<CandidateProfile> findByCandidateIdAndProfileVersion(Long candidateId, int profileVersion);
}

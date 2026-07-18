package com.jobpilot.candidate.repository;

import com.jobpilot.candidate.domain.CandidateProfile;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CandidateProfileRepository extends JpaRepository<CandidateProfile, Long> {
    Optional<CandidateProfile> findByActiveTrue();

    Optional<CandidateProfile> findByProfileVersion(int profileVersion);

    long countByActiveTrue();
}

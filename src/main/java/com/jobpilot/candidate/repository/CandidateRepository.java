package com.jobpilot.candidate.repository;

import com.jobpilot.candidate.domain.Candidate;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CandidateRepository extends JpaRepository<Candidate, Long> {
    Optional<Candidate> findByStableKey(String stableKey);
}

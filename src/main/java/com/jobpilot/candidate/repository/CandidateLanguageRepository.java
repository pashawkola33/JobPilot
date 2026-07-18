package com.jobpilot.candidate.repository;

import com.jobpilot.candidate.domain.CandidateLanguage;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CandidateLanguageRepository extends JpaRepository<CandidateLanguage, Long> {
    List<CandidateLanguage> findByCandidateProfileIdOrderByDisplayOrder(Long candidateProfileId);
}

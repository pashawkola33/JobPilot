package com.jobpilot.candidate.repository;

import com.jobpilot.candidate.domain.CandidateProject;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CandidateProjectRepository extends JpaRepository<CandidateProject, Long> {
    List<CandidateProject> findByCandidateProfileIdOrderByDisplayOrder(Long candidateProfileId);
}

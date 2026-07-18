package com.jobpilot.candidate.repository;

import com.jobpilot.candidate.domain.CandidateSkill;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CandidateSkillRepository extends JpaRepository<CandidateSkill, Long> {
    List<CandidateSkill> findByCandidateProfileIdOrderByDisplayOrder(Long candidateProfileId);
}

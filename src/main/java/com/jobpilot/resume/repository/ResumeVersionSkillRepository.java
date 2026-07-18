package com.jobpilot.resume.repository;

import com.jobpilot.resume.domain.ResumeVersionSkill;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ResumeVersionSkillRepository extends JpaRepository<ResumeVersionSkill, Long> {
    List<ResumeVersionSkill> findByResumeVersionIdOrderByDisplayOrder(Long resumeVersionId);
}

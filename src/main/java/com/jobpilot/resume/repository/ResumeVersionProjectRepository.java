package com.jobpilot.resume.repository;

import com.jobpilot.resume.domain.ResumeVersionProject;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ResumeVersionProjectRepository extends JpaRepository<ResumeVersionProject, Long> {
    List<ResumeVersionProject> findByResumeVersionIdOrderByDisplayOrder(Long resumeVersionId);
}

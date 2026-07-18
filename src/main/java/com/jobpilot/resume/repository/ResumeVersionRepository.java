package com.jobpilot.resume.repository;

import com.jobpilot.resume.domain.ResumeVersion;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ResumeVersionRepository extends JpaRepository<ResumeVersion, Long> {
    List<ResumeVersion> findByJobIdOrderByGeneratedAtDesc(Long jobId);
}

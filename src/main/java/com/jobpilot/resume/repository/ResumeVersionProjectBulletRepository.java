package com.jobpilot.resume.repository;

import com.jobpilot.resume.domain.ResumeVersionProjectBullet;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ResumeVersionProjectBulletRepository
        extends JpaRepository<ResumeVersionProjectBullet, Long> {
    List<ResumeVersionProjectBullet> findByResumeVersionIdOrderByDisplayOrder(Long resumeVersionId);
}

package com.jobpilot.candidate.repository;

import com.jobpilot.candidate.domain.CandidateProjectBullet;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CandidateProjectBulletRepository extends JpaRepository<CandidateProjectBullet, Long> {
    List<CandidateProjectBullet> findByProjectIdOrderByDisplayOrder(Long projectId);
}

package com.jobpilot.llm.repository;

import com.jobpilot.llm.domain.JobAnalysis;
import com.jobpilot.llm.domain.JobAnalysisStatus;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface JobAnalysisRepository extends JpaRepository<JobAnalysis, Long> {
    Optional<JobAnalysis> findByCacheKey(String cacheKey);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select analysis from JobAnalysis analysis where analysis.cacheKey = :cacheKey")
    Optional<JobAnalysis> findByCacheKeyForUpdate(String cacheKey);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select analysis from JobAnalysis analysis where analysis.id = :id")
    Optional<JobAnalysis> findByIdForUpdate(long id);

    List<JobAnalysis> findByJobIdOrderByCreatedAtDesc(long jobId);

    long countByStatus(JobAnalysisStatus status);
}

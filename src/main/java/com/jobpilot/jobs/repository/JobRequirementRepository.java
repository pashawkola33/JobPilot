package com.jobpilot.jobs.repository;

import com.jobpilot.jobs.domain.JobRequirement;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import jakarta.persistence.LockModeType;

public interface JobRequirementRepository extends JpaRepository<JobRequirement, Long> {
    Optional<JobRequirement> findByJobId(Long jobId);

    List<JobRequirement> findAllByJobIdIn(Collection<Long> jobIds);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from JobRequirement r join fetch r.job j where j.id in :jobIds order by j.id")
    List<JobRequirement> findAllByJobIdInForRescoreWrite(Collection<Long> jobIds);
}

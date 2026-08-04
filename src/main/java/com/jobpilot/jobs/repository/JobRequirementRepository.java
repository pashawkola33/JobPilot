package com.jobpilot.jobs.repository;

import com.jobpilot.jobs.domain.JobRequirement;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface JobRequirementRepository extends JpaRepository<JobRequirement, Long> {
    Optional<JobRequirement> findByJobId(Long jobId);

    List<JobRequirement> findAllByJobIdIn(Collection<Long> jobIds);
}

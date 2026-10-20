package com.jobpilot.jobreview.repository;

import com.jobpilot.jobreview.domain.JobWorkflowState;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface JobWorkflowStateRepository extends JpaRepository<JobWorkflowState, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select state from JobWorkflowState state where state.jobId = :jobId and state.candidateId = (select c.id from Candidate c where c.stableKey = 'default')")
    Optional<JobWorkflowState> findByJobIdForUpdate(long jobId);

    @Query("select state from JobWorkflowState state where state.jobId in :jobIds and state.candidateId = (select c.id from Candidate c where c.stableKey = 'default')")
    List<JobWorkflowState> findAllByJobIdIn(Collection<Long> jobIds);
    @Override
    @Query("select state from JobWorkflowState state where state.jobId = :jobId and state.candidateId = (select c.id from Candidate c where c.stableKey = 'default')")
    Optional<JobWorkflowState> findById(Long jobId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select state from JobWorkflowState state where state.candidateId = :candidateId and state.jobId = :jobId")
    Optional<JobWorkflowState> findByCandidateIdAndJobIdForUpdate(long candidateId, long jobId);
}

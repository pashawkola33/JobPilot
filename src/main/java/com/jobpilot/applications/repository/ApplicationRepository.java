package com.jobpilot.applications.repository;

import com.jobpilot.applications.domain.ApplicationRecord;
import com.jobpilot.applications.domain.ApplicationStatus;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import jakarta.persistence.LockModeType;

public interface ApplicationRepository extends JpaRepository<ApplicationRecord, Long> {
    @EntityGraph(attributePaths = "job")
    @Query("select application from ApplicationRecord application where application.job.id = :jobId and application.candidateId = (select c.id from Candidate c where c.stableKey = 'default')")
    Optional<ApplicationRecord> findByJobId(Long jobId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select application from ApplicationRecord application where application.job.id = :jobId and application.candidateId = (select c.id from Candidate c where c.stableKey = 'default')")
    Optional<ApplicationRecord> findByJobIdForUpdate(Long jobId);

    @EntityGraph(attributePaths = "job")
    @Query("select a from ApplicationRecord a where a.status = :status and a.candidateId = (select c.id from Candidate c where c.stableKey = 'default') order by a.updatedAt desc")
    List<ApplicationRecord> findByStatusOrderByUpdatedAtDesc(ApplicationStatus status, Pageable pageable);

    @EntityGraph(attributePaths = "job")
    @Query("select a from ApplicationRecord a where a.candidateId = (select c.id from Candidate c where c.stableKey = 'default') order by a.updatedAt desc")
    List<ApplicationRecord> findAllByOrderByUpdatedAtDesc(Pageable pageable);

    @Query("select count(a) from ApplicationRecord a where a.status = :status and a.candidateId = (select c.id from Candidate c where c.stableKey = 'default')")
    long countByStatus(ApplicationStatus status);
    @Override
    @Query("select count(a) from ApplicationRecord a where a.candidateId = (select c.id from Candidate c where c.stableKey = 'default')")
    long count();

    @Override
    @Query("select a from ApplicationRecord a where a.id = :id and a.candidateId = (select c.id from Candidate c where c.stableKey = 'default')")
    Optional<ApplicationRecord> findById(Long id);

    @EntityGraph(attributePaths = "job")
    Optional<ApplicationRecord> findByCandidateIdAndJobId(long candidateId, long jobId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from ApplicationRecord a where a.candidateId = :candidateId and a.job.id = :jobId")
    Optional<ApplicationRecord> findByCandidateIdAndJobIdForUpdate(long candidateId, long jobId);

    @EntityGraph(attributePaths = "job")
    Optional<ApplicationRecord> findByCandidateIdAndId(long candidateId, long id);
    @Query("select a.job.id from ApplicationRecord a where a.candidateId = :candidateId and a.id = :id")
    Optional<Long> findJobIdByCandidateIdAndId(long candidateId, long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from ApplicationRecord a where a.candidateId = :candidateId and a.id = :id")
    Optional<ApplicationRecord> findByCandidateIdAndIdForUpdate(long candidateId, long id);
}

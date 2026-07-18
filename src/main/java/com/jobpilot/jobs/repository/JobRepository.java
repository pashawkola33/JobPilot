package com.jobpilot.jobs.repository;

import com.jobpilot.jobs.domain.Job;
import com.jobpilot.jobs.domain.JobStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface JobRepository extends JpaRepository<Job, Long> {
    Optional<Job> findByCanonicalUrl(String canonicalUrl);

    Optional<Job> findBySourceAndExternalId(String source, String externalId);

    Optional<Job> findFirstByNormalizedFingerprintOrDescriptionHash(
            String normalizedFingerprint, String descriptionHash);

    Optional<Job> findFirstByNormalizedFingerprint(String normalizedFingerprint);

    List<Job> findByStatusOrderByFirstSeenAtDesc(JobStatus status, Pageable pageable);

    List<Job> findByFirstSeenAtAfterOrderByFirstSeenAtDesc(Instant since, Pageable pageable);

    long countByStatus(JobStatus status);

    @Modifying
    @org.springframework.transaction.annotation.Transactional
    @Query("update Job j set j.status = com.jobpilot.jobs.domain.JobStatus.EXPIRED "
            + "where j.lastSeenAt < :cutoff and j.status in "
            + "(com.jobpilot.jobs.domain.JobStatus.NEW, com.jobpilot.jobs.domain.JobStatus.REVIEWED)")
    int expireStale(Instant cutoff);
}

package com.jobpilot.applications.repository;

import com.jobpilot.applications.domain.ApplicationRecord;
import com.jobpilot.applications.domain.ApplicationStatus;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ApplicationRepository extends JpaRepository<ApplicationRecord, Long> {
    @EntityGraph(attributePaths = "job")
    Optional<ApplicationRecord> findByJobId(Long jobId);

    @EntityGraph(attributePaths = "job")
    List<ApplicationRecord> findByStatusOrderByUpdatedAtDesc(ApplicationStatus status, Pageable pageable);

    @EntityGraph(attributePaths = "job")
    List<ApplicationRecord> findAllByOrderByUpdatedAtDesc(Pageable pageable);

    long countByStatus(ApplicationStatus status);
}

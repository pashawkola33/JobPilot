package com.jobpilot.applications.repository;

import com.jobpilot.applications.domain.ApplicationRecord;
import com.jobpilot.applications.domain.ApplicationStatus;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ApplicationRepository extends JpaRepository<ApplicationRecord, Long> {
    Optional<ApplicationRecord> findByJobId(Long jobId);

    List<ApplicationRecord> findByStatusOrderByUpdatedAtDesc(ApplicationStatus status);

    long countByStatus(ApplicationStatus status);
}

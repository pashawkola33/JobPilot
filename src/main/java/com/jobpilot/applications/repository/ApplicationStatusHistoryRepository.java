package com.jobpilot.applications.repository;

import com.jobpilot.applications.domain.ApplicationStatusHistory;
import java.util.List;
import org.springframework.data.repository.Repository;

public interface ApplicationStatusHistoryRepository
        extends Repository<ApplicationStatusHistory, Long> {
    ApplicationStatusHistory save(ApplicationStatusHistory history);
    List<ApplicationStatusHistory> findByApplicationIdOrderByChangedAtAscIdAsc(Long applicationId);
    long countByApplicationId(Long applicationId);
}

package com.jobpilot.sources.health;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SourceTenantFetchLogRepository extends JpaRepository<SourceTenantFetchLog, Long> {

    List<SourceTenantFetchLog> findByIngestionRunIdOrderByIdAsc(UUID ingestionRunId);

    List<SourceTenantFetchLog> findByProviderAndTenantOrderByIdAsc(String provider, String tenant);

    long countByProviderAndTenant(String provider, String tenant);
}

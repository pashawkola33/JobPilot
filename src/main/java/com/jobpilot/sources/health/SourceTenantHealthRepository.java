package com.jobpilot.sources.health;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SourceTenantHealthRepository extends JpaRepository<SourceTenantHealth, Long> {

    Optional<SourceTenantHealth> findByProviderAndTenant(String provider, String tenant);

    List<SourceTenantHealth> findAllByOrderByProviderAscTenantAsc();

    List<SourceTenantHealth> findByProviderOrderByTenantAsc(String provider);
}

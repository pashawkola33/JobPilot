package com.jobpilot.sources.health.api;

import com.jobpilot.sources.health.SafeErrorText;
import com.jobpilot.sources.health.SourceTenantHealth;
import com.jobpilot.sources.health.SourceTenantHealthRepository;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only per-tenant ATS source health.
 *
 * <p>Strictly diagnostic: no mutation, no tenant disabling, and no field that could carry
 * a credential or a raw payload. A failing external tenant never affects {@code /health}.
 */
@RestController
@RequestMapping("/api/sources")
public class SourceHealthController {
    private final SourceTenantHealthRepository health;

    public SourceHealthController(SourceTenantHealthRepository health) {
        this.health = health;
    }

    @GetMapping("/health")
    public Map<String, Object> sourceHealth(
            @RequestParam(name = "provider", required = false) String provider,
            @RequestParam(name = "onlyUnhealthy", required = false, defaultValue = "false")
            boolean onlyUnhealthy) {
        String requested = provider == null || provider.isBlank()
                ? null : SafeErrorText.token(provider);
        List<SourceTenantHealth> rows = requested == null
                ? health.findAllByOrderByProviderAscTenantAsc()
                : health.findByProviderOrderByTenantAsc(requested);

        List<SourceTenantHealthView> tenants = rows.stream()
                .filter(row -> !onlyUnhealthy || !row.healthy())
                // Deterministic regardless of the repository method or database ordering.
                .sorted(Comparator.comparing(SourceTenantHealth::getProvider,
                                Comparator.nullsFirst(Comparator.naturalOrder()))
                        .thenComparing(SourceTenantHealth::getTenant,
                                Comparator.nullsFirst(Comparator.naturalOrder())))
                .map(SourceTenantHealthView::of)
                .toList();

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("degradedThreshold", SourceTenantHealth.DEGRADED_THRESHOLD);
        body.put("totalTenants", tenants.size());
        body.put("unhealthyTenants", tenants.stream().filter(view -> !view.healthy()).count());
        body.put("degradedTenants", tenants.stream().filter(SourceTenantHealthView::degraded).count());
        body.put("tenants", tenants);
        return body;
    }
}

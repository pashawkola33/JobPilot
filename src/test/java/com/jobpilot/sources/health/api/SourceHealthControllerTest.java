package com.jobpilot.sources.health.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.jobpilot.sources.health.SourceTenantHealth;
import com.jobpilot.sources.health.SourceTenantHealthRepository;
import com.jobpilot.sources.health.TenantAttemptStatus;
import com.jobpilot.sources.health.TenantFailure;
import com.jobpilot.sources.health.TenantFailureCategory;
import java.lang.reflect.RecordComponent;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Example;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

class SourceHealthControllerTest {
    private static final Instant T0 = Instant.parse("2026-08-03T13:00:00Z");

    private final StubRepository repository = new StubRepository();
    private final SourceHealthController controller = new SourceHealthController(repository);

    @Test
    void ordersDeterministicallyByProviderThenTenant() {
        repository.add(healthy("recruitee", "pleo"));
        repository.add(healthy("ashby", "notion"));
        repository.add(healthy("greenhouse", "algolia"));
        repository.add(healthy("ashby", "attio"));
        repository.add(healthy("greenhouse", "twilio"));

        assertThat(tenants(controller.sourceHealth(null, false)))
                .extracting(view -> view.provider() + "/" + view.tenant())
                .containsExactly("ashby/attio", "ashby/notion", "greenhouse/algolia",
                        "greenhouse/twilio", "recruitee/pleo");
    }

    @Test
    void filtersByProvider() {
        repository.add(healthy("ashby", "notion"));
        repository.add(healthy("lever", "swissborg"));
        repository.add(failing("lever", "veeva", 1));

        List<SourceTenantHealthView> views = tenants(controller.sourceHealth("lever", false));

        assertThat(views).extracting(SourceTenantHealthView::tenant)
                .containsExactly("swissborg", "veeva");
    }

    @Test
    void filtersToUnhealthyTenantsOnly() {
        repository.add(healthy("ashby", "notion"));
        repository.add(failing("ashby", "cohere", 1));
        repository.add(emptySuccess("ashby", "linear"));

        List<SourceTenantHealthView> views = tenants(controller.sourceHealth(null, true));

        assertThat(views).extracting(SourceTenantHealthView::tenant).containsExactly("cohere");
        assertThat(views).allSatisfy(view -> assertThat(view.healthy()).isFalse());
    }

    @Test
    void emptySuccessIsReportedAsHealthy() {
        repository.add(emptySuccess("lever", "collabora"));

        SourceTenantHealthView view = tenants(controller.sourceHealth(null, false)).get(0);

        assertThat(view.healthy()).isTrue();
        assertThat(view.lastStatus()).isEqualTo("EMPTY_SUCCESS");
        assertThat(view.lastFetchedCount()).isZero();
        assertThat(view.lastFailureCategory()).isEqualTo("NONE");
    }

    @Test
    void degradedOnlyAfterTheConfiguredConsecutiveFailureThreshold() {
        repository.add(failing("ashby", "belowthreshold", SourceTenantHealth.DEGRADED_THRESHOLD - 1));
        repository.add(failing("ashby", "atthreshold", SourceTenantHealth.DEGRADED_THRESHOLD));

        Map<String, Object> body = controller.sourceHealth("ashby", false);
        List<SourceTenantHealthView> views = tenants(body);

        assertThat(views).filteredOn(view -> view.tenant().equals("belowthreshold"))
                .singleElement().satisfies(view -> {
                    assertThat(view.degraded()).isFalse();
                    assertThat(view.healthy()).isFalse();
                });
        assertThat(views).filteredOn(view -> view.tenant().equals("atthreshold"))
                .singleElement().satisfies(view -> assertThat(view.degraded()).isTrue());
        assertThat(body.get("degradedThreshold")).isEqualTo(SourceTenantHealth.DEGRADED_THRESHOLD);
        assertThat(body.get("degradedTenants")).isEqualTo(1L);
        assertThat(body.get("unhealthyTenants")).isEqualTo(2L);
        assertThat(body.get("totalTenants")).isEqualTo(2);
    }

    @Test
    void surfacesResponseTooLargeAsItsOwnCategoryWithoutPayloadDetail() {
        SourceTenantHealth oversized = new SourceTenantHealth("greenhouse", "gitlab", T0);
        oversized.apply(TenantAttemptStatus.FAILURE,
                new TenantFailure(TenantFailureCategory.RESPONSE_TOO_LARGE, null,
                        "com.jobpilot.common.ExternalHttpException",
                        "Response exceeded the configured 10485760-byte limit "
                                + "for greenhouse tenant gitlab"),
                0, 2073L, T0);
        repository.add(oversized);

        SourceTenantHealthView view = tenants(controller.sourceHealth(null, true)).get(0);

        assertThat(view.lastFailureCategory()).isEqualTo("RESPONSE_TOO_LARGE");
        assertThat(view.lastStatus()).isEqualTo("FAILURE");
        assertThat(view.healthy()).isFalse();
        assertThat(view.degraded()).isFalse();
        assertThat(view.lastHttpStatus()).isNull();
        assertThat(view.safeErrorMessage())
                .isEqualTo("Response exceeded the configured 10485760-byte limit "
                        + "for greenhouse tenant gitlab");
        assertThat(view.safeErrorMessage()).doesNotContain("://", "?", "<", "Bearer");
    }

    @Test
    void exposesNoSecretBearingOrPayloadFields() {
        List<String> fields = new ArrayList<>();
        for (RecordComponent component : SourceTenantHealthView.class.getRecordComponents()) {
            fields.add(component.getName().toLowerCase(Locale.ROOT));
        }

        assertThat(fields).noneSatisfy(field -> assertThat(field)
                .containsAnyOf("token", "secret", "apikey", "password", "authorization",
                        "cookie", "header", "body", "payload", "stack", "url", "uri", "query"));
        assertThat(fields).contains("provider", "tenant", "healthy", "laststatus",
                "lastfailurecategory", "lasthttpstatus", "lastfetchedcount", "lastdurationms",
                "consecutivefailures", "totalattempts", "totalsuccesses", "totalfailures",
                "lastattemptat", "lastsuccessat", "lastfailureat", "safeerrortype",
                "safeerrormessage");
    }

    @Test
    void blankProviderFilterBehavesLikeNoFilter() {
        repository.add(healthy("ashby", "notion"));
        repository.add(healthy("lever", "swissborg"));

        assertThat(tenants(controller.sourceHealth("   ", false))).hasSize(2);
    }

    @SuppressWarnings("unchecked")
    private List<SourceTenantHealthView> tenants(Map<String, Object> body) {
        return (List<SourceTenantHealthView>) body.get("tenants");
    }

    private SourceTenantHealth healthy(String provider, String tenant) {
        SourceTenantHealth health = new SourceTenantHealth(provider, tenant, T0);
        health.apply(TenantAttemptStatus.SUCCESS, TenantFailure.none(), 4, 100L, T0);
        return health;
    }

    private SourceTenantHealth emptySuccess(String provider, String tenant) {
        SourceTenantHealth health = new SourceTenantHealth(provider, tenant, T0);
        health.apply(TenantAttemptStatus.EMPTY_SUCCESS, TenantFailure.none(), 0, 40L, T0);
        return health;
    }

    private SourceTenantHealth failing(String provider, String tenant, int consecutiveFailures) {
        SourceTenantHealth health = new SourceTenantHealth(provider, tenant, T0);
        TenantFailure failure = new TenantFailure(TenantFailureCategory.INVALID_TENANT, 404,
                "com.jobpilot.common.ExternalHttpException",
                "HTTP 404 for " + provider + " tenant " + tenant);
        for (int attempt = 0; attempt < consecutiveFailures; attempt++) {
            health.apply(TenantAttemptStatus.FAILURE, failure, 0, 20L, T0.plusSeconds(attempt));
        }
        return health;
    }

    /** Insertion-ordered stub so the controller's own ordering is what the test observes. */
    private static final class StubRepository implements SourceTenantHealthRepository {
        private final List<SourceTenantHealth> rows = new ArrayList<>();

        private void add(SourceTenantHealth health) {
            rows.add(health);
        }

        @Override
        public Optional<SourceTenantHealth> findByProviderAndTenant(String provider, String tenant) {
            return rows.stream().filter(row -> row.getProvider().equals(provider)
                    && row.getTenant().equals(tenant)).findFirst();
        }

        @Override
        public List<SourceTenantHealth> findAllByOrderByProviderAscTenantAsc() {
            return List.copyOf(rows);
        }

        @Override
        public List<SourceTenantHealth> findByProviderOrderByTenantAsc(String provider) {
            return rows.stream().filter(row -> row.getProvider().equals(provider))
                    .sorted(Comparator.comparing(SourceTenantHealth::getTenant)).toList();
        }

        @Override public void flush() { }
        @Override public <S extends SourceTenantHealth> S saveAndFlush(S entity) { return entity; }
        @Override public <S extends SourceTenantHealth> List<S> saveAllAndFlush(Iterable<S> e) {
            return List.of();
        }
        @Override public void deleteAllInBatch(Iterable<SourceTenantHealth> entities) { }
        @Override public void deleteAllByIdInBatch(Iterable<Long> ids) { }
        @Override public void deleteAllInBatch() { }
        @Override public SourceTenantHealth getOne(Long id) { return null; }
        @Override public SourceTenantHealth getById(Long id) { return null; }
        @Override public SourceTenantHealth getReferenceById(Long id) { return null; }
        @Override public <S extends SourceTenantHealth> List<S> findAll(Example<S> example) {
            return List.of();
        }
        @Override public <S extends SourceTenantHealth> List<S> findAll(Example<S> e, Sort sort) {
            return List.of();
        }
        @Override public <S extends SourceTenantHealth> List<S> saveAll(Iterable<S> entities) {
            return List.of();
        }
        @Override public List<SourceTenantHealth> findAll() { return List.copyOf(rows); }
        @Override public List<SourceTenantHealth> findAll(Sort sort) { return List.copyOf(rows); }
        @Override public List<SourceTenantHealth> findAllById(Iterable<Long> ids) { return List.of(); }
        @Override public <S extends SourceTenantHealth> S save(S entity) { return entity; }
        @Override public Optional<SourceTenantHealth> findById(Long id) { return Optional.empty(); }
        @Override public boolean existsById(Long id) { return false; }
        @Override public long count() { return rows.size(); }
        @Override public void deleteById(Long id) { }
        @Override public void delete(SourceTenantHealth entity) { }
        @Override public void deleteAllById(Iterable<? extends Long> ids) { }
        @Override public void deleteAll(Iterable<? extends SourceTenantHealth> entities) { }
        @Override public void deleteAll() { }
        @Override public Page<SourceTenantHealth> findAll(Pageable pageable) { return Page.empty(); }
        @Override public <S extends SourceTenantHealth> Optional<S> findOne(Example<S> example) {
            return Optional.empty();
        }
        @Override public <S extends SourceTenantHealth> Page<S> findAll(Example<S> e, Pageable p) {
            return Page.empty();
        }
        @Override public <S extends SourceTenantHealth> long count(Example<S> example) { return 0; }
        @Override public <S extends SourceTenantHealth> boolean exists(Example<S> example) {
            return false;
        }
        @Override public <S extends SourceTenantHealth, R> R findBy(Example<S> example,
                java.util.function.Function<org.springframework.data.repository.query.FluentQuery
                        .FetchableFluentQuery<S>, R> queryFunction) {
            return null;
        }
    }
}

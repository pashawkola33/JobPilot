package com.jobpilot.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/**
 * Hygiene of the permanent ATS tenant registry.
 *
 * <p>The registry lives in {@code application-development.yml} and is bound through the
 * same {@link JobPilotProperties.Sources} path production uses; nothing is duplicated
 * here to satisfy an assertion. Every check runs against the bound values, so a typo,
 * blank entry, stray space, duplicate, or reintroduced dead board fails the build.
 */
class SourceRegistryValidationTest {
    /** Same grammar {@code Sources} enforces and {@code ExternalHttpClient} re-checks. */
    private static final Pattern TENANT_GRAMMAR = Pattern.compile("^[a-zA-Z0-9][a-zA-Z0-9._-]{0,62}$");

    /**
     * Removed by the Phase 3.2 audit: a deterministic HTTP 404 from the public Recruitee
     * endpoint during ingestion run f4020aec and again on direct verification, with no
     * verifiable replacement board.
     */
    private static final Map<String, String> REMOVED_INVALID = Map.of("recruitee", "xebiapoland");

    /**
     * Loads the real {@code application.yml} plus the {@code development} profile through
     * Spring Boot's own config-data machinery, so the test binds exactly what production
     * binds rather than a copy.
     */
    private final ApplicationContextRunner developmentRegistry = new ApplicationContextRunner()
            .withUserConfiguration(RegistryConfiguration.class)
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withPropertyValues("spring.profiles.active=development");

    static List<String> providers() {
        return List.of("greenhouse", "lever", "ashby", "recruitee");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("providers")
    void everyConfiguredTenantIsNonBlankTrimmedUniqueAndWellFormed(String provider) {
        withRegistry(sources -> {
            List<String> tenants = tenantsOf(sources, provider);
            assertThat(tenants).as("%s registry must not be empty", provider).isNotEmpty();

            for (String tenant : tenants) {
                assertThat(tenant).as("blank %s tenant", provider).isNotBlank();
                assertThat(tenant).as("%s tenant '%s' has surrounding whitespace", provider, tenant)
                        .isEqualTo(tenant.strip());
                assertThat(TENANT_GRAMMAR.matcher(tenant).matches())
                        .as("%s tenant '%s' violates the tenant grammar", provider, tenant)
                        .isTrue();
            }
            assertThat(tenants).as("duplicate %s tenants", provider).doesNotHaveDuplicates();
        });
    }

    @Test
    void registryOrderIsDeterministicAcrossRepeatedBindings() {
        List<Map<String, List<String>>> bindings = new ArrayList<>();
        for (int attempt = 0; attempt < 3; attempt++) {
            withRegistry(sources -> bindings.add(Map.of(
                    "greenhouse", sources.greenhouseBoardTokens(),
                    "lever", sources.leverCompanyIds(),
                    "ashby", sources.ashbyBoardNames(),
                    "recruitee", sources.recruiteeCompanyIds())));
        }

        assertThat(bindings).hasSize(3);
        assertThat(bindings.get(1)).isEqualTo(bindings.get(0));
        assertThat(bindings.get(2)).isEqualTo(bindings.get(0));
    }

    @Test
    void auditRemovedTenantsAreNotReintroduced() {
        withRegistry(sources -> REMOVED_INVALID.forEach((provider, removed) ->
                assertThat(tenantsOf(sources, provider))
                        .as("%s/%s was removed as a deterministic 404 and must stay removed",
                                provider, removed)
                        .doesNotContain(removed)));
    }

    @Test
    void tenantsThatFailedOnlyTheClientSideResponseSizeLimitAreStillConfigured() {
        // These boards are live: they failed with RESPONSE_PARSE_ERROR because the
        // response exceeded jobpilot.http.max-response-bytes, not because they are dead.
        withRegistry(sources -> {
            assertThat(sources.greenhouseBoardTokens()).contains(
                    "gitlab", "grafanalabs", "elastic", "cloudflare", "datadog", "canonical",
                    "twilio");
            assertThat(sources.leverCompanyIds()).contains("weloglobal", "veeva");
            assertThat(sources.ashbyBoardNames()).contains("cohere", "elevenlabs");
            assertThat(sources.recruiteeCompanyIds()).contains("transperfect", "tether");
        });
    }

    @Test
    void theDevelopmentProfileRaisesTheResponseLimitTheseLargeBoardsNeed() {
        // Documents why the large-board tenants above are retained rather than removed.
        developmentRegistry.run(context -> {
            JobPilotProperties properties = context.getBean(JobPilotProperties.class);
            assertThat(properties.http().maxResponseBytes()).isGreaterThan(2_097_152);
        });
    }

    @Test
    void ashbyBoardNamesKeepTheirVerifiedCaseBecauseThePathIsCaseSensitive() {
        // 'Ashby' fetched successfully in run f4020aec; lower-casing it is not a safe
        // normalization, so case is deliberately preserved.
        withRegistry(sources -> assertThat(sources.ashbyBoardNames()).contains("Ashby"));
    }

    private void withRegistry(java.util.function.Consumer<JobPilotProperties.Sources> assertions) {
        developmentRegistry.run(context -> {
            assertThat(context).hasNotFailed();
            assertions.accept(context.getBean(JobPilotProperties.class).sources());
        });
    }

    private List<String> tenantsOf(JobPilotProperties.Sources sources, String provider) {
        return switch (provider.toLowerCase(Locale.ROOT)) {
            case "greenhouse" -> sources.greenhouseBoardTokens();
            case "lever" -> sources.leverCompanyIds();
            case "ashby" -> sources.ashbyBoardNames();
            case "recruitee" -> sources.recruiteeCompanyIds();
            default -> throw new IllegalArgumentException("Unknown provider " + provider);
        };
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(JobPilotProperties.class)
    static class RegistryConfiguration {
    }
}

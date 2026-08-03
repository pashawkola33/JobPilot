package com.jobpilot.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class SmartRecruitersConfigurationTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(PropertiesConfiguration.class)
            .withInitializer(new ConfigDataApplicationContextInitializer());

    @Test
    void productionDefaultStaysEmptyUntilTheEnvironmentSuppliesCompanies() {
        // application.yml binds ${SMARTRECRUITERS_COMPANY_IDENTIFIERS:}, so a deployment
        // that has not opted in fetches no SmartRecruiters board at all.
        runner.run(context -> assertThat(context.getBean(JobPilotProperties.class).sources()
                .smartrecruitersCompanyIdentifiers()).isEmpty());
    }

    @Test
    void developmentProfileCarriesOnlyTheLiveValidatedCompanies() {
        // Phase 3.3D activated the first two; Phase 3.3F added the three the scalar
        // reference fix cleared. Order is asserted literally to keep the registry
        // deterministic, and the provider path is case-sensitive.
        runner.withPropertyValues("spring.profiles.active=development")
                .run(context -> assertThat(context.getBean(JobPilotProperties.class).sources()
                        .smartrecruitersCompanyIdentifiers())
                        .containsExactly("BoschGroup", "AECOM2", "Ubisoft2", "Endava",
                                "Gameloft"));
    }

    @Test
    void environmentVariableBindsInCasePreservingDeclarationOrder() {
        runner.withSystemProperties("SMARTRECRUITERS_COMPANY_IDENTIFIERS=SecondCo,first.co,A-Co")
                .run(context -> assertThat(context.getBean(JobPilotProperties.class).sources()
                        .smartrecruitersCompanyIdentifiers())
                        .containsExactly("SecondCo", "first.co", "A-Co"));
    }

    @Test
    void rejectsWhitespaceBlankAndGrammarViolations() {
        for (String invalid : List.of("", " padded", "padded ", "a/b", "a%2Fb", "a?b",
                "a#b", "a:b", "x".repeat(64))) {
            assertThatThrownBy(() -> sources(List.of(invalid)))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void rejectsExactDuplicatesWithoutChangingCaseSemantics() {
        assertThatThrownBy(() -> sources(List.of("Example", "Example")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("duplicates");
        assertThat(sources(List.of("Example", "example")).smartrecruitersCompanyIdentifiers())
                .containsExactly("Example", "example");
    }

    @Test
    void acceptsOneHundredAndRejectsOneHundredOneCompanies() {
        List<String> hundred = IntStream.range(0, 100).mapToObj(index -> "company" + index).toList();
        assertThat(sources(hundred).smartrecruitersCompanyIdentifiers()).hasSize(100);
        assertThatThrownBy(() -> sources(IntStream.range(0, 101)
                .mapToObj(index -> "company" + index).toList()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at most 100");
    }

    private JobPilotProperties.Sources sources(List<String> smartRecruiters) {
        return new JobPilotProperties.Sources(List.of(), List.of(), List.of(), List.of(),
                smartRecruiters);
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(JobPilotProperties.class)
    static class PropertiesConfiguration { }
}

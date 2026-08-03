package com.jobpilot.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class SourceTenantConfigurationTest {
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(TenantPropertiesConfiguration.class);

    @Test
    void acceptsTheExactTenantGrammarForEveryProvider() {
        var sources = new JobPilotProperties.Sources(
                List.of("greenhouse_1"), List.of("lever.one"),
                List.of("ashby-board"), List.of("recruitee_1"), List.of("SmartCo"));

        assertThat(sources.recruiteeCompanyIds()).containsExactly("recruitee_1");
        assertThat(sources.smartrecruitersCompanyIdentifiers()).containsExactly("SmartCo");
    }

    @Test
    void invalidTenantFailsConfigurationConstructionForEveryProvider() {
        List<String> invalid = List.of("", " bad", "bad ", "a/b", "a\\b", "a:b", "a?b",
                "a#b", "a%2fb", "x".repeat(64));
        for (String value : invalid) {
            assertThatThrownBy(() -> new JobPilotProperties.Sources(
                    List.of(value), List.of(), List.of(), List.of(), List.of()))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new JobPilotProperties.Sources(
                    List.of(), List.of(value), List.of(), List.of(), List.of()))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new JobPilotProperties.Sources(
                    List.of(), List.of(), List.of(value), List.of(), List.of()))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new JobPilotProperties.Sources(
                    List.of(), List.of(), List.of(), List.of(value), List.of()))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new JobPilotProperties.Sources(
                    List.of(), List.of(), List.of(), List.of(), List.of(value)))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void invalidBoundTenantPreventsSpringStartupForEveryProvider() {
        List<String> properties = List.of(
                "jobpilot.sources.greenhouse-board-tokens[0]",
                "jobpilot.sources.lever-company-ids[0]",
                "jobpilot.sources.ashby-board-names[0]",
                "jobpilot.sources.recruitee-company-ids[0]",
                "jobpilot.sources.smartrecruiters-company-identifiers[0]");

        for (String property : properties) {
            contextRunner.withPropertyValues(property + "=tenant/path")
                    .run(context -> assertThat(context).hasFailed());
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(JobPilotProperties.class)
    static class TenantPropertiesConfiguration {
    }
}

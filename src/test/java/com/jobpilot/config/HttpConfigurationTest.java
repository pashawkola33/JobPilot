package com.jobpilot.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class HttpConfigurationTest {
    private static final Duration CONNECT = Duration.ofSeconds(1);
    private static final Duration RESPONSE = Duration.ofSeconds(2);
    private static final int VALID_BYTES = 10 * 1_024 * 1_024;

    /** Binds the real application.yml, so the deployed default is what is asserted. */
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(HttpPropertiesConfiguration.class)
            .withInitializer(new ConfigDataApplicationContextInitializer());

    @Test
    void rejectsUnsafeTimeoutBounds() {
        assertInvalid(Duration.ZERO, RESPONSE, VALID_BYTES);
        assertInvalid(Duration.ofSeconds(31), Duration.ofSeconds(40), VALID_BYTES);
        assertInvalid(RESPONSE, RESPONSE, VALID_BYTES);
        assertInvalid(CONNECT, Duration.ofSeconds(91), VALID_BYTES);
    }

    @Test
    void rejectsAResponseLimitBelowOneMebibyte() {
        assertInvalid(CONNECT, RESPONSE, JobPilotProperties.Http.MIN_RESPONSE_BYTES - 1);
        assertInvalid(CONNECT, RESPONSE, 1_024);
        assertInvalid(CONNECT, RESPONSE, 0);
    }

    @Test
    void rejectsAResponseLimitAboveThirtyTwoMebibytes() {
        assertInvalid(CONNECT, RESPONSE, JobPilotProperties.Http.MAX_RESPONSE_BYTES + 1);
        assertInvalid(CONNECT, RESPONSE, 64 * 1_024 * 1_024);
    }

    @Test
    void acceptsBothEndsOfTheValidRangeWithoutClamping() {
        assertThatCode(() -> new JobPilotProperties.Http(
                CONNECT, RESPONSE, JobPilotProperties.Http.MIN_RESPONSE_BYTES))
                .doesNotThrowAnyException();
        assertThat(new JobPilotProperties.Http(
                CONNECT, RESPONSE, JobPilotProperties.Http.MAX_RESPONSE_BYTES).maxResponseBytes())
                .isEqualTo(33_554_432);
        assertThat(JobPilotProperties.Http.MIN_RESPONSE_BYTES).isEqualTo(1_048_576);
    }

    @Test
    void invalidLimitNamesThePropertyAndTheRangeInsteadOfClamping() {
        assertThatThrownBy(() -> new JobPilotProperties.Http(CONNECT, RESPONSE, 512))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("jobpilot.http.max-response-bytes")
                .hasMessageContaining("1048576")
                .hasMessageContaining("33554432");
    }

    @Test
    void theDeployedDefaultIsTenMebibytes() {
        contextRunner.run(context -> assertThat(context.getBean(JobPilotProperties.class)
                .http().maxResponseBytes()).isEqualTo(10_485_760));
    }

    @Test
    void theEnvironmentOverrideBindsThroughTheDocumentedVariable() {
        contextRunner.withPropertyValues("JOBPILOT_HTTP_MAX_RESPONSE_BYTES=20971520")
                .run(context -> assertThat(context.getBean(JobPilotProperties.class)
                        .http().maxResponseBytes()).isEqualTo(20_971_520));
    }

    @Test
    void anOutOfRangeEnvironmentOverrideFailsStartupInsteadOfClamping() {
        contextRunner.withPropertyValues("JOBPILOT_HTTP_MAX_RESPONSE_BYTES=524288")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void theDevelopmentProfileNoLongerContradictsTheDeployedDefault() {
        contextRunner.withPropertyValues("spring.profiles.active=development")
                .run(context -> assertThat(context.getBean(JobPilotProperties.class)
                        .http().maxResponseBytes()).isEqualTo(10_485_760));
    }

    private void assertInvalid(Duration connect, Duration response, int bytes) {
        assertThatThrownBy(() -> new JobPilotProperties.Http(connect, response, bytes))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(JobPilotProperties.class)
    static class HttpPropertiesConfiguration {
    }
}

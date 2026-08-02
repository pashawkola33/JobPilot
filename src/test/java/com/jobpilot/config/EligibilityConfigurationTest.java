package com.jobpilot.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jobpilot.jobs.domain.RemoteScope;
import java.util.List;
import org.junit.jupiter.api.Test;

class EligibilityConfigurationTest {
    @Test
    void safeDefaultsTargetBucharestAndRejectUnknownRemoteScope() {
        var settings = JobPilotProperties.Eligibility.defaults();

        assertThat(settings.targetCity()).isEqualTo("Bucharest");
        assertThat(settings.targetCountry()).isEqualTo("Romania");
        assertThat(settings.includeIlfov()).isFalse();
        assertThat(settings.rejectUnknownRemoteScope()).isTrue();
        assertThat(settings.acceptedRemoteRegions()).containsExactly(
                RemoteScope.ROMANIA, RemoteScope.EU, RemoteScope.EEA,
                RemoteScope.EUROPE, RemoteScope.EMEA, RemoteScope.WORLDWIDE);
    }

    @Test
    void unsafeOrAmbiguousScopesCannotBeConfiguredAsAccepted() {
        assertThatThrownBy(() -> new JobPilotProperties.Eligibility(
                "Bucharest", "Romania", false, true, true, true, true, true,
                List.of(RemoteScope.ROMANIA, RemoteScope.UNKNOWN)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new JobPilotProperties.Eligibility(
                "Bucharest", "Romania", false, true, true, true, true, true,
                List.of(RemoteScope.COUNTRY_RESTRICTED)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}

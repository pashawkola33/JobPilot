package com.jobpilot.applications.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.jobpilot.applications.domain.ApplicationStatus;
import org.junit.jupiter.api.Test;

class ApplicationTransitionPolicyTest {
    private final ApplicationTransitionPolicy policy = new ApplicationTransitionPolicy();

    @Test
    void permitsOnlyTheExplicitTransitionGraphAndSameState() {
        assertThat(policy.canCreate(ApplicationStatus.SAVED)).isTrue();
        assertThat(policy.canCreate(ApplicationStatus.APPLIED)).isTrue();
        assertThat(policy.canCreate(ApplicationStatus.INTERVIEW)).isFalse();
        assertThat(policy.canTransition(ApplicationStatus.SAVED, ApplicationStatus.APPLIED)).isTrue();
        assertThat(policy.canTransition(ApplicationStatus.SAVED, ApplicationStatus.WITHDRAWN)).isTrue();
        assertThat(policy.canTransition(ApplicationStatus.APPLIED, ApplicationStatus.INTERVIEW)).isTrue();
        assertThat(policy.canTransition(ApplicationStatus.APPLIED, ApplicationStatus.REJECTED)).isTrue();
        assertThat(policy.canTransition(ApplicationStatus.APPLIED, ApplicationStatus.OFFER)).isTrue();
        assertThat(policy.canTransition(ApplicationStatus.APPLIED, ApplicationStatus.WITHDRAWN)).isTrue();
        assertThat(policy.canTransition(ApplicationStatus.INTERVIEW, ApplicationStatus.INTERVIEW)).isTrue();
        assertThat(policy.canTransition(ApplicationStatus.INTERVIEW, ApplicationStatus.REJECTED)).isTrue();
        assertThat(policy.canTransition(ApplicationStatus.INTERVIEW, ApplicationStatus.OFFER)).isTrue();
        assertThat(policy.canTransition(ApplicationStatus.INTERVIEW, ApplicationStatus.WITHDRAWN)).isTrue();
        assertThat(policy.canTransition(ApplicationStatus.OFFER, ApplicationStatus.WITHDRAWN)).isTrue();
        assertThat(policy.canTransition(ApplicationStatus.APPLIED, ApplicationStatus.SAVED)).isFalse();
        assertThat(policy.canTransition(ApplicationStatus.REJECTED, ApplicationStatus.OFFER)).isFalse();
        assertThat(policy.canTransition(ApplicationStatus.WITHDRAWN, ApplicationStatus.SAVED)).isFalse();
    }
}

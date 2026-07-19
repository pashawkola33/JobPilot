package com.jobpilot.resume.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jobpilot.resume.config.DocumentProperties;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class DocumentContactPolicyTest {
    @Test
    void acceptsBoundedSyntheticContactAndOptionalPhone() {
        DocumentContactPolicy policy = policy("student@example.test", "+1 202 555 0100",
                "https://example.test/code", "", "https://portfolio.example.test/profile");

        var contact = policy.requireValidContact();

        assertThat(contact.email()).isEqualTo("student@example.test");
        assertThat(contact.phone()).isEqualTo("+1 202 555 0100");
        assertThat(contact.links()).containsExactly(
                "https://example.test/code", "https://portfolio.example.test/profile");
    }

    @Test
    void rejectsMissingOrOverlyPermissiveEmailAndInvalidPhone() {
        assertThatThrownBy(() -> policy("", "", "", "", "").requireValidContact())
                .isInstanceOf(DocumentConfigurationException.class);
        assertThatThrownBy(() -> policy("student@localhost", "", "", "", "")
                .requireValidContact()).isInstanceOf(DocumentConfigurationException.class);
        assertThatThrownBy(() -> policy("student@example.test", "call-me", "", "", "")
                .requireValidContact()).isInstanceOf(DocumentConfigurationException.class);
    }

    @Test
    void rejectsCredentialsQueriesFragmentsLocalAndPrivateDestinationsAndUnsafeSchemes() {
        DocumentContactPolicy policy = policy("student@example.test", "", "", "", "");
        assertThat(policy.safeHttpsLink("https://example.test/profile")).isTrue();
        assertThat(policy.safeHttpsLink("http://example.test/profile")).isFalse();
        assertThat(policy.safeHttpsLink("javascript:alert(1)")).isFalse();
        assertThat(policy.safeHttpsLink("data:text/plain,secret")).isFalse();
        assertThat(policy.safeHttpsLink("file:///tmp/private")).isFalse();
        assertThat(policy.safeHttpsLink("https://user:pass@example.test/profile")).isFalse();
        assertThat(policy.safeHttpsLink("https://example.test/profile?token=secret")).isFalse();
        assertThat(policy.safeHttpsLink("https://example.test/profile#fragment")).isFalse();
        assertThat(policy.safeHttpsLink("https://localhost/profile")).isFalse();
        assertThat(policy.safeHttpsLink("https://127.0.0.1/profile")).isFalse();
        assertThat(policy.safeHttpsLink("https://10.0.0.1/profile")).isFalse();
    }

    private DocumentContactPolicy policy(String email, String phone, String github,
                                         String linkedin, String portfolio) {
        return new DocumentContactPolicy(new DocumentProperties(true,
                Path.of("data/documents"), 2_097_152, 2_097_152,
                "resume-v1", "cover-v1", "renderer-v1", 4_000,
                Duration.ofMinutes(10),
                "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
                new DocumentProperties.Contact(email, phone, github, linkedin, portfolio)));
    }
}

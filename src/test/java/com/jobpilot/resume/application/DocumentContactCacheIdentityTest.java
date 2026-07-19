package com.jobpilot.resume.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.jobpilot.common.Hashing;
import com.jobpilot.resume.config.DocumentProperties;
import com.jobpilot.resume.domain.DocumentContactBlock;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class DocumentContactCacheIdentityTest {
    private static final String FIRST_KEY =
            "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=";
    private static final String SECOND_KEY =
            "YWJjZGVmMDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODk=";

    @Test
    void keyedIdentityIsDeterministicAfterNormalizationAndChangesWithContactOrSecret() {
        DocumentContactBlock first = new DocumentContactBlock(
                " Student@Example.Test ", "+1  202 555 0100",
                List.of(" HTTPS://Example.Test/code "));
        DocumentContactBlock normalized = new DocumentContactBlock(
                "student@example.test", "+1 202 555 0100",
                List.of("https://example.test/code"));
        DocumentContactCacheIdentity identity = identity(FIRST_KEY, true);

        String fingerprint = identity.fingerprint(first);

        assertThat(fingerprint).hasSize(64).matches("[a-f0-9]{64}")
                .isEqualTo(identity.fingerprint(normalized));
        assertThat(identity.fingerprint(new DocumentContactBlock(
                "other@example.test", normalized.phone(), normalized.links())))
                .isNotEqualTo(fingerprint);
        assertThat(identity(SECOND_KEY, true).fingerprint(normalized))
                .isNotEqualTo(fingerprint);
        assertThat(identity.fingerprint(normalized, Optional.of("future-owner-a")))
                .isNotEqualTo(identity.fingerprint(normalized, Optional.of("future-owner-b")));

        String plain = Hashing.sha256(normalized.email() + "\0" + normalized.phone() + "\0"
                + String.join("\0", normalized.links()));
        assertThat(fingerprint).isNotEqualTo(plain);
    }

    @Test
    void missingMalformedAndWeakKeysFailOnlyWhenDocumentsAreEnabledWithoutLeakingInputs() {
        assertThat(properties(false, "")).isNotNull();
        assertThat(properties(false, "not-base64")).isNotNull();
        assertThat(properties(true, FIRST_KEY).toString())
                .doesNotContain(FIRST_KEY, "student@example.test", "+1 202 555 0100");

        assertSanitizedFailure("");
        assertSanitizedFailure("not-base64");
        assertSanitizedFailure("dG9vLXNob3J0");
    }

    private void assertSanitizedFailure(String key) {
        Throwable failure = org.assertj.core.api.Assertions.catchThrowable(
                () -> properties(true, key));
        assertThat(failure).isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining("student@example.test")
                .hasMessageNotContaining("+1 202 555 0100");
        if (!key.isEmpty()) assertThat(failure).hasMessageNotContaining(key);
    }

    private DocumentContactCacheIdentity identity(String key, boolean enabled) {
        DocumentProperties properties = properties(enabled, key);
        return new DocumentContactCacheIdentity(properties);
    }

    private DocumentProperties properties(boolean enabled, String key) {
        return new DocumentProperties(enabled, Path.of("data/documents"),
                2_097_152, 2_097_152, "resume-v1", "cover-v1", "renderer-v1", 4_000,
                Duration.ofMinutes(10), key, new DocumentProperties.Contact(
                "student@example.test", "+1 202 555 0100",
                "https://example.test/code", "", ""));
    }
}

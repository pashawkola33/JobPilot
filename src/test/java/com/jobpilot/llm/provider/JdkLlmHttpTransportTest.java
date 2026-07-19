package com.jobpilot.llm.provider;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jobpilot.llm.api.LlmProviderException;
import com.jobpilot.llm.domain.LlmFailureCategory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class JdkLlmHttpTransportTest {
    @Test
    void rejectsAnOversizedStreamWithoutRetainingResponseDataOrCause() {
        ByteArrayInputStream response = new ByteArrayInputStream(
                "synthetic-response-body".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> JdkLlmHttpTransport.readBounded(response, 8))
                .isInstanceOfSatisfying(LlmProviderException.class, exception -> {
                    org.assertj.core.api.Assertions.assertThat(exception.getCategory())
                            .isEqualTo(LlmFailureCategory.RESPONSE_TOO_LARGE);
                    org.assertj.core.api.Assertions.assertThat(exception.getMessage())
                            .doesNotContain("synthetic-response-body");
                    org.assertj.core.api.Assertions.assertThat(exception.getCause()).isNull();
                });
    }
}

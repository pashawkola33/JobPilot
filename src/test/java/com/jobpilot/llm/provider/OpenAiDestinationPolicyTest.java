package com.jobpilot.llm.provider;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jobpilot.llm.api.LlmProviderException;
import java.net.InetAddress;
import java.net.URI;
import java.util.List;
import org.junit.jupiter.api.Test;

class OpenAiDestinationPolicyTest {
    @Test
    void allowsOnlyTheOfficialEndpointWhenEveryResolvedAddressIsPublic() throws Exception {
        var policy = new OpenAiDestinationPolicy(host ->
                List.of(InetAddress.getByName("93.184.216.34")));

        assertThatCode(() -> policy.validateBeforeAuthorization(
                URI.create("https://api.openai.com/v1/responses"))).doesNotThrowAnyException();
    }

    @Test
    void rejectsIfAnyResolverAnswerIsProhibitedWithoutExposingTheDestination() throws Exception {
        var policy = new OpenAiDestinationPolicy(host -> List.of(
                InetAddress.getByName("93.184.216.34"),
                InetAddress.getByName("169.254.169.254")));

        assertThatThrownBy(() -> policy.validateBeforeAuthorization(
                URI.create("https://api.openai.com/v1/responses")))
                .isInstanceOfSatisfying(LlmProviderException.class, failure -> {
                    org.assertj.core.api.Assertions.assertThat(failure.getMessage())
                            .doesNotContain("api.openai.com", "169.254.169.254");
                    org.assertj.core.api.Assertions.assertThat(failure.getAmbiguousAttempts())
                            .isZero();
                });
    }

    @Test
    void rejectsAChangedEndpointBeforeResolution() {
        var policy = new OpenAiDestinationPolicy(host -> {
            throw new AssertionError("Resolver must not receive an invalid destination");
        });

        assertThatThrownBy(() -> policy.validateBeforeAuthorization(
                URI.create("https://attacker.api.openai.com.example/v1/responses")))
                .isInstanceOf(LlmProviderException.class);
    }
}

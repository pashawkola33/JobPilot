package com.jobpilot.llm.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jobpilot.config.JobPilotProperties;
import com.jobpilot.llm.api.LlmProviderException;
import com.jobpilot.llm.api.LlmRequest;
import com.jobpilot.llm.domain.LlmFailureCategory;
import com.jobpilot.support.TestProperties;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Queue;
import org.junit.jupiter.api.Test;

class OpenAiResponsesLlmProviderTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void sendsDocumentedStructuredResponsesShapeAndParsesUsage() throws Exception {
        FakeTransport transport = new FakeTransport(success(123, 45));
        RecordingSleeper sleeper = new RecordingSleeper();
        OpenAiResponsesLlmProvider provider = provider(transport, sleeper, 1);

        var response = provider.execute(request());

        assertThat(response.structuredJson()).isEqualTo("{\"roleSummary\":\"Synthetic role\"}");
        assertThat(response.inputTokens()).isEqualTo(123);
        assertThat(response.outputTokens()).isEqualTo(45);
        assertThat(response.physicalAttempts()).isEqualTo(1);
        assertThat(response.ambiguousAttemptsBeforeFinal()).isZero();
        ObjectNode sent = (ObjectNode) mapper.readTree(transport.body);
        assertThat(sent.path("store").asBoolean()).isFalse();
        assertThat(sent.path("max_output_tokens").asInt()).isEqualTo(500);
        assertThat(sent.path("text").path("format").path("type").asText()).isEqualTo("json_schema");
        assertThat(sent.path("text").path("format").path("strict").asBoolean()).isTrue();
        assertThat(sent.path("input")).hasSize(3);
        assertThat(transport.endpoint).isEqualTo(URI.create("https://api.openai.com/v1/responses"));
        assertThat(transport.body).doesNotContain("obviously-fake-secret");
    }

    @Test
    void missingUsageRemainsExplicitlyUnknownForConservativeServiceAccounting() {
        FakeTransport transport = new FakeTransport(success(null, null));

        var response = provider(transport, new RecordingSleeper(), 0).execute(request());

        assertThat(response.hasTokenUsage()).isFalse();
        assertThat(response.inputTokens()).isNull();
        assertThat(response.outputTokens()).isNull();
    }

    @Test
    void retriesOnlyBoundedRateLimitsAndServerFailures() {
        FakeTransport rateLimited = new FakeTransport(
                new LlmHttpResponse(429, "{}", "0"), success(1, 1));
        RecordingSleeper rateSleep = new RecordingSleeper();
        var afterRateLimit = provider(rateLimited, rateSleep, 1).execute(request());
        assertThat(afterRateLimit.inputTokens()).isEqualTo(1);
        assertThat(afterRateLimit.physicalAttempts()).isEqualTo(2);
        assertThat(afterRateLimit.ambiguousAttemptsBeforeFinal()).isEqualTo(1);
        assertThat(rateLimited.calls).isEqualTo(2);
        assertThat(rateSleep.calls).isEqualTo(1);

        FakeTransport serverFailure = new FakeTransport(
                new LlmHttpResponse(503, "{}", null), success(2, 2));
        assertThat(provider(serverFailure, new RecordingSleeper(), 1)
                .execute(request()).inputTokens()).isEqualTo(2);

        FakeTransport excessiveDelay = new FakeTransport(new LlmHttpResponse(429, "{}", "6"));
        assertThatThrownBy(() -> provider(excessiveDelay, new RecordingSleeper(), 1).execute(request()))
                .isInstanceOfSatisfying(LlmProviderException.class, exception ->
                        assertThat(exception.getCategory()).isEqualTo(LlmFailureCategory.RATE_LIMITED));
        assertThat(excessiveDelay.calls).isEqualTo(1);
    }

    @Test
    void allRetriesExhaustedCarryBoundedAmbiguousAttemptAccounting() {
        FakeTransport transport = new FakeTransport(
                new LlmHttpResponse(503, "{}", null),
                new LlmHttpResponse(503, "{}", null),
                new LlmHttpResponse(503, "{}", null));

        assertThatThrownBy(() -> provider(transport, new RecordingSleeper(), 2).execute(request()))
                .isInstanceOfSatisfying(LlmProviderException.class, failure -> {
                    assertThat(failure.getCategory()).isEqualTo(LlmFailureCategory.PROVIDER_ERROR);
                    assertThat(failure.getAmbiguousAttempts()).isEqualTo(3);
                });
        assertThat(transport.calls).isEqualTo(3);
    }

    @Test
    void explicitlyRejectsIncompleteNonCompletedRefusalMissingAndMultipleOutputs() {
        String text = "{\"type\":\"message\",\"content\":[{\"type\":\"output_text\","
                + "\"text\":\"{}\"}]}";
        assertProviderFailure("{\"status\":\"incomplete\",\"incomplete_details\":{"
                + "\"reason\":\"max_output_tokens\"},\"output\":[" + text + "]}");
        assertProviderFailure("{\"status\":\"failed\",\"output\":[" + text + "]}");
        assertProviderFailure("{\"status\":\"completed\",\"output\":[{\"type\":\"message\","
                + "\"content\":[{\"type\":\"refusal\",\"refusal\":\"no\"}]}]}");
        assertProviderFailure("{\"status\":\"completed\",\"output\":[]}");
        assertProviderFailure("{\"status\":\"completed\",\"output\":[" + text + "," + text + "]}");
    }

    @Test
    void doesNotRetryAuthenticationOrMalformedResponses() {
        for (int status : new int[] {401, 403}) {
            FakeTransport transport = new FakeTransport(new LlmHttpResponse(status, "{}", null));
            assertThatThrownBy(() -> provider(transport, new RecordingSleeper(), 3).execute(request()))
                    .isInstanceOfSatisfying(LlmProviderException.class, exception ->
                            assertThat(exception.getCategory())
                                    .isEqualTo(LlmFailureCategory.AUTHENTICATION));
            assertThat(transport.calls).isEqualTo(1);
        }
        FakeTransport malformed = new FakeTransport(new LlmHttpResponse(200, "not-json", null));
        assertThatThrownBy(() -> provider(malformed, new RecordingSleeper(), 3).execute(request()))
                .isInstanceOfSatisfying(LlmProviderException.class, exception ->
                        assertThat(exception.getCategory())
                                .isEqualTo(LlmFailureCategory.MALFORMED_RESPONSE));
        assertThat(malformed.calls).isEqualTo(1);
    }

    @Test
    void transportFailuresAndOversizedResponsesStaySanitized() {
        for (LlmFailureCategory category : new LlmFailureCategory[] {
                LlmFailureCategory.TIMEOUT, LlmFailureCategory.CONNECTION,
                LlmFailureCategory.RESPONSE_TOO_LARGE}) {
            LlmHttpTransport transport = (endpoint, key, body, timeout, maximum) -> {
                throw new LlmProviderException(category,
                        "Synthetic safe failure without credentials");
            };
            assertThatThrownBy(() -> provider(transport, new RecordingSleeper(), 1).execute(request()))
                    .isInstanceOfSatisfying(LlmProviderException.class, exception -> {
                        assertThat(exception.getCategory()).isEqualTo(category);
                        assertThat(exception.getMessage()).doesNotContain("obviously-fake-secret");
                        assertThat(exception.getCause()).isNull();
                        assertThat(exception.getAmbiguousAttempts()).isEqualTo(1);
                    });
        }
    }

    @Test
    void transportFailureIsNotRetriedByTheProviderLayerAndDisabledModeDoesNoIo() {
        LlmHttpTransport timeout = (endpoint, key, body, responseTimeout, maximum) -> {
            throw new LlmProviderException(LlmFailureCategory.TIMEOUT,
                    "Synthetic timeout", 1);
        };
        assertThatThrownBy(() -> provider(timeout, new RecordingSleeper(), 3).execute(request()))
                .isInstanceOfSatisfying(LlmProviderException.class, failure ->
                        assertThat(failure.getAmbiguousAttempts()).isEqualTo(1));

        FakeTransport unused = new FakeTransport(success(1, 1));
        var disabled = new OpenAiResponsesLlmProvider(unused, new RecordingSleeper(), mapper,
                TestProperties.create(JobPilotProperties.Llm.disabled()));
        assertThatThrownBy(() -> disabled.execute(request()))
                .isInstanceOfSatisfying(LlmProviderException.class, failure ->
                        assertThat(failure.getAmbiguousAttempts()).isZero());
        assertThat(unused.calls).isZero();
    }

    private OpenAiResponsesLlmProvider provider(LlmHttpTransport transport,
                                                LlmSleeper sleeper, int retries) {
        JobPilotProperties.Llm llm = new JobPilotProperties.Llm(true, "openai",
                "https://api.openai.com/v1", "obviously-fake-secret", "model-a",
                Duration.ofSeconds(1), Duration.ofSeconds(2), 1_000, 500, retries,
                new BigDecimal("1"), new BigDecimal("2"), new BigDecimal("3"),
                new BigDecimal("1"), new BigDecimal("2"));
        return new OpenAiResponsesLlmProvider(transport, sleeper, mapper,
                TestProperties.create(llm));
    }

    private LlmRequest request() {
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");
        schema.putObject("properties").putObject("roleSummary").put("type", "string");
        schema.putArray("required").add("roleSummary");
        schema.put("additionalProperties", false);
        return new LlmRequest("Trusted synthetic instructions", "{\"facts\":[]}",
                "{\"description\":\"Synthetic vacancy\"}", schema, 500);
    }

    private LlmHttpResponse success(Integer input, Integer output) {
        String usage = input == null ? "" : ",\"usage\":{\"input_tokens\":" + input
                + ",\"output_tokens\":" + output + "}";
        return new LlmHttpResponse(200,
                "{\"status\":\"completed\",\"output\":[{\"type\":\"message\",\"content\":[{\"type\":\"output_text\","
                        + "\"text\":\"{\\\"roleSummary\\\":\\\"Synthetic role\\\"}\"}]}]"
                        + usage + "}", null);
    }

    private void assertProviderFailure(String body) {
        FakeTransport transport = new FakeTransport(new LlmHttpResponse(200, body, null));
        assertThatThrownBy(() -> provider(transport, new RecordingSleeper(), 2).execute(request()))
                .isInstanceOfSatisfying(LlmProviderException.class, failure -> {
                    assertThat(failure.getAmbiguousAttempts()).isEqualTo(1);
                    assertThat(failure.getCategory()).isIn(
                            LlmFailureCategory.PROVIDER_ERROR,
                            LlmFailureCategory.MALFORMED_RESPONSE);
                });
        assertThat(transport.calls).isEqualTo(1);
    }

    private static class FakeTransport implements LlmHttpTransport {
        private final Queue<LlmHttpResponse> responses = new ArrayDeque<>();
        private int calls;
        private URI endpoint;
        private String body;

        FakeTransport(LlmHttpResponse... responses) {
            this.responses.addAll(java.util.List.of(responses));
        }

        @Override
        public LlmHttpResponse post(URI endpoint, String apiKey, String jsonBody,
                                    Duration responseTimeout, int maxResponseBytes) {
            calls++;
            this.endpoint = endpoint;
            body = jsonBody;
            return responses.remove();
        }
    }

    private static class RecordingSleeper extends LlmSleeper {
        private int calls;

        @Override
        public void sleep(Duration duration) {
            calls++;
        }
    }
}

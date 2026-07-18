package com.jobpilot.common;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobpilot.config.JobPilotProperties;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.function.Supplier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

@Component
public class ExternalHttpClient {
    private static final int MAX_ATTEMPTS = 3;
    private static final long MAX_RETRY_AFTER_MILLIS = 30_000L;
    private final RestClient client;
    private final ObjectMapper objectMapper;
    private final int maxResponseBytes;

    public ExternalHttpClient(RestClient client, ObjectMapper objectMapper, JobPilotProperties properties) {
        this.client = client;
        this.objectMapper = objectMapper;
        this.maxResponseBytes = properties.http().maxResponseBytes();
    }

    public JsonNode getJson(String url) {
        return readJson(withRetry(() -> client.get().uri(url).accept(MediaType.APPLICATION_JSON)
                .retrieve().body(byte[].class)));
    }

    public JsonNode postJson(String url, Object body) {
        return readJson(withRetry(() -> client.post().uri(url).contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON).body(body)
                .retrieve().body(byte[].class)));
    }

    public String getText(String url) {
        byte[] body = withRetry(() -> client.get().uri(url).accept(MediaType.TEXT_HTML, MediaType.APPLICATION_XML)
                .retrieve().body(byte[].class));
        requireBounded(body);
        return new String(body, StandardCharsets.UTF_8);
    }

    private JsonNode readJson(byte[] body) {
        requireBounded(body);
        try {
            return objectMapper.readTree(body);
        } catch (java.io.IOException exception) {
            throw new IllegalArgumentException("Remote service returned invalid JSON", exception);
        }
    }

    private void requireBounded(byte[] body) {
        if (body == null || body.length > maxResponseBytes) {
            throw new IllegalArgumentException("Remote response is empty or exceeds the configured size limit");
        }
    }

    private <T> T withRetry(Supplier<T> request) {
        RuntimeException last = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                return request.get();
            } catch (ResourceAccessException exception) {
                last = exception;
            } catch (RestClientResponseException exception) {
                if (!isRetryable(exception)) throw exception;
                last = exception;
            }
            if (attempt < MAX_ATTEMPTS) {
                pause(retryDelayMillis(attempt, last));
            }
        }
        throw last;
    }

    private boolean isRetryable(RestClientResponseException exception) {
        return exception.getStatusCode().is5xxServerError()
                || exception.getStatusCode().value() == 429;
    }

    private long retryDelayMillis(int attempt, RuntimeException failure) {
        if (failure instanceof RestClientResponseException response && response.getResponseHeaders() != null) {
            String retryAfter = response.getResponseHeaders().getFirst("Retry-After");
            if (retryAfter != null && retryAfter.matches("\\d+")) {
                return Math.min(Long.parseLong(retryAfter) * 1000L, MAX_RETRY_AFTER_MILLIS);
            }
        }
        return 400L << (attempt - 1);
    }

    // Overridable so tests can record delays instead of really sleeping.
    protected void pause(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("HTTP retry interrupted", interrupted);
        }
    }
}

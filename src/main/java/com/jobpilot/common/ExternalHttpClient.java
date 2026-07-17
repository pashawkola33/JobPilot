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
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                return request.get();
            } catch (ResourceAccessException exception) {
                last = exception;
            } catch (RestClientResponseException exception) {
                if (!exception.getStatusCode().is5xxServerError()) throw exception;
                last = exception;
            }
            try {
                Thread.sleep(400L << attempt);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("HTTP retry interrupted", interrupted);
            }
        }
        throw last;
    }
}

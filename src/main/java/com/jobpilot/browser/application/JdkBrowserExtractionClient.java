package com.jobpilot.browser.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jobpilot.browser.api.BrowserExtractionRequest;
import com.jobpilot.browser.api.BrowserExtractionResponse;
import com.jobpilot.browser.config.ScraperWorkerProperties;
import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import org.springframework.stereotype.Component;

/**
 * JDK HttpClient worker client. Targets only the single configured endpoint with
 * redirects disabled, bounds the response body, deserializes typed JSON, and
 * returns a conservative WORKER_UNAVAILABLE on any transport or shape failure.
 * It never logs the URL, description, response body, or the shared secret, and
 * never retains an exception cause that could contain them.
 */
@Component
public class JdkBrowserExtractionClient implements BrowserExtractionClient {
    static final String SECRET_HEADER = "x-jobpilot-worker-secret";

    private final ScraperWorkerProperties settings;
    private final ObjectMapper mapper;
    private final HttpClient client;

    public JdkBrowserExtractionClient(ScraperWorkerProperties settings, ObjectMapper mapper) {
        this.settings = settings;
        this.mapper = mapper;
        this.client = HttpClient.newBuilder()
                .connectTimeout(settings.connectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    @Override
    public BrowserExtractionResponse extract(BrowserExtractionRequest request) {
        if (!settings.enabled()) return BrowserExtractionResponse.unavailable();
        final String body;
        try {
            body = requestBody(request);
        } catch (RuntimeException serialization) {
            return BrowserExtractionResponse.unavailable();
        }
        try {
            HttpRequest httpRequest = HttpRequest.newBuilder(settings.extractEndpoint())
                    .timeout(settings.responseTimeout())
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .header(SECRET_HEADER, settings.sharedSecret())
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<InputStream> response = client.send(
                    httpRequest, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                return BrowserExtractionResponse.unavailable();
            }
            byte[] bytes = readBounded(response.body(), settings.maxResponseBytes());
            if (bytes == null) return BrowserExtractionResponse.unavailable();
            BrowserExtractionResponse parsed = mapper.readValue(bytes, BrowserExtractionResponse.class);
            return parsed.status() == null ? BrowserExtractionResponse.unavailable() : parsed;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return BrowserExtractionResponse.unavailable();
        } catch (IOException | RuntimeException failure) {
            return BrowserExtractionResponse.unavailable();
        }
    }

    private String requestBody(BrowserExtractionRequest request) {
        ObjectNode node = mapper.createObjectNode();
        node.put("requestId", request.requestId());
        node.put("url", request.url());
        if (request.expectedSource() != null && !request.expectedSource().isBlank()) {
            node.put("expectedSource", request.expectedSource());
        }
        try {
            return mapper.writeValueAsString(node);
        } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
            throw new IllegalStateException("Could not construct the worker request");
        }
    }

    private byte[] readBounded(InputStream input, int maximum) throws IOException {
        try (input) {
            byte[] bytes = input.readNBytes(maximum + 1);
            return bytes.length > maximum ? null : bytes;
        }
    }
}

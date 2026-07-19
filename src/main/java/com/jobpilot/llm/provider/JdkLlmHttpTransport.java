package com.jobpilot.llm.provider;

import com.jobpilot.config.JobPilotProperties;
import com.jobpilot.llm.api.LlmProviderException;
import com.jobpilot.llm.domain.LlmFailureCategory;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.springframework.stereotype.Component;

@Component
public class JdkLlmHttpTransport implements LlmHttpTransport {
    private final HttpClient client;
    private final OpenAiDestinationPolicy destinations;

    public JdkLlmHttpTransport(JobPilotProperties properties, OpenAiDestinationPolicy destinations) {
        this.destinations = destinations;
        client = HttpClient.newBuilder()
                .connectTimeout(properties.llm().connectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    @Override
    public LlmHttpResponse post(URI endpoint, String apiKey, String jsonBody,
                                Duration responseTimeout, int maxResponseBytes) {
        try {
            destinations.validateBeforeAuthorization(endpoint);
            HttpRequest request = HttpRequest.newBuilder(endpoint)
                    .timeout(responseTimeout)
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .header("User-Agent", "JobPilot/0.1")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<InputStream> response = client.send(
                    request, HttpResponse.BodyHandlers.ofInputStream());
            String body;
            try (InputStream stream = response.body()) {
                body = readBounded(stream, maxResponseBytes);
            }
            return new LlmHttpResponse(response.statusCode(), body,
                    response.headers().firstValue("Retry-After").orElse(null));
        } catch (HttpTimeoutException exception) {
            throw safe(LlmFailureCategory.TIMEOUT, "LLM provider timed out", 1);
        } catch (ConnectException exception) {
            throw safe(LlmFailureCategory.CONNECTION, "LLM provider connection failed", 0);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw safe(LlmFailureCategory.CONNECTION, "LLM provider request was interrupted", 1);
        } catch (IOException | RuntimeException exception) {
            if (exception instanceof LlmProviderException safe) throw safe;
            throw safe(LlmFailureCategory.CONNECTION, "LLM provider transport failed", 1);
        }
    }

    static String readBounded(InputStream stream, int maximum) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min(maximum, 64 * 1024));
        byte[] buffer = new byte[8192];
        int total = 0;
        for (int read; (read = stream.read(buffer)) >= 0;) {
            total += read;
            if (total > maximum) {
                throw safe(LlmFailureCategory.RESPONSE_TOO_LARGE,
                        "LLM provider response exceeded the configured bound", 1);
            }
            output.write(buffer, 0, read);
        }
        return output.toString(StandardCharsets.UTF_8);
    }

    private static LlmProviderException safe(LlmFailureCategory category, String message) {
        return new LlmProviderException(category, message);
    }

    private static LlmProviderException safe(LlmFailureCategory category, String message,
                                             int ambiguousAttempts) {
        return new LlmProviderException(category, message, ambiguousAttempts);
    }
}

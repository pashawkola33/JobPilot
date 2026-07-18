package com.jobpilot.manualurl.fetch;

import com.jobpilot.config.JobPilotProperties;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.stereotype.Component;

@Component
public class JdkManualHttpTransport implements ManualHttpTransport {
    private final HttpClient client;
    private final ExecutorService bodyReaders = Executors.newVirtualThreadPerTaskExecutor();

    public JdkManualHttpTransport(JobPilotProperties properties) {
        this.client = HttpClient.newBuilder()
                .connectTimeout(properties.manualUrl().connectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    @Override
    public ManualHttpResponse get(URI uri, Duration responseTimeout, int maxResponseBytes) {
        long deadline = System.nanoTime() + responseTimeout.toNanos();
        HttpRequest request = HttpRequest.newBuilder(uri)
                .GET()
                .timeout(responseTimeout)
                .header("Accept", "text/html,application/xhtml+xml,application/ld+json,application/json,text/plain")
                .header("User-Agent", "JobPilot/0.2 (+human-in-the-loop manual vacancy review)")
                .build();
        try {
            HttpResponse<InputStream> response = client.send(
                    request, HttpResponse.BodyHandlers.ofInputStream());
            byte[] body = readBody(response.body(), maxResponseBytes, deadline);
            if (body.length > maxResponseBytes) {
                throw new ManualFetchException(ManualFetchException.Category.RESPONSE_TOO_LARGE,
                        "Remote response exceeded the configured size limit");
            }
            return new ManualHttpResponse(response.statusCode(),
                    response.headers().firstValue("Content-Type").orElse(null),
                    response.headers().firstValue("Location").orElse(null), body);
        } catch (HttpTimeoutException exception) {
            throw new ManualFetchException(
                    ManualFetchException.Category.TIMEOUT, "Remote request timed out", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new ManualFetchException(
                    ManualFetchException.Category.NETWORK_FAILURE, "Remote request was interrupted", exception);
        } catch (IOException exception) {
            throw new ManualFetchException(
                    ManualFetchException.Category.NETWORK_FAILURE, "Remote request failed", exception);
        }
    }

    byte[] readBody(InputStream input, int maxResponseBytes, long deadline)
            throws IOException, InterruptedException {
        long remainingNanos = deadline - System.nanoTime();
        if (remainingNanos <= 0) {
            closeQuietly(input);
            throw timeout();
        }
        Future<byte[]> read = bodyReaders.submit(() -> {
            try (input) {
                return input.readNBytes(maxResponseBytes + 1);
            }
        });
        try {
            return read.get(remainingNanos, TimeUnit.NANOSECONDS);
        } catch (TimeoutException exception) {
            closeQuietly(input);
            read.cancel(true);
            throw timeout(exception);
        } catch (InterruptedException exception) {
            closeQuietly(input);
            read.cancel(true);
            throw exception;
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof IOException ioException) {
                throw ioException;
            }
            throw new IOException("Remote response body could not be read", cause);
        }
    }

    private ManualFetchException timeout() {
        return new ManualFetchException(
                ManualFetchException.Category.TIMEOUT, "Remote request timed out");
    }

    private ManualFetchException timeout(Throwable cause) {
        return new ManualFetchException(
                ManualFetchException.Category.TIMEOUT, "Remote request timed out", cause);
    }

    private void closeQuietly(InputStream input) {
        try {
            input.close();
        } catch (IOException ignored) {
            // Closing is best-effort after the bounded read deadline expires.
        }
    }

    @PreDestroy
    void close() {
        bodyReaders.shutdownNow();
    }
}

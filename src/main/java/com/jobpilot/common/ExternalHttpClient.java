package com.jobpilot.common;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobpilot.common.net.ProhibitedAddressClassifier;
import com.jobpilot.config.JobPilotProperties;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Bounded external JSON transport. Redirects are manual, every hop stays in the
 * original provider family, DNS answers are screened, content types are
 * enforced, and response bodies are streamed only up to the configured limit.
 */
@Component
public class ExternalHttpClient {
    private static final int MAX_ATTEMPTS = 3;
    private static final int MAX_REDIRECTS = 5;
    private static final long MAX_RETRY_AFTER_MILLIS = 30_000L;

    private final ObjectMapper objectMapper;
    private final int maxResponseBytes;
    private final Duration responseTimeout;
    private final HttpClient client;
    private final URI testOrigin;

    @Autowired
    public ExternalHttpClient(ObjectMapper objectMapper, JobPilotProperties properties) {
        this(objectMapper, properties.http(), null);
    }

    ExternalHttpClient(ObjectMapper objectMapper, JobPilotProperties.Http settings, URI testOrigin) {
        this(objectMapper, settings.connectTimeout(), settings.responseTimeout(),
                settings.maxResponseBytes(), testOrigin);
    }

    /**
     * Explicit-bound seam. The operator-facing range check lives in
     * {@link JobPilotProperties.Http}, which guards configuration binding; the transport
     * itself simply honours whatever bound it is handed. That lets boundary tests drive a
     * few kilobytes without weakening the production configuration validation.
     */
    ExternalHttpClient(ObjectMapper objectMapper, Duration connectTimeout,
                       Duration responseTimeout, int maxResponseBytes, URI testOrigin) {
        if (maxResponseBytes < 1) {
            throw new IllegalArgumentException("maxResponseBytes must be positive");
        }
        this.objectMapper = objectMapper;
        this.maxResponseBytes = maxResponseBytes;
        this.responseTimeout = responseTimeout;
        this.client = HttpClient.newBuilder()
                .connectTimeout(connectTimeout)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        this.testOrigin = testOrigin;
    }

    public JsonNode getJson(String url) {
        return withRetry("GET", URI.create(url), null);
    }

    public JsonNode postJson(String url, Object body) {
        try {
            return withRetry("POST", URI.create(url), objectMapper.writeValueAsBytes(body));
        } catch (JsonProcessingException failure) {
            throw new ExternalHttpException(ExternalHttpException.Category.MALFORMED_JSON, null);
        }
    }

    public String getText(String url) {
        URI uri = URI.create(url);
        Destination destination = validateInitial(uri);
        HttpResponse<InputStream> response = sendFollowingRedirects("GET", uri, null, destination);
        try (InputStream body = response.body()) {
            requireSuccess(response);
            requireContentType(response, false);
            requireDeclaredSizeWithinLimit(response);
            return new String(readBounded(body), StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new ExternalHttpException(ExternalHttpException.Category.IO, null);
        }
    }

    private JsonNode withRetry(String method, URI uri, byte[] requestBody) {
        ExternalHttpException last = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                return executeJson(method, uri, requestBody);
            } catch (ExternalHttpException failure) {
                if (!retryable(failure) || attempt == MAX_ATTEMPTS) throw failure;
                last = failure;
                pause(retryDelayMillis(attempt, failure));
            }
        }
        throw last == null ? new ExternalHttpException(ExternalHttpException.Category.IO, null) : last;
    }

    private JsonNode executeJson(String method, URI uri, byte[] requestBody) {
        Destination destination = validateInitial(uri);
        HttpResponse<InputStream> response = sendFollowingRedirects(method, uri, requestBody, destination);
        try (InputStream body = response.body()) {
            requireSuccess(response);
            requireContentType(response, true);
            requireDeclaredSizeWithinLimit(response);
            byte[] bytes = readBounded(body);
            try {
                JsonNode parsed = objectMapper.readTree(bytes);
                if (parsed == null) throw new JsonProcessingException("empty JSON") { };
                return parsed;
            } catch (JsonProcessingException malformed) {
                throw new ExternalHttpException(ExternalHttpException.Category.MALFORMED_JSON, null);
            }
        } catch (IOException failure) {
            throw new ExternalHttpException(ExternalHttpException.Category.IO, null);
        }
    }

    private HttpResponse<InputStream> sendFollowingRedirects(
            String initialMethod, URI initialUri, byte[] initialBody, Destination destination) {
        String method = initialMethod;
        byte[] body = initialBody;
        URI uri = initialUri;
        for (int redirects = 0; ; redirects++) {
            validateHop(uri, destination);
            HttpResponse<InputStream> response = send(method, uri, body);
            if (!redirectStatus(response.statusCode())) return response;
            closeQuietly(response.body());
            if (redirects >= MAX_REDIRECTS) {
                throw new ExternalHttpException(ExternalHttpException.Category.REDIRECT_LIMIT, null);
            }
            String location = response.headers().firstValue("location")
                    .orElseThrow(() -> new ExternalHttpException(
                            ExternalHttpException.Category.INVALID_DESTINATION, null));
            try {
                uri = uri.resolve(location);
            } catch (RuntimeException invalid) {
                throw new ExternalHttpException(ExternalHttpException.Category.INVALID_DESTINATION, null);
            }
            if (response.statusCode() == 301 || response.statusCode() == 302
                    || response.statusCode() == 303) {
                method = "GET";
                body = null;
            }
        }
    }

    private HttpResponse<InputStream> send(String method, URI uri, byte[] body) {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri)
                .timeout(responseTimeout)
                .header("Accept", "application/json")
                .header("User-Agent", "JobPilot/0.1 (+human-in-the-loop job discovery)");
        if (body == null || method.equals("GET")) {
            request.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            request.header("Content-Type", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofByteArray(body));
        }
        try {
            return client.send(request.build(), HttpResponse.BodyHandlers.ofInputStream());
        } catch (java.net.http.HttpTimeoutException timeout) {
            throw new ExternalHttpException(ExternalHttpException.Category.TIMEOUT, null);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new ExternalHttpException(ExternalHttpException.Category.IO, null);
        } catch (IOException failure) {
            throw new ExternalHttpException(ExternalHttpException.Category.IO, null);
        }
    }

    private Destination validateInitial(URI uri) {
        if (testOrigin != null && sameOrigin(testOrigin, uri)) {
            return new Destination(DestinationFamily.TEST, testOrigin.getHost().toLowerCase(Locale.ROOT));
        }
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getPort() != -1 && uri.getPort() != 443
                || uri.getUserInfo() != null || uri.getHost() == null || uri.getFragment() != null) {
            throw new ExternalHttpException(ExternalHttpException.Category.INVALID_DESTINATION, null);
        }
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        DestinationFamily family = family(host);
        if (family == null) {
            throw new ExternalHttpException(ExternalHttpException.Category.INVALID_DESTINATION, null);
        }
        validateAddresses(host);
        return new Destination(family, host);
    }

    private void validateHop(URI uri, Destination destination) {
        if (destination.family() == DestinationFamily.TEST) {
            if (!sameOrigin(testOrigin, uri)) {
                throw new ExternalHttpException(ExternalHttpException.Category.INVALID_DESTINATION, null);
            }
            return;
        }
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getPort() != -1 && uri.getPort() != 443
                || uri.getUserInfo() != null || uri.getHost() == null || uri.getFragment() != null) {
            throw new ExternalHttpException(ExternalHttpException.Category.INVALID_DESTINATION, null);
        }
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        DestinationFamily family = family(host);
        if (family != destination.family()
                || family == DestinationFamily.RECRUITEE && !host.equals(destination.initialHost())) {
            throw new ExternalHttpException(ExternalHttpException.Category.INVALID_DESTINATION, null);
        }
        validateAddresses(host);
    }

    private DestinationFamily family(String host) {
        if (host.equals("boards-api.greenhouse.io") || host.equals("boards-api.eu.greenhouse.io")) {
            return DestinationFamily.GREENHOUSE;
        }
        if (host.equals("api.lever.co") || host.equals("api.eu.lever.co")) return DestinationFamily.LEVER;
        if (host.equals("api.ashbyhq.com")) return DestinationFamily.ASHBY;
        if (host.endsWith(".recruitee.com")) {
            String tenant = host.substring(0, host.length() - ".recruitee.com".length());
            if (tenant.matches("[a-zA-Z0-9][a-zA-Z0-9._-]{0,62}")) {
                return DestinationFamily.RECRUITEE;
            }
        }
        if (host.equals("api.telegram.org")) return DestinationFamily.TELEGRAM;
        return null;
    }

    private void validateAddresses(String host) {
        try {
            InetAddress[] addresses = InetAddress.getAllByName(host);
            if (addresses.length == 0) throw new IOException("no addresses");
            for (InetAddress address : addresses) {
                if (ProhibitedAddressClassifier.isProhibited(address)) {
                    throw new ExternalHttpException(
                            ExternalHttpException.Category.INVALID_DESTINATION, null);
                }
            }
        } catch (ExternalHttpException prohibited) {
            throw prohibited;
        } catch (IOException resolutionFailure) {
            throw new ExternalHttpException(ExternalHttpException.Category.INVALID_DESTINATION, null);
        }
    }

    /**
     * Rejects a response whose declared length already exceeds the limit, before any
     * body byte is consumed. Absent, malformed, or chunked-encoding responses declare
     * nothing and fall through to the streaming bound in {@link #readBounded}.
     */
    private void requireDeclaredSizeWithinLimit(HttpResponse<?> response) {
        response.headers().firstValueAsLong("content-length").ifPresent(declared -> {
            if (declared > maxResponseBytes) throw oversized();
        });
    }

    /**
     * Reads at most {@code maxResponseBytes + 1} bytes. The extra byte only detects
     * overflow; it is never returned, and the partial buffer is dropped unreferenced so
     * no fragment can reach an exception, a log, or the database. The caller's
     * try-with-resources closes the stream on both paths, abandoning the remainder.
     */
    private byte[] readBounded(InputStream input) throws IOException {
        byte[] bytes = input.readNBytes(maxResponseBytes + 1);
        if (bytes.length > maxResponseBytes) throw oversized();
        if (bytes.length == 0) {
            // An empty body is a malformed payload, not an oversized one.
            throw new ExternalHttpException(ExternalHttpException.Category.MALFORMED_JSON, null);
        }
        return bytes;
    }

    /** Carries the configured limit structurally; callers never parse a message. */
    private ExternalHttpException oversized() {
        return new ExternalHttpException(ExternalHttpException.Category.RESPONSE_TOO_LARGE, null)
                .limitBytes(maxResponseBytes);
    }

    private void requireSuccess(HttpResponse<?> response) {
        int status = response.statusCode();
        if (status < 200 || status >= 300) {
            ExternalHttpException failure = new ExternalHttpException(
                    ExternalHttpException.Category.HTTP_STATUS, status);
            response.headers().firstValue("retry-after")
                    .filter(value -> value.matches("\\d+"))
                    .map(Long::parseLong).ifPresent(failure::retryAfterSeconds);
            throw failure;
        }
    }

    private void requireContentType(HttpResponse<?> response, boolean json) {
        String raw = response.headers().firstValue("content-type").orElse("");
        String mediaType = raw.split(";", 2)[0].strip().toLowerCase(Locale.ROOT);
        boolean valid = json ? mediaType.equals("application/json") || mediaType.endsWith("+json")
                : mediaType.equals("text/html") || mediaType.equals("application/xhtml+xml")
                || mediaType.equals("application/xml") || mediaType.equals("text/xml");
        if (!valid) throw new ExternalHttpException(ExternalHttpException.Category.INVALID_CONTENT_TYPE, null);
    }

    private boolean retryable(ExternalHttpException failure) {
        return failure.category() == ExternalHttpException.Category.TIMEOUT
                || failure.category() == ExternalHttpException.Category.IO
                || failure.category() == ExternalHttpException.Category.HTTP_STATUS
                && failure.statusCode() != null
                && (failure.statusCode() == 429 || failure.statusCode() >= 500);
    }

    private long retryDelayMillis(int attempt, ExternalHttpException failure) {
        if (failure.retryAfterSeconds() != null) {
            return Math.min(failure.retryAfterSeconds() * 1_000L, MAX_RETRY_AFTER_MILLIS);
        }
        return 400L << (attempt - 1);
    }

    private boolean redirectStatus(int status) {
        return status == 301 || status == 302 || status == 303 || status == 307 || status == 308;
    }

    private boolean sameOrigin(URI left, URI right) {
        return left != null && right != null
                && left.getScheme().equalsIgnoreCase(right.getScheme())
                && left.getHost().equalsIgnoreCase(right.getHost())
                && effectivePort(left) == effectivePort(right);
    }

    private int effectivePort(URI uri) {
        if (uri.getPort() >= 0) return uri.getPort();
        return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }

    private void closeQuietly(InputStream input) {
        try {
            input.close();
        } catch (IOException ignored) {
            // The request is already being rejected.
        }
    }

    protected void pause(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new ExternalHttpException(ExternalHttpException.Category.IO, null);
        }
    }

    private enum DestinationFamily { GREENHOUSE, LEVER, ASHBY, RECRUITEE, TELEGRAM, TEST }

    private record Destination(DestinationFamily family, String initialHost) {
    }
}

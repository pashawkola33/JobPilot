package com.jobpilot.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobpilot.config.JobPilotProperties;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ExternalHttpClientTest {
    /** Small deliberately: boundary behaviour must not need 10 MiB payloads. */
    private static final int LIMIT = 1_024;
    /** Declared length, far above LIMIT, that must be refused from the header alone. */
    private static final long DECLARED_OVERSIZE = 8L * 1_024 * 1_024;
    /** Chunked stream large enough that socket buffers cannot absorb it whole. */
    private static final long CHUNKED_TOTAL = 8L * 1_024 * 1_024;

    private final AtomicLong chunkedBytesWritten = new AtomicLong();
    private final AtomicLong declaredOversizeBytesWritten = new AtomicLong();
    private final AtomicInteger serverErrorAttempts = new AtomicInteger();
    private HttpServer server;
    private URI origin;
    private RecordingClient client;

    private static final class RecordingClient extends ExternalHttpClient {
        private final List<Long> pauses = new ArrayList<>();

        private RecordingClient(int maxResponseBytes, URI origin) {
            super(new ObjectMapper(), Duration.ofMillis(50), Duration.ofMillis(100),
                    maxResponseBytes, origin);
        }

        @Override
        protected void pause(long millis) {
            pauses.add(millis);
        }
    }

    @BeforeEach
    void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/valid", exchange -> json(exchange, 200, "{\"ok\":true}"));
        server.createContext("/wrong-type", exchange -> respond(exchange, 200, "text/html", "<html>no</html>"));
        server.createContext("/oversized", exchange -> json(exchange, 200, "x".repeat(2_000)));
        server.createContext("/at-limit", exchange -> json(exchange, 200, padded(LIMIT)));
        server.createContext("/one-over-limit", exchange -> json(exchange, 200, padded(LIMIT + 1)));
        // Declares a Content-Length far above the bound, then streams it slowly. The
        // client must reject on the header, so most of the declared body is never sent.
        server.createContext("/declared-oversize", exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, DECLARED_OVERSIZE);
            byte[] slice = new byte[1_024];
            java.util.Arrays.fill(slice, (byte) 'x');
            try (var out = exchange.getResponseBody()) {
                for (long sent = 0; sent < DECLARED_OVERSIZE; sent += slice.length) {
                    out.write(slice);
                    out.flush();
                    declaredOversizeBytesWritten.addAndGet(slice.length);
                }
            } catch (IOException abandoned) {
                // Expected: the client rejected on the header and closed the stream.
            }
            exchange.close();
        });
        // Chunked: no Content-Length, emitted in slices past the bound.
        server.createContext("/chunked-oversize", exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, 0);
            byte[] slice = new byte[1_024];
            java.util.Arrays.fill(slice, (byte) 'x');
            try (var out = exchange.getResponseBody()) {
                for (long written = 0; written < CHUNKED_TOTAL; written += slice.length) {
                    out.write(slice);
                    out.flush();
                    chunkedBytesWritten.addAndGet(slice.length);
                }
            } catch (IOException disconnected) {
                // Expected: the client stops reading at the bound and closes the stream.
            }
            exchange.close();
        });
        server.createContext("/chunked-within-limit", exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, 0);
            try (var out = exchange.getResponseBody()) {
                out.write("{\"ok\":true}".getBytes(StandardCharsets.UTF_8));
            }
            exchange.close();
        });
        server.createContext("/empty", exchange -> json(exchange, 200, ""));
        server.createContext("/server-error", exchange -> {
            serverErrorAttempts.incrementAndGet();
            json(exchange, 500, "{\"error\":true}");
        });
        server.createContext("/private-redirect", exchange -> {
            exchange.getResponseHeaders().add("Location",
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/valid");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.createContext("/redirect", this::redirect);
        server.createContext("/timeout", exchange -> {
            try {
                Thread.sleep(300);
                json(exchange, 200, "{\"late\":true}");
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } catch (IOException disconnected) {
                exchange.close();
            }
        });
        server.createContext("/malformed", exchange -> json(exchange, 200, "{broken"));
        server.start();
        origin = URI.create("http://localhost:" + server.getAddress().getPort());
        client = new RecordingClient(LIMIT, origin);
    }

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
    }

    @Test
    void acceptsValidBoundedJson() {
        assertThat(client.getJson(origin + "/valid").path("ok").asBoolean()).isTrue();
    }

    @Test
    void rejectsInvalidContentTypeAndMalformedJson() {
        assertCategory("/wrong-type", ExternalHttpException.Category.INVALID_CONTENT_TYPE);
        assertCategory("/malformed", ExternalHttpException.Category.MALFORMED_JSON);
    }

    @Test
    void stopsReadingAtTheHardResponseLimit() {
        assertCategory("/oversized", ExternalHttpException.Category.RESPONSE_TOO_LARGE);
    }

    @Test
    void acceptsAResponseExactlyAtTheLimitAndRejectsOneByteMore() {
        assertThat(client.getJson(origin + "/at-limit").path("pad").asText()).isNotEmpty();
        assertCategory("/one-over-limit", ExternalHttpException.Category.RESPONSE_TOO_LARGE);
    }

    @Test
    void rejectsADeclaredContentLengthAboveTheLimitBeforeConsumingTheBody() {
        assertCategory("/declared-oversize", ExternalHttpException.Category.RESPONSE_TOO_LARGE);

        // The declared body was refused from its header: the server never got to stream
        // anywhere near the declared length before the client closed the stream.
        assertThat(declaredOversizeBytesWritten.get()).isLessThan(DECLARED_OVERSIZE / 2);
    }

    @Test
    void boundsAChunkedResponseThatDeclaresNoContentLength() {
        assertCategory("/chunked-oversize", ExternalHttpException.Category.RESPONSE_TOO_LARGE);
        // Reading stopped at the bound instead of draining the whole stream.
        assertThat(chunkedBytesWritten.get()).isLessThan(CHUNKED_TOTAL / 2);
        // A chunked response inside the bound still succeeds.
        assertThat(client.getJson(origin + "/chunked-within-limit").path("ok").asBoolean()).isTrue();
    }

    @Test
    void oversizeCarriesTheConfiguredLimitButNoResponseContent() {
        assertThatThrownBy(() -> client.getJson(origin + "/oversized"))
                .isInstanceOfSatisfying(ExternalHttpException.class, failure -> {
                    assertThat(failure.limitBytes()).isEqualTo(LIMIT);
                    assertThat(failure.statusCode()).isNull();
                    // Only the closed category name; never a body fragment.
                    assertThat(failure.getMessage()).isEqualTo("RESPONSE_TOO_LARGE");
                    assertThat(failure.getMessage()).doesNotContain("x");
                    assertThat(failure.getSuppressed()).isEmpty();
                    assertThat(failure.getCause()).isNull();
                });
    }

    @Test
    void oversizeIsDeterministicAndNeverRetried() {
        assertCategory("/oversized", ExternalHttpException.Category.RESPONSE_TOO_LARGE);

        assertThat(client.pauses).isEmpty();
    }

    @Test
    void transientServerErrorsStillRetryWithBoundedBackoff() {
        assertCategory("/server-error", ExternalHttpException.Category.HTTP_STATUS);

        assertThat(serverErrorAttempts.get()).isEqualTo(3);
        assertThat(client.pauses).containsExactly(400L, 800L);
    }

    @Test
    void anEmptyBodyIsAParseFailureRatherThanAnOversizeFailure() {
        assertCategory("/empty", ExternalHttpException.Category.MALFORMED_JSON);
    }

    @Test
    void rejectsRedirectsOutsideTheOriginalAllowedOrigin() {
        assertCategory("/private-redirect", ExternalHttpException.Category.INVALID_DESTINATION);
    }

    @Test
    void capsRedirectCount() {
        assertCategory("/redirect/0", ExternalHttpException.Category.REDIRECT_LIMIT);
    }

    @Test
    void preservesResponseTimeoutAndRetryIsolation() {
        assertCategory("/timeout", ExternalHttpException.Category.TIMEOUT);
        assertThat(client.pauses).containsExactly(400L, 800L);
    }

    private void assertCategory(String path, ExternalHttpException.Category category) {
        assertThatThrownBy(() -> client.getJson(origin + path))
                .isInstanceOfSatisfying(ExternalHttpException.class,
                        failure -> assertThat(failure.category()).isEqualTo(category));
    }

    /** JSON whose serialized form is exactly {@code total} bytes. */
    private static String padded(int total) {
        String prefix = "{\"pad\":\"";
        String suffix = "\"}";
        return prefix + "x".repeat(total - prefix.length() - suffix.length()) + suffix;
    }

    private void redirect(HttpExchange exchange) throws IOException {
        int current = Integer.parseInt(exchange.getRequestURI().getPath().substring("/redirect/".length()));
        exchange.getResponseHeaders().add("Location", "/redirect/" + (current + 1));
        exchange.sendResponseHeaders(302, -1);
        exchange.close();
    }

    private void json(HttpExchange exchange, int status, String body) throws IOException {
        respond(exchange, status, "application/json; charset=utf-8", body);
    }

    private void respond(HttpExchange exchange, int status, String contentType, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", contentType);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}

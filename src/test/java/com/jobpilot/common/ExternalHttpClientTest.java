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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ExternalHttpClientTest {
    private HttpServer server;
    private URI origin;
    private RecordingClient client;

    private static final class RecordingClient extends ExternalHttpClient {
        private final List<Long> pauses = new ArrayList<>();

        private RecordingClient(JobPilotProperties.Http settings, URI origin) {
            super(new ObjectMapper(), settings, origin);
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
        client = new RecordingClient(new JobPilotProperties.Http(
                Duration.ofMillis(50), Duration.ofMillis(100), 1_024), origin);
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

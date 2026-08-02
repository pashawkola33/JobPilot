package com.jobpilot.browser.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobpilot.browser.api.BrowserExtractionRequest;
import com.jobpilot.browser.api.BrowserExtractionResponse;
import com.jobpilot.browser.api.BrowserExtractionStatus;
import com.jobpilot.browser.config.ScraperWorkerProperties;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class JdkBrowserExtractionClientTest {
    private static final String SECRET = "0123456789abcdef0123456789abcdef";
    private final ObjectMapper mapper = new ObjectMapper();
    private HttpServer server;
    private int port;
    private final AtomicReference<String> receivedSecret = new AtomicReference<>();
    private final AtomicInteger requests = new AtomicInteger();
    private volatile int status = 200;
    private volatile String body = "";

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/extract", exchange -> {
            requests.incrementAndGet();
            receivedSecret.set(exchange.getRequestHeaders().getFirst("x-jobpilot-worker-secret"));
            exchange.getRequestBody().readAllBytes();
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        port = server.getAddress().getPort();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private JdkBrowserExtractionClient client(int maxResponseBytes) {
        ScraperWorkerProperties settings = new ScraperWorkerProperties(true,
                "http://127.0.0.1:" + port, SECRET, Duration.ofSeconds(2), Duration.ofSeconds(5),
                maxResponseBytes, 50_000);
        return new JdkBrowserExtractionClient(settings, mapper);
    }

    private BrowserExtractionRequest request() {
        return new BrowserExtractionRequest("req-1", "https://93.184.216.34/jobs/1", null);
    }

    @Test
    void parsesAnExtractedResponseAndSendsTheSecretHeader() throws Exception {
        body = mapper.writeValueAsString(new BrowserExtractionResponse(
                BrowserExtractionStatus.EXTRACTED, "https://93.184.216.34/jobs/1", "BROWSER",
                new BrowserExtractionResponse.Job("Java Intern", "Example", "Bucharest",
                        "A sufficiently long verified description of the internship role and duties.",
                        "INTERN", "2026-07-19", "https://93.184.216.34/jobs/1"),
                new BrowserExtractionResponse.Evidence("JSON_LD", "JSON_LD", "JSON_LD")));

        BrowserExtractionResponse response = client(1_048_576).extract(request());

        assertThat(response.status()).isEqualTo(BrowserExtractionStatus.EXTRACTED);
        assertThat(response.job().title()).isEqualTo("Java Intern");
        assertThat(receivedSecret.get()).isEqualTo(SECRET);
    }

    @Test
    void rejectsMalformedResponse() {
        body = "{ not json";
        assertThat(client(1_048_576).extract(request()).status())
                .isEqualTo(BrowserExtractionStatus.WORKER_UNAVAILABLE);
    }

    @Test
    void rejectsOversizedResponse() {
        body = "{\"status\":\"EXTRACTED\",\"pad\":\"" + "x".repeat(5000) + "\"}";
        assertThat(client(1024).extract(request()).status())
                .isEqualTo(BrowserExtractionStatus.WORKER_UNAVAILABLE);
    }

    @Test
    void rejectsNonSuccessStatus() {
        status = 503;
        body = "{\"status\":\"EXTRACTED\"}";
        assertThat(client(1_048_576).extract(request()).status())
                .isEqualTo(BrowserExtractionStatus.WORKER_UNAVAILABLE);
    }

    @Test
    void disabledClientMakesNoRequest() {
        JdkBrowserExtractionClient disabled = new JdkBrowserExtractionClient(
                ScraperWorkerProperties.disabled(), mapper);
        assertThat(disabled.extract(request()).status())
                .isEqualTo(BrowserExtractionStatus.WORKER_UNAVAILABLE);
        assertThat(requests.get()).isZero();
    }
}

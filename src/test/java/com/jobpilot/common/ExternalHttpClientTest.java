package com.jobpilot.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobpilot.support.TestProperties;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestClient;

class ExternalHttpClientTest {
    private static final String URL = "https://api.example.com/jobs";

    /** Records requested pauses instead of really sleeping, keeping the tests fast. */
    private static final class RecordingClient extends ExternalHttpClient {
        private final List<Long> pauses = new ArrayList<>();

        private RecordingClient(RestClient client) {
            super(client, new ObjectMapper(), TestProperties.create());
        }

        @Override
        protected void pause(long millis) {
            pauses.add(millis);
        }
    }

    private final MockRestServiceServer server;
    private final RecordingClient client;

    ExternalHttpClientTest() {
        RestClient.Builder builder = RestClient.builder();
        this.server = MockRestServiceServer.bindTo(builder).build();
        this.client = new RecordingClient(builder.build());
    }

    @Test
    void retriesServerErrorsWithBackoffAndSucceeds() {
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));
        server.expect(requestTo(URL)).andRespond(withSuccess("{\"ok\":true}", MediaType.APPLICATION_JSON));

        assertThat(client.getJson(URL).path("ok").asBoolean()).isTrue();
        assertThat(client.pauses).containsExactly(400L);
        server.verify();
    }

    @Test
    void doesNotSleepAfterTheFinalFailedAttempt() {
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

        assertThatThrownBy(() -> client.getJson(URL)).isInstanceOf(HttpServerErrorException.class);
        assertThat(client.pauses).containsExactly(400L, 800L);
        server.verify();
    }

    @Test
    void retriesRateLimitsAndHonoursRetryAfterSeconds() {
        HttpHeaders headers = new HttpHeaders();
        headers.add("Retry-After", "2");
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).headers(headers));
        server.expect(requestTo(URL)).andRespond(withSuccess("{\"ok\":true}", MediaType.APPLICATION_JSON));

        assertThat(client.getJson(URL).path("ok").asBoolean()).isTrue();
        assertThat(client.pauses).containsExactly(2000L);
        server.verify();
    }

    @Test
    void doesNotRetryOtherClientErrors() {
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.BAD_REQUEST));

        assertThatThrownBy(() -> client.getJson(URL)).isInstanceOf(HttpClientErrorException.class);
        assertThat(client.pauses).isEmpty();
        server.verify();
    }
}

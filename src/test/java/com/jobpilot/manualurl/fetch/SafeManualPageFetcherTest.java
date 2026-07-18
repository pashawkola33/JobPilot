package com.jobpilot.manualurl.fetch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jobpilot.common.UrlCanonicalizer;
import com.jobpilot.support.TestProperties;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.List;
import org.junit.jupiter.api.Test;

class SafeManualPageFetcherTest {
    private static final String URL = "https://public.example/job";

    @Test
    void fetchesBoundedTextResource() throws Exception {
        var fixture = new Fixture(response(200, "text/html; charset=UTF-8", null, "<h1>Job</h1>"));

        ManualFetchedResource result = fixture.fetcher.fetch(fixture.initial());

        assertThat(result.finalUri().toString()).isEqualTo(URL);
        assertThat(result.body()).isEqualTo("<h1>Job</h1>");
    }

    @Test
    void rejectsRedirectToPrivateAddress() throws Exception {
        var fixture = new Fixture(response(302, "text/html", "http://10.0.0.2/internal", ""));

        assertCategory(() -> fixture.fetcher.fetch(fixture.initial()),
                ManualFetchException.Category.INVALID_REDIRECT);
    }

    @Test
    void rejectsExcessiveRedirects() throws Exception {
        var fixture = new Fixture(
                response(302, "text/html", "/one", ""),
                response(302, "text/html", "/two", ""),
                response(302, "text/html", "/three", ""),
                response(302, "text/html", "/four", ""));

        assertCategory(() -> fixture.fetcher.fetch(fixture.initial()),
                ManualFetchException.Category.REDIRECT_LIMIT);
    }

    @Test
    void propagatesTimeoutCategory() throws Exception {
        ManualHttpTransport timeout = (uri, responseTimeout, maxBytes) -> {
            throw new ManualFetchException(ManualFetchException.Category.TIMEOUT, "timeout");
        };
        Fixture fixture = new Fixture(timeout);

        assertCategory(() -> fixture.fetcher.fetch(fixture.initial()),
                ManualFetchException.Category.TIMEOUT);
    }

    @Test
    void rejectsOversizedBody() throws Exception {
        byte[] body = new byte[TestProperties.create().manualUrl().maxResponseBytes() + 1];
        var fixture = new Fixture(new ManualHttpResponse(200, "text/html", null, body));

        assertCategory(() -> fixture.fetcher.fetch(fixture.initial()),
                ManualFetchException.Category.RESPONSE_TOO_LARGE);
    }

    @Test
    void rejectsUnsupportedContentType() throws Exception {
        var fixture = new Fixture(response(200, "application/pdf", null, "%PDF"));

        assertCategory(() -> fixture.fetcher.fetch(fixture.initial()),
                ManualFetchException.Category.UNSUPPORTED_CONTENT_TYPE);
    }

    @Test
    void classifiesProtectedHttpStatusWithoutRetrying() throws Exception {
        var fixture = new Fixture(response(403, "text/html", null, "Access denied"));

        assertCategory(() -> fixture.fetcher.fetch(fixture.initial()),
                ManualFetchException.Category.BLOCKED_OR_PROTECTED);
        assertThat(fixture.transport.requests).isOne();
    }

    private ManualHttpResponse response(int status, String contentType, String location, String body) {
        return new ManualHttpResponse(status, contentType, location,
                body.getBytes(StandardCharsets.UTF_8));
    }

    private void assertCategory(org.assertj.core.api.ThrowableAssert.ThrowingCallable action,
                                ManualFetchException.Category category) {
        assertThatThrownBy(action).isInstanceOf(ManualFetchException.class)
                .extracting(error -> ((ManualFetchException) error).getCategory())
                .isEqualTo(category);
    }

    private static final class Fixture {
        private final QueueTransport transport;
        private final ManualUrlPolicy policy;
        private final SafeManualPageFetcher fetcher;

        private Fixture(ManualHttpResponse... responses) throws Exception {
            this(new QueueTransport(responses));
        }

        private Fixture(ManualHttpTransport transport) throws Exception {
            this.transport = transport instanceof QueueTransport queue ? queue : new QueueTransport(transport);
            InetAddress publicAddress = InetAddress.getByName("93.184.216.34");
            HostResolver resolver = host -> host.equals("10.0.0.2")
                    ? List.of(InetAddress.getByName(host)) : List.of(publicAddress);
            this.policy = new ManualUrlPolicy(new UrlCanonicalizer(), resolver);
            this.fetcher = new SafeManualPageFetcher(this.transport, policy, TestProperties.create());
        }

        private ValidatedManualUrl initial() {
            return policy.validate(URL);
        }
    }

    private static final class QueueTransport implements ManualHttpTransport {
        private final ArrayDeque<ManualHttpResponse> responses = new ArrayDeque<>();
        private final ManualHttpTransport delegate;
        private int requests;

        private QueueTransport(ManualHttpResponse... responses) {
            this.responses.addAll(List.of(responses));
            this.delegate = null;
        }

        private QueueTransport(ManualHttpTransport delegate) {
            this.delegate = delegate;
        }

        @Override
        public ManualHttpResponse get(java.net.URI uri, java.time.Duration timeout, int maxBytes) {
            requests++;
            return delegate == null ? responses.removeFirst() : delegate.get(uri, timeout, maxBytes);
        }
    }
}

package com.jobpilot.manualurl.fetch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jobpilot.common.UrlCanonicalizer;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ManualUrlPolicyTest {
    private static final String PUBLIC_IP = "93.184.216.34";

    @Test
    void acceptsValidHttpsPublicUrl() throws Exception {
        ManualUrlPolicy policy = policyReturning(PUBLIC_IP);

        ValidatedManualUrl result = policy.validate("HTTPS://PUBLIC.Example/jobs/42#apply");

        assertThat(result.uri().toString()).isEqualTo("https://public.example/jobs/42");
        assertThat(result.resolvedAddresses()).singleElement()
                .extracting(InetAddress::getHostAddress).isEqualTo(PUBLIC_IP);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "file:///etc/passwd", "ftp://example.com/job", "data:text/plain,job",
            "jar:https://example.com/a.jar!/job", "javascript:alert(1)"
    })
    void rejectsUnsupportedProtocol(String url) {
        assertInvalid(policyReturningUnchecked(PUBLIC_IP), url);
    }

    @Test
    void rejectsEmbeddedCredentials() {
        assertInvalid(policyReturningUnchecked(PUBLIC_IP), "https://user:secret@example.com/job");
    }

    @Test
    void rejectsLocalhost() {
        assertInvalid(policyReturningUnchecked("127.0.0.1"), "http://localhost/job");
    }

    @ParameterizedTest
    @ValueSource(strings = {"127.0.0.1", "10.1.2.3", "169.254.10.20", "169.254.169.254"})
    void rejectsLoopbackPrivateLinkLocalAndMetadataIpv4(String address) {
        assertInvalid(policyReturningUnchecked(address), "https://public.example/job");
    }

    @Test
    void rejectsPrivateIpv6() {
        assertInvalid(policyReturningUnchecked("fd00::1234"), "https://public.example/job");
    }

    @Test
    void rejectsHostnameResolvingToPrivateAddress() {
        assertInvalid(policyReturningUnchecked("192.168.1.20"), "https://public.example/job");
    }

    @Test
    void mapsDnsFailureSeparately() {
        HostResolver resolver = host -> {
            throw new UnknownHostException("not found");
        };

        assertThatThrownBy(() -> new ManualUrlPolicy(new UrlCanonicalizer(), resolver)
                .validate("https://missing.example/job"))
                .isInstanceOf(ManualUrlValidationException.class)
                .extracting(error -> ((ManualUrlValidationException) error).getReason())
                .isEqualTo(ManualUrlValidationException.Reason.RESOLUTION_FAILED);
    }

    private ManualUrlPolicy policyReturning(String address) throws Exception {
        InetAddress resolved = InetAddress.getByName(address);
        return new ManualUrlPolicy(new UrlCanonicalizer(), host -> List.of(resolved));
    }

    private ManualUrlPolicy policyReturningUnchecked(String address) {
        try {
            return policyReturning(address);
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
    }

    private void assertInvalid(ManualUrlPolicy policy, String url) {
        assertThatThrownBy(() -> policy.validate(url))
                .isInstanceOf(ManualUrlValidationException.class);
    }
}

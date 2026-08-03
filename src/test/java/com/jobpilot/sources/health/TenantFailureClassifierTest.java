package com.jobpilot.sources.health;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.JsonParseException;
import com.jobpilot.common.ExternalHttpException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.net.http.HttpTimeoutException;
import javax.net.ssl.SSLHandshakeException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class TenantFailureClassifierTest {
    private final TenantFailureClassifier classifier = new TenantFailureClassifier();

    @ParameterizedTest
    @CsvSource({
            "404, INVALID_TENANT",
            "410, INVALID_TENANT",
            "401, AUTHORIZATION_ERROR",
            "403, AUTHORIZATION_ERROR",
            "429, RATE_LIMITED",
            "400, CLIENT_ERROR",
            "402, CLIENT_ERROR",
            "418, CLIENT_ERROR",
            "422, CLIENT_ERROR",
            "500, SERVER_ERROR",
            "502, SERVER_ERROR",
            "503, SERVER_ERROR",
            "504, SERVER_ERROR"
    })
    void mapsHttpStatusToItsSemanticCategory(int status, TenantFailureCategory expected) {
        TenantFailure failure = classifier.classify("ashby", "cohere", http(status));

        assertThat(failure.category()).isEqualTo(expected);
        assertThat(failure.httpStatus()).isEqualTo(status);
        assertThat(failure.errorMessage()).isEqualTo(expectedMessage(status, expected));
    }

    @Test
    void classifiesTimeoutFromTheTransportCategoryAndFromNestedJavaTimeouts() {
        assertThat(classifier.classify("lever", "veeva",
                new ExternalHttpException(ExternalHttpException.Category.TIMEOUT, null)).category())
                .isEqualTo(TenantFailureCategory.TIMEOUT);
        assertThat(classifier.classify("lever", "veeva",
                new IllegalStateException(new HttpTimeoutException("request timed out"))).category())
                .isEqualTo(TenantFailureCategory.TIMEOUT);
        assertThat(classifier.classify("lever", "veeva",
                new RuntimeException(new SocketTimeoutException("read timed out"))).category())
                .isEqualTo(TenantFailureCategory.TIMEOUT);
    }

    @Test
    void classifiesConnectionFailuresAsNetworkErrors() {
        assertThat(classifier.classify("recruitee", "tether",
                new ExternalHttpException(ExternalHttpException.Category.IO, null)).category())
                .isEqualTo(TenantFailureCategory.NETWORK_ERROR);
        assertThat(classifier.classify("recruitee", "tether",
                new RuntimeException(new ConnectException("Connection refused"))).category())
                .isEqualTo(TenantFailureCategory.NETWORK_ERROR);
        assertThat(classifier.classify("recruitee", "tether",
                new RuntimeException(new UnknownHostException("tether.recruitee.com"))).category())
                .isEqualTo(TenantFailureCategory.NETWORK_ERROR);
        assertThat(classifier.classify("recruitee", "tether",
                new RuntimeException(new SSLHandshakeException("handshake"))).category())
                .isEqualTo(TenantFailureCategory.NETWORK_ERROR);
        assertThat(classifier.classify("recruitee", "tether",
                new ExternalHttpException(ExternalHttpException.Category.REDIRECT_LIMIT, null))
                .category()).isEqualTo(TenantFailureCategory.NETWORK_ERROR);
    }

    @ParameterizedTest
    @ValueSource(strings = {"MALFORMED_JSON", "INVALID_CONTENT_TYPE"})
    void classifiesUndecodableResponsesAsParseErrors(String category) {
        TenantFailure failure = classifier.classify("greenhouse", "gitlab",
                new ExternalHttpException(ExternalHttpException.Category.valueOf(category), null));

        assertThat(failure.category()).isEqualTo(TenantFailureCategory.RESPONSE_PARSE_ERROR);
    }

    @Test
    void classifiesAnOversizedResponseSeparatelyFromAParseError() {
        TenantFailure failure = classifier.classify("greenhouse", "gitlab",
                new ExternalHttpException(ExternalHttpException.Category.RESPONSE_TOO_LARGE, null)
                        .limitBytes(10_485_760));

        assertThat(failure.category()).isEqualTo(TenantFailureCategory.RESPONSE_TOO_LARGE);
        assertThat(failure.category()).isNotEqualTo(TenantFailureCategory.RESPONSE_PARSE_ERROR);
        assertThat(failure.httpStatus()).isNull();
        assertThat(failure.errorMessage()).isEqualTo(
                "Response exceeded the configured 10485760-byte limit for greenhouse tenant gitlab");
    }

    @Test
    void classifiesANestedOversizedResponseThroughTheCauseChain() {
        TenantFailure failure = classifier.classify("lever", "veeva",
                new IllegalStateException("adapter wrapper",
                        new ExternalHttpException(
                                ExternalHttpException.Category.RESPONSE_TOO_LARGE, null)
                                .limitBytes(10_485_760)));

        assertThat(failure.category()).isEqualTo(TenantFailureCategory.RESPONSE_TOO_LARGE);
        assertThat(failure.errorMessage()).contains("10485760-byte limit");
    }

    @Test
    void theOversizeMessageCarriesOnlyTheLimitProviderAndTenant() {
        TenantFailure failure = classifier.classify("ashby", "cohere",
                new ExternalHttpException(ExternalHttpException.Category.RESPONSE_TOO_LARGE, null)
                        .limitBytes(10_485_760));

        assertThat(failure.errorMessage())
                .doesNotContain("http", "://", "?", "Bearer", "token", "cookie", "<")
                .doesNotContain("com.jobpilot.common.ExternalHttpClient.readBounded");
        assertThat(failure.errorMessage())
                .isEqualTo("Response exceeded the configured 10485760-byte limit "
                        + "for ashby tenant cohere");
    }

    @Test
    void anOversizeWithoutAStructuredLimitStillAvoidsMessageParsing() {
        TenantFailure failure = classifier.classify("recruitee", "tether",
                new ExternalHttpException(ExternalHttpException.Category.RESPONSE_TOO_LARGE, null));

        assertThat(failure.category()).isEqualTo(TenantFailureCategory.RESPONSE_TOO_LARGE);
        assertThat(failure.errorMessage())
                .isEqualTo("Response exceeded the configured limit for recruitee tenant tether");
    }

    @Test
    void classifiesNestedJacksonFailuresAsParseErrors() {
        TenantFailure failure = classifier.classify("greenhouse", "gitlab",
                new IllegalStateException(new JsonParseException(null, "unexpected token")));

        assertThat(failure.category()).isEqualTo(TenantFailureCategory.RESPONSE_PARSE_ERROR);
        assertThat(failure.errorMessage())
                .isEqualTo("Could not parse the greenhouse jobs response for tenant gitlab");
    }

    @Test
    void classifiesRejectedDestinationsAsConfigurationErrors() {
        TenantFailure failure = classifier.classify("recruitee", "xebiapoland",
                new ExternalHttpException(ExternalHttpException.Category.INVALID_DESTINATION, null));

        assertThat(failure.category()).isEqualTo(TenantFailureCategory.CONFIGURATION_ERROR);
        assertThat(failure.errorMessage()).contains("check the configured tenant identifier");
    }

    @Test
    void classifiesAnythingElseAsUnknownWithoutThrowing() {
        TenantFailure failure = classifier.classify("ashby", "elevenlabs",
                new IllegalArgumentException("something unexpected"));

        assertThat(failure.category()).isEqualTo(TenantFailureCategory.UNKNOWN_ERROR);
        assertThat(failure.httpStatus()).isNull();
        assertThat(failure.errorType()).isEqualTo(IllegalArgumentException.class.getName());
        assertThat(failure.errorMessage())
                .isEqualTo("Unclassified IllegalArgumentException for ashby tenant elevenlabs");
    }

    @Test
    void classifiesAMissingErrorAndACyclicCauseChainSafely() {
        assertThat(classifier.classify("ashby", "cohere", null).category())
                .isEqualTo(TenantFailureCategory.UNKNOWN_ERROR);

        RuntimeException first = new RuntimeException("first");
        RuntimeException second = new RuntimeException("second", first);
        first.initCause(second);

        assertThat(classifier.classify("ashby", "cohere", first).category())
                .isEqualTo(TenantFailureCategory.UNKNOWN_ERROR);
    }

    @Test
    void prefersTheStructuredCauseOverTheOuterWrapper() {
        TenantFailure failure = classifier.classify("ashby", "cohere",
                new IllegalStateException("adapter wrapper", http(404)));

        assertThat(failure.category()).isEqualTo(TenantFailureCategory.INVALID_TENANT);
        assertThat(failure.httpStatus()).isEqualTo(404);
    }

    @Test
    void reportsAnHttpFailureWithoutAStatusAsUnknown() {
        TenantFailure failure = classifier.classify("lever", "weloglobal",
                new ExternalHttpException(ExternalHttpException.Category.HTTP_STATUS, null));

        assertThat(failure.category()).isEqualTo(TenantFailureCategory.UNKNOWN_ERROR);
        assertThat(failure.httpStatus()).isNull();
    }

    private ExternalHttpException http(int status) {
        return new ExternalHttpException(ExternalHttpException.Category.HTTP_STATUS, status);
    }

    private String expectedMessage(int status, TenantFailureCategory category) {
        String detail = switch (category) {
            case INVALID_TENANT -> "; the board or company does not exist";
            case AUTHORIZATION_ERROR -> "; the request was refused";
            case RATE_LIMITED -> "; the provider is throttling requests";
            case SERVER_ERROR -> "; the provider reported a server error";
            default -> "";
        };
        return "HTTP " + status + " for ashby tenant cohere" + detail;
    }
}

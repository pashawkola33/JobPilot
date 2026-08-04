package com.jobpilot.sources.health;

import com.jobpilot.common.ExternalHttpException;
import com.jobpilot.sources.smartrecruiters.SmartRecruitersLimitException;
import java.io.IOException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.PortUnreachableException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import javax.net.ssl.SSLException;
import org.springframework.stereotype.Component;

/**
 * Turns an arbitrary tenant-fetch throwable into a closed semantic category plus a
 * bounded, redacted operator message.
 *
 * <p>Classification prefers structured metadata: {@link ExternalHttpException} already
 * carries a fixed transport category and, for HTTP failures, the status code. Only when
 * no structured signal exists anywhere in the cause chain does this fall back to
 * {@code UNKNOWN_ERROR}. Message strings are never used to decide a category.
 */
@Component
public class TenantFailureClassifier {
    private static final int MAX_CAUSE_DEPTH = 12;

    /** Classifies a failure for one provider/tenant pair. */
    public TenantFailure classify(String provider, String tenant, Throwable error) {
        String providerLabel = SafeErrorText.token(provider);
        String tenantLabel = SafeErrorText.token(tenant);
        if (error == null) {
            return new TenantFailure(TenantFailureCategory.UNKNOWN_ERROR, null, null,
                    "Tenant fetch failed without an error");
        }
        for (Throwable current : chain(error)) {
            TenantFailure classified = classifyOne(providerLabel, tenantLabel, current);
            if (classified != null) return classified;
        }
        return new TenantFailure(TenantFailureCategory.UNKNOWN_ERROR, null,
                error.getClass().getName(),
                "Unclassified " + error.getClass().getSimpleName() + " for " + providerLabel
                        + " tenant " + tenantLabel);
    }

    /**
     * Names the schema rule that rejected the response when the adapter supplied one. The
     * detail is provider-generic field-path and JSON-type text, so a vague "could not
     * parse" becomes actionable without ever carrying a response value.
     */
    private static String parseMessage(String provider, String tenant, String parseDetail) {
        String base = "Could not parse the " + provider + " jobs response for tenant " + tenant;
        return parseDetail == null || parseDetail.isBlank() ? base : base + "; " + parseDetail;
    }

    private TenantFailure classifyOne(String provider, String tenant, Throwable error) {
        if (error instanceof SmartRecruitersLimitException limit) {
            String kind = limit.limit() == SmartRecruitersLimitException.Limit.LIST_PAGES
                    ? "list-page" : "unique-posting";
            return new TenantFailure(TenantFailureCategory.RESPONSE_TOO_LARGE, null,
                    error.getClass().getName(),
                    "SmartRecruiters " + kind + " cap of " + limit.configuredMaximum()
                            + " was exceeded for " + provider + " tenant " + tenant);
        }
        if (error instanceof com.jobpilot.sources.workday.WorkdayLimitException limit) {
            return new TenantFailure(TenantFailureCategory.RESPONSE_TOO_LARGE, null,
                    error.getClass().getName(),
                    "Workday " + limit.limit().name().toLowerCase(java.util.Locale.ROOT).replace('_', '-')
                            + " cap of " + limit.configuredMaximum() + " was exceeded for "
                            + provider + " tenant " + tenant);
        }
        if (error instanceof ExternalHttpException transport) {
            return fromTransport(provider, tenant, transport);
        }
        // Timeouts must be checked before the generic IOException branch:
        // HttpTimeoutException and SocketTimeoutException both extend IOException.
        if (error instanceof HttpConnectTimeoutException || error instanceof HttpTimeoutException
                || error instanceof SocketTimeoutException || error instanceof TimeoutException) {
            return new TenantFailure(TenantFailureCategory.TIMEOUT, null, typeOf(error),
                    "Request timed out after the configured HTTP timeout for " + provider
                            + " tenant " + tenant);
        }
        if (error instanceof UnknownHostException) {
            return new TenantFailure(TenantFailureCategory.NETWORK_ERROR, null, typeOf(error),
                    "Host could not be resolved for " + provider + " tenant " + tenant);
        }
        if (error instanceof SSLException) {
            return new TenantFailure(TenantFailureCategory.NETWORK_ERROR, null, typeOf(error),
                    "TLS negotiation failed for " + provider + " tenant " + tenant);
        }
        if (error instanceof ConnectException || error instanceof NoRouteToHostException
                || error instanceof PortUnreachableException || error instanceof SocketException) {
            return new TenantFailure(TenantFailureCategory.NETWORK_ERROR, null, typeOf(error),
                    "Connection failed for " + provider + " tenant " + tenant);
        }
        if (error instanceof com.fasterxml.jackson.core.JacksonException) {
            return new TenantFailure(TenantFailureCategory.RESPONSE_PARSE_ERROR, null, typeOf(error),
                    "Could not parse the " + provider + " jobs response for tenant " + tenant);
        }
        if (error instanceof IOException) {
            return new TenantFailure(TenantFailureCategory.NETWORK_ERROR, null, typeOf(error),
                    "Transport failure for " + provider + " tenant " + tenant);
        }
        return null;
    }

    private TenantFailure fromTransport(String provider, String tenant, ExternalHttpException error) {
        Integer status = error.statusCode();
        return switch (error.category()) {
            case HTTP_STATUS -> fromHttpStatus(provider, tenant, status);
            case TIMEOUT -> new TenantFailure(TenantFailureCategory.TIMEOUT, status, typeOf(error),
                    "Request timed out after the configured HTTP timeout for " + provider
                            + " tenant " + tenant);
            case IO -> new TenantFailure(TenantFailureCategory.NETWORK_ERROR, status, typeOf(error),
                    "Transport failure for " + provider + " tenant " + tenant);
            case REDIRECT_LIMIT -> new TenantFailure(TenantFailureCategory.NETWORK_ERROR, status,
                    typeOf(error), "Too many redirects for " + provider + " tenant " + tenant);
            case MALFORMED_JSON -> new TenantFailure(TenantFailureCategory.RESPONSE_PARSE_ERROR,
                    status, typeOf(error), parseMessage(provider, tenant, error.parseDetail()));
            case INVALID_CONTENT_TYPE -> new TenantFailure(TenantFailureCategory.RESPONSE_PARSE_ERROR,
                    status, typeOf(error),
                    "Unexpected content type from " + provider + " for tenant " + tenant);
            case RESPONSE_TOO_LARGE -> new TenantFailure(TenantFailureCategory.RESPONSE_TOO_LARGE,
                    status, typeOf(error), oversizedMessage(provider, tenant, error.limitBytes()));
            case INVALID_DESTINATION -> new TenantFailure(TenantFailureCategory.CONFIGURATION_ERROR,
                    status, typeOf(error),
                    "Rejected destination for " + provider + " tenant " + tenant
                            + "; check the configured tenant identifier");
        };
    }

    private TenantFailure fromHttpStatus(String provider, String tenant, Integer status) {
        if (status == null) {
            return new TenantFailure(TenantFailureCategory.UNKNOWN_ERROR, null,
                    ExternalHttpException.class.getName(),
                    "HTTP failure without a status for " + provider + " tenant " + tenant);
        }
        TenantFailureCategory category = categoryFor(status);
        String detail = switch (category) {
            case INVALID_TENANT -> "; the board or company does not exist";
            case AUTHORIZATION_ERROR -> "; the request was refused";
            case RATE_LIMITED -> "; the provider is throttling requests";
            case SERVER_ERROR -> "; the provider reported a server error";
            default -> "";
        };
        return new TenantFailure(category, status, ExternalHttpException.class.getName(),
                "HTTP " + status + " for " + provider + " tenant " + tenant + detail);
    }

    /**
     * Closed oversize sentence. It carries only the configured limit plus the already
     * normalized provider and tenant — never a URL, header, or any measured or partial
     * body. The limit comes from a structured field, never from a parsed message.
     */
    private String oversizedMessage(String provider, String tenant, Integer limitBytes) {
        String bound = limitBytes == null ? "" : limitBytes + "-byte ";
        return "Response exceeded the configured " + bound + "limit for " + provider
                + " tenant " + tenant;
    }

    private TenantFailureCategory categoryFor(int status) {
        if (status == 404 || status == 410) return TenantFailureCategory.INVALID_TENANT;
        if (status == 401 || status == 403) return TenantFailureCategory.AUTHORIZATION_ERROR;
        if (status == 429) return TenantFailureCategory.RATE_LIMITED;
        if (status >= 500 && status <= 599) return TenantFailureCategory.SERVER_ERROR;
        if (status >= 400 && status <= 499) return TenantFailureCategory.CLIENT_ERROR;
        return TenantFailureCategory.UNKNOWN_ERROR;
    }

    private String typeOf(Throwable error) {
        return error.getClass().getName();
    }

    /** Bounded, cycle-safe walk from the thrown error down through its causes. */
    private Iterable<Throwable> chain(Throwable root) {
        Map<Throwable, Boolean> seen = new IdentityHashMap<>();
        java.util.List<Throwable> chain = new java.util.ArrayList<>();
        Throwable current = root;
        while (current != null && chain.size() < MAX_CAUSE_DEPTH && seen.put(current, Boolean.TRUE) == null) {
            chain.add(current);
            current = current.getCause();
        }
        return chain;
    }
}

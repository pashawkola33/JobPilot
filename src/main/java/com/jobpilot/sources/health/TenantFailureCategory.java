package com.jobpilot.sources.health;

/**
 * Semantic reason a tenant fetch failed. Deliberately coarse and closed so it can
 * be persisted, indexed, and shown to an operator without leaking request detail.
 */
public enum TenantFailureCategory {
    /** No failure: the attempt succeeded. */
    NONE,
    /** The configured board/company does not exist at the provider (404/410). */
    INVALID_TENANT,
    /** Any other 4xx that is not authorization, rate limiting, or a missing tenant. */
    CLIENT_ERROR,
    /** The provider refused the request (401/403). */
    AUTHORIZATION_ERROR,
    /** The provider throttled the request (429). */
    RATE_LIMITED,
    /** The configured request timeout or a Java HTTP timeout elapsed. */
    TIMEOUT,
    /** Connect, DNS, socket, TLS, or other transport failure. */
    NETWORK_ERROR,
    /** The provider returned 5xx. */
    SERVER_ERROR,
    /** A valid HTTP response that could not be decoded or mapped. */
    RESPONSE_PARSE_ERROR,
    /**
     * The response exceeded the configured {@code jobpilot.http.max-response-bytes}
     * bound. The board is reachable and answering; only our own limit was breached, so
     * this is deliberately distinct from {@link #RESPONSE_PARSE_ERROR}.
     */
    RESPONSE_TOO_LARGE,
    /** Missing or structurally invalid tenant/destination configuration. */
    CONFIGURATION_ERROR,
    /** Anything that cannot be classified safely. */
    UNKNOWN_ERROR
}

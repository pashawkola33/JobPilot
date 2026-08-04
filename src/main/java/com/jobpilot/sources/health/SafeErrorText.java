package com.jobpilot.sources.health;

import com.jobpilot.common.Utf16;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Bounds and sanitises operator-facing failure text.
 *
 * <p>Nothing produced here may contain an authorization header, API key, bot token,
 * cookie, query string, response body, HTML fragment, stack frame, or environment
 * value. Callers build a short actionable sentence; this class is the last guard.
 */
public final class SafeErrorText {
    public static final int MAX_TYPE_LENGTH = 120;
    public static final int MAX_MESSAGE_LENGTH = 500;
    /** Matches the source_tenant_health and source_tenant_fetch_logs tenant column. */
    public static final int MAX_TOKEN_LENGTH = 100;

    /** One safe segment: must start alphanumeric, so "..", ".hidden" and "-lead" are out. */
    private static final String TOKEN_SEGMENT = "[A-Za-z0-9][A-Za-z0-9._-]{0,62}";
    /** Exactly two segments joined by a single separator; anchored by {@code matches()}. */
    private static final Pattern STRUCTURED_TENANT_KEY =
            Pattern.compile(TOKEN_SEGMENT + "/" + TOKEN_SEGMENT);

    /** Any absolute URL: replaced by scheme://host plus a redaction marker for the rest. */
    private static final Pattern URL = Pattern.compile("(?i)\\b(https?)://([^\\s/?#\"']{1,253})(\\S*)");
    /** A bare `key=value` pair outside a URL, e.g. leaked from a formatted message. */
    private static final Pattern BARE_PARAMETER = Pattern.compile(
            "(?i)\\b(token|secret|key|apikey|api_key|password|passwd|pwd|authorization|auth|"
                    + "cookie|session|sig|signature|access_token|refresh_token|bearer)"
                    + "\\s*[:=]\\s*\\S+");
    private static final Pattern CONTROL = Pattern.compile("[\\p{Cntrl}\\p{Cf}]+");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    /** Markup would only ever come from a response body, which must never be stored. */
    private static final Pattern MARKUP = Pattern.compile("<[^>]{0,200}>");

    private SafeErrorText() {
    }

    /** Sanitises and bounds a human-readable failure sentence. Never returns blank. */
    public static String message(String raw) {
        if (raw == null || raw.isBlank()) return "Unspecified failure";
        String cleaned = MARKUP.matcher(raw).replaceAll(" ");
        cleaned = BARE_PARAMETER.matcher(cleaned).replaceAll("$1=[redacted]");
        cleaned = URL.matcher(cleaned).replaceAll(matcher -> {
            String host = matcher.group(2).toLowerCase(Locale.ROOT);
            int at = host.lastIndexOf('@'); // Drop any userinfo component.
            if (at >= 0) host = host.substring(at + 1);
            int colon = host.indexOf(':');
            if (colon >= 0) host = host.substring(0, colon);
            return java.util.regex.Matcher.quoteReplacement(
                    matcher.group(1).toLowerCase(Locale.ROOT) + "://" + host);
        });
        cleaned = CONTROL.matcher(cleaned).replaceAll(" ");
        cleaned = WHITESPACE.matcher(cleaned).replaceAll(" ").strip();
        if (cleaned.isEmpty()) return "Unspecified failure";
        return Utf16.truncate(cleaned, MAX_MESSAGE_LENGTH);
    }

    /** Sanitises and bounds a short exception-type token. */
    public static String type(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String cleaned = CONTROL.matcher(raw).replaceAll("");
        cleaned = cleaned.replaceAll("[^A-Za-z0-9._$-]", "");
        if (cleaned.isEmpty()) return null;
        return Utf16.truncate(cleaned, MAX_TYPE_LENGTH);
    }

    /**
     * Bounds a tenant or provider token for log, message, and health-key interpolation.
     *
     * <p>A <em>structured</em> tenant key — exactly two safe segments joined by one
     * {@code /}, as produced by providers whose tenant is a pair such as
     * {@code db/DBWebsite} — is recognised and returned unchanged, so the stored health key
     * matches {@code jobs.provider_tenant} exactly. Everything else is stripped to the
     * original conservative charset, which still removes control characters, newlines,
     * whitespace, URL punctuation, percent-escapes, and any extra separator.
     *
     * <p>This is a validation step, not a relaxation: the separator survives only inside a
     * fully matched, length-bounded key. {@code ../t}, {@code a//b}, {@code a/b/c},
     * {@code a%2Fb}, and any URL-shaped text all fail the match and fall through to the
     * unchanged sanitiser.
     */
    public static String token(String raw) {
        if (raw == null || raw.isBlank()) return "unknown";
        if (raw.length() <= MAX_TOKEN_LENGTH && STRUCTURED_TENANT_KEY.matcher(raw).matches()) {
            return raw;
        }
        String cleaned = raw.replaceAll("[^A-Za-z0-9._-]", "");
        if (cleaned.isEmpty()) return "unknown";
        return Utf16.truncate(cleaned, MAX_TOKEN_LENGTH);
    }
}

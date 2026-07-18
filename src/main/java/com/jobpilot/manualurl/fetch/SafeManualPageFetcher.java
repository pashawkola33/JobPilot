package com.jobpilot.manualurl.fetch;

import com.jobpilot.config.JobPilotProperties;
import java.net.URI;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class SafeManualPageFetcher {
    private static final Set<String> ALLOWED_CONTENT_TYPES = Set.of(
            "text/html", "application/xhtml+xml", "application/ld+json",
            "application/json", "text/plain");
    private static final Pattern CHARSET = Pattern.compile("charset=([^;\\s]+)", Pattern.CASE_INSENSITIVE);

    private final ManualHttpTransport transport;
    private final ManualUrlPolicy urlPolicy;
    private final JobPilotProperties.ManualUrl settings;

    public SafeManualPageFetcher(ManualHttpTransport transport, ManualUrlPolicy urlPolicy,
                                 JobPilotProperties properties) {
        this.transport = transport;
        this.urlPolicy = urlPolicy;
        this.settings = properties.manualUrl();
    }

    public ManualFetchedResource fetch(ValidatedManualUrl initial) {
        URI requested = initial.uri();
        ValidatedManualUrl current = initial;
        Set<URI> visited = new HashSet<>();
        int redirects = 0;
        while (visited.add(current.uri())) {
            ManualHttpResponse response = transport.get(current.uri(), settings.responseTimeout(),
                    settings.maxResponseBytes());
            if (isRedirect(response.statusCode())) {
                if (redirects++ >= settings.maxRedirects()) {
                    throw new ManualFetchException(ManualFetchException.Category.REDIRECT_LIMIT,
                            "Remote response exceeded the redirect limit");
                }
                current = validateRedirect(current.uri(), response.location());
                continue;
            }
            if (response.statusCode() == 401 || response.statusCode() == 403
                    || response.statusCode() == 407) {
                throw new ManualFetchException(ManualFetchException.Category.BLOCKED_OR_PROTECTED,
                        "Remote page requires protected access");
            }
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new ManualFetchException(ManualFetchException.Category.HTTP_FAILURE,
                        "Remote server returned an unsuccessful status");
            }
            String contentType = baseContentType(response.contentType());
            if (!ALLOWED_CONTENT_TYPES.contains(contentType)) {
                throw new ManualFetchException(ManualFetchException.Category.UNSUPPORTED_CONTENT_TYPE,
                        "Remote response content type is unsupported");
            }
            byte[] bytes = response.body();
            if (bytes.length == 0 || bytes.length > settings.maxResponseBytes()) {
                throw new ManualFetchException(ManualFetchException.Category.RESPONSE_TOO_LARGE,
                        "Remote response is empty or exceeded the configured size limit");
            }
            return new ManualFetchedResource(requested, current.uri(), contentType,
                    new String(bytes, charset(response.contentType())));
        }
        throw new ManualFetchException(ManualFetchException.Category.REDIRECT_LIMIT,
                "Remote response contained a redirect loop");
    }

    private ValidatedManualUrl validateRedirect(URI current, String location) {
        if (location == null || location.isBlank()) {
            throw new ManualFetchException(ManualFetchException.Category.INVALID_REDIRECT,
                    "Remote response contained an invalid redirect");
        }
        try {
            return urlPolicy.validate(current.resolve(location).toString());
        } catch (ManualUrlValidationException exception) {
            throw new ManualFetchException(ManualFetchException.Category.INVALID_REDIRECT,
                    "Remote response redirected to a prohibited destination", exception);
        } catch (IllegalArgumentException exception) {
            throw new ManualFetchException(ManualFetchException.Category.INVALID_REDIRECT,
                    "Remote response contained an invalid redirect", exception);
        }
    }

    private boolean isRedirect(int statusCode) {
        return statusCode == 301 || statusCode == 302 || statusCode == 303
                || statusCode == 307 || statusCode == 308;
    }

    private String baseContentType(String contentType) {
        if (contentType == null) {
            return "";
        }
        return contentType.split(";", 2)[0].strip().toLowerCase(Locale.ROOT);
    }

    private Charset charset(String contentType) {
        if (contentType != null) {
            Matcher matcher = CHARSET.matcher(contentType);
            if (matcher.find()) {
                try {
                    return Charset.forName(matcher.group(1).replace("\"", ""));
                } catch (RuntimeException ignored) {
                    // UTF-8 is the deterministic fallback for an invalid remote charset declaration.
                }
            }
        }
        return StandardCharsets.UTF_8;
    }
}

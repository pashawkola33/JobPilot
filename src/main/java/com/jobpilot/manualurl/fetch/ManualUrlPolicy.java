package com.jobpilot.manualurl.fetch;

import com.jobpilot.common.UrlCanonicalizer;
import com.jobpilot.common.net.ProhibitedAddressClassifier;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class ManualUrlPolicy {
    private static final Set<String> PROHIBITED_HOSTS = Set.of(
            "localhost", "metadata.google.internal", "metadata.goog",
            "instance-data", "instance-data.ec2.internal");

    private final UrlCanonicalizer canonicalizer;
    private final HostResolver resolver;

    public ManualUrlPolicy(UrlCanonicalizer canonicalizer, HostResolver resolver) {
        this.canonicalizer = canonicalizer;
        this.resolver = resolver;
    }

    public ValidatedManualUrl validate(String rawUrl) {
        URI canonical;
        try {
            canonical = canonicalizer.canonicalize(rawUrl);
        } catch (IllegalArgumentException exception) {
            throw new ManualUrlValidationException(
                    ManualUrlValidationException.Reason.INVALID, "URL is invalid", exception);
        }
        String host = hostWithoutBrackets(canonical.getHost());
        if (host == null || isProhibitedHost(host)) {
            throw prohibited();
        }
        List<InetAddress> addresses;
        try {
            addresses = resolver.resolve(host);
        } catch (UnknownHostException exception) {
            throw new ManualUrlValidationException(
                    ManualUrlValidationException.Reason.RESOLUTION_FAILED,
                    "Destination could not be resolved", exception);
        }
        if (addresses.isEmpty() || addresses.stream()
                .anyMatch(ProhibitedAddressClassifier::isProhibited)) {
            throw prohibited();
        }
        return new ValidatedManualUrl(canonical, addresses);
    }

    private boolean isProhibitedHost(String host) {
        String normalized = host.toLowerCase(Locale.ROOT);
        return PROHIBITED_HOSTS.contains(normalized) || normalized.endsWith(".localhost")
                || normalized.endsWith(".local") || normalized.endsWith(".internal");
    }

    private String hostWithoutBrackets(String host) {
        if (host == null) {
            return null;
        }
        return host.startsWith("[") && host.endsWith("]")
                ? host.substring(1, host.length() - 1) : host;
    }

    private ManualUrlValidationException prohibited() {
        return new ManualUrlValidationException(
                ManualUrlValidationException.Reason.PROHIBITED_DESTINATION,
                "Destination is not permitted");
    }
}

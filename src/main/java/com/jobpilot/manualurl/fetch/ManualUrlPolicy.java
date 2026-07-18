package com.jobpilot.manualurl.fetch;

import com.jobpilot.common.UrlCanonicalizer;
import java.net.Inet4Address;
import java.net.Inet6Address;
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
        if (addresses.isEmpty() || addresses.stream().anyMatch(this::isProhibitedAddress)) {
            throw prohibited();
        }
        return new ValidatedManualUrl(canonical, addresses);
    }

    private boolean isProhibitedHost(String host) {
        String normalized = host.toLowerCase(Locale.ROOT);
        return PROHIBITED_HOSTS.contains(normalized) || normalized.endsWith(".localhost")
                || normalized.endsWith(".local") || normalized.endsWith(".internal");
    }

    private boolean isProhibitedAddress(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) {
            return true;
        }
        byte[] bytes = address.getAddress();
        if (address instanceof Inet4Address) {
            int first = unsigned(bytes[0]);
            int second = unsigned(bytes[1]);
            return first == 0 || first == 10 || first == 127
                    || first == 100 && second >= 64 && second <= 127
                    || first == 169 && second == 254
                    || first == 172 && second >= 16 && second <= 31
                    || first == 192 && (second == 0 || second == 168)
                    || first == 198 && (second == 18 || second == 19 || second == 51)
                    || first == 203 && second == 0 && unsigned(bytes[2]) == 113
                    || first >= 224;
        }
        if (address instanceof Inet6Address) {
            int first = unsigned(bytes[0]);
            int second = unsigned(bytes[1]);
            boolean uniqueLocal = (first & 0xfe) == 0xfc;
            boolean documentation = first == 0x20 && second == 0x01
                    && unsigned(bytes[2]) == 0x0d && unsigned(bytes[3]) == 0xb8;
            return uniqueLocal || documentation;
        }
        return true;
    }

    private int unsigned(byte value) {
        return Byte.toUnsignedInt(value);
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

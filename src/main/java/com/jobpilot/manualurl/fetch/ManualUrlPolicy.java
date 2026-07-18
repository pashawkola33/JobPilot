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
            return isProhibitedIpv4(bytes);
        }
        if (address instanceof Inet6Address) {
            int first = unsigned(bytes[0]);
            int second = unsigned(bytes[1]);
            boolean uniqueLocal = (first & 0xfe) == 0xfc;
            boolean documentation = first == 0x20 && second == 0x01
                    && unsigned(bytes[2]) == 0x0d && unsigned(bytes[3]) == 0xb8;
            boolean benchmarking = hasPrefix(bytes, 0x20, 0x01, 0x00, 0x02, 0x00, 0x00);
            if (uniqueLocal || documentation || benchmarking) {
                return true;
            }
            if (hasPrefix(bytes, 0x20, 0x02)) { // 6to4: embedded IPv4 follows 2002::/16.
                return embeddedIpv4Prohibited(bytes, 2, false);
            }
            if (hasPrefix(bytes, 0x20, 0x01, 0x00, 0x00)) { // Teredo 2001:0000::/32.
                return embeddedIpv4Prohibited(bytes, 4, false)
                        || embeddedIpv4Prohibited(bytes, 12, true);
            }
            if (hasPrefix(bytes, 0x00, 0x64, 0xff, 0x9b, 0x00, 0x00,
                    0x00, 0x00, 0x00, 0x00, 0x00, 0x00)) { // NAT64 well-known prefix.
                return embeddedIpv4Prohibited(bytes, 12, false);
            }
            if (hasPrefix(bytes, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
                    0x00, 0x00, 0x00, 0x00, 0xff, 0xff)) { // IPv4-mapped IPv6.
                return embeddedIpv4Prohibited(bytes, 12, false);
            }
            if (hasPrefix(bytes, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
                    0x00, 0x00, 0x00, 0x00, 0x00, 0x00)) { // IPv4-compatible IPv6.
                return embeddedIpv4Prohibited(bytes, 12, false);
            }
            return false;
        }
        return true;
    }

    private boolean isProhibitedIpv4(byte[] bytes) {
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

    private boolean embeddedIpv4Prohibited(byte[] ipv6, int offset, boolean inverted) {
        byte[] ipv4 = new byte[4];
        for (int index = 0; index < ipv4.length; index++) {
            ipv4[index] = inverted ? (byte) ~ipv6[offset + index] : ipv6[offset + index];
        }
        return isProhibitedIpv4(ipv4);
    }

    private boolean hasPrefix(byte[] address, int... prefix) {
        for (int index = 0; index < prefix.length; index++) {
            if (unsigned(address[index]) != prefix[index]) {
                return false;
            }
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

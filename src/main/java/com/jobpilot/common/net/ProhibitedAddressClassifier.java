package com.jobpilot.common.net;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;

/** Shared fail-closed classification for destinations that must never receive outbound secrets. */
public final class ProhibitedAddressClassifier {
    private ProhibitedAddressClassifier() {
    }

    public static boolean isProhibited(InetAddress address) {
        if (address == null || address.isAnyLocalAddress() || address.isLoopbackAddress()
                || address.isLinkLocalAddress() || address.isSiteLocalAddress()
                || address.isMulticastAddress()) {
            return true;
        }
        byte[] bytes = address.getAddress();
        if (address instanceof Inet4Address) return isProhibitedIpv4(bytes);
        if (address instanceof Inet6Address) return isProhibitedIpv6(bytes);
        return true;
    }

    private static boolean isProhibitedIpv6(byte[] bytes) {
        int first = unsigned(bytes[0]);
        int second = unsigned(bytes[1]);
        boolean uniqueLocal = (first & 0xfe) == 0xfc;
        boolean documentation = hasPrefix(bytes, 0x20, 0x01, 0x0d, 0xb8);
        boolean benchmarking = hasPrefix(bytes, 0x20, 0x01, 0x00, 0x02, 0x00, 0x00);
        if (uniqueLocal || documentation || benchmarking) return true;
        if (hasPrefix(bytes, 0x20, 0x02)) { // 6to4 embeds IPv4 after 2002::/16.
            return embeddedIpv4Prohibited(bytes, 2, false);
        }
        if (hasPrefix(bytes, 0x20, 0x01, 0x00, 0x00)) { // Teredo embeds two IPv4 values.
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
        // Currently allocated public global-unicast IPv6 space is 2000::/3.
        return first < 0x20 || first > 0x3f;
    }

    private static boolean isProhibitedIpv4(byte[] bytes) {
        int first = unsigned(bytes[0]);
        int second = unsigned(bytes[1]);
        int third = unsigned(bytes[2]);
        return first == 0 || first == 10 || first == 127
                || first == 100 && second >= 64 && second <= 127
                || first == 169 && second == 254
                || first == 172 && second >= 16 && second <= 31
                || first == 192 && second == 0
                || first == 192 && second == 168
                || first == 198 && (second == 18 || second == 19)
                || first == 198 && second == 51 && third == 100
                || first == 203 && second == 0 && third == 113
                || first >= 224;
    }

    private static boolean embeddedIpv4Prohibited(byte[] ipv6, int offset, boolean inverted) {
        byte[] ipv4 = new byte[4];
        for (int index = 0; index < ipv4.length; index++) {
            ipv4[index] = inverted ? (byte) ~ipv6[offset + index] : ipv6[offset + index];
        }
        return isProhibitedIpv4(ipv4);
    }

    private static boolean hasPrefix(byte[] address, int... prefix) {
        for (int index = 0; index < prefix.length; index++) {
            if (unsigned(address[index]) != prefix[index]) return false;
        }
        return true;
    }

    private static int unsigned(byte value) {
        return Byte.toUnsignedInt(value);
    }
}

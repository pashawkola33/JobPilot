/**
 * Fail-closed classification of destinations that must never receive an
 * outbound browser navigation or fetch. Mirrors the Java
 * ProhibitedAddressClassifier so the worker is an independent SSRF boundary.
 *
 * Given a literal IP address string, returns true when the address is not a
 * safe public global-unicast destination.
 */
import { isIP } from "node:net";

export function isProhibitedIp(address: string): boolean {
  const family = isIP(address);
  if (family === 4) return isProhibitedIpv4(octets(address));
  if (family === 6) return isProhibitedIpv6(ipv6Bytes(address));
  // Not a literal IP: caller must resolve DNS first.
  return true;
}

function octets(address: string): number[] {
  return address.split(".").map((part) => Number.parseInt(part, 10) & 0xff);
}

function isProhibitedIpv4(b: number[]): boolean {
  const [a = 0, second = 0, third = 0] = b;
  return (
    a === 0 || // "this" network / unspecified
    a === 10 ||
    a === 127 || // loopback
    (a === 100 && second >= 64 && second <= 127) || // carrier-grade NAT
    (a === 169 && second === 254) || // link-local
    (a === 172 && second >= 16 && second <= 31) ||
    (a === 192 && second === 0) || // 192.0.0.0/24 (incl. protocol assignment)
    (a === 192 && second === 168) ||
    (a === 198 && (second === 18 || second === 19)) || // benchmarking
    (a === 198 && second === 51 && third === 100) || // TEST-NET-2
    (a === 203 && second === 0 && third === 113) || // TEST-NET-3
    a >= 224 // multicast + reserved + broadcast
  );
}

function isProhibitedIpv6(bytes: number[] | null): boolean {
  if (bytes === null || bytes.length !== 16) return true;
  const b = bytes;
  // Unspecified :: and loopback ::1
  if (b.slice(0, 15).every((x) => x === 0)) return true;
  const first = b[0] ?? 0;
  const second = b[1] ?? 0;
  if ((first & 0xfe) === 0xfc) return true; // unique-local fc00::/7
  if (first === 0xfe && (second & 0xc0) === 0x80) return true; // link-local fe80::/10
  if (first === 0xff) return true; // multicast ff00::/8
  if (hasPrefix(b, [0x20, 0x01, 0x0d, 0xb8])) return true; // documentation 2001:db8::/32
  if (hasPrefix(b, [0x20, 0x01, 0x00, 0x02, 0x00, 0x00])) return true; // benchmarking 2001:2::/48
  if (hasPrefix(b, [0x20, 0x02])) return embeddedIpv4Prohibited(b, 2, false); // 6to4 2002::/16
  if (hasPrefix(b, [0x20, 0x01, 0x00, 0x00])) {
    // Teredo 2001:0000::/32 — server (offset 4) and inverted client (offset 12).
    return embeddedIpv4Prohibited(b, 4, false) || embeddedIpv4Prohibited(b, 12, true);
  }
  if (hasPrefix(b, [0x00, 0x64, 0xff, 0x9b, 0, 0, 0, 0, 0, 0, 0, 0])) {
    return embeddedIpv4Prohibited(b, 12, false); // NAT64 64:ff9b::/96
  }
  if (hasPrefix(b, [0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0xff, 0xff])) {
    return embeddedIpv4Prohibited(b, 12, false); // IPv4-mapped ::ffff:0:0/96
  }
  if (hasPrefix(b, [0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0])) {
    return embeddedIpv4Prohibited(b, 12, false); // IPv4-compatible ::/96
  }
  // Only currently allocated global-unicast space (2000::/3) may pass.
  return first < 0x20 || first > 0x3f;
}

function embeddedIpv4Prohibited(ipv6: number[], offset: number, inverted: boolean): boolean {
  const ipv4 = [0, 1, 2, 3].map((i) => {
    const value = ipv6[offset + i] ?? 0;
    return (inverted ? ~value : value) & 0xff;
  });
  return isProhibitedIpv4(ipv4);
}

function hasPrefix(bytes: number[], prefix: number[]): boolean {
  return prefix.every((value, index) => bytes[index] === value);
}

/** Expand any valid IPv6 literal (including embedded IPv4) into 16 bytes. */
export function ipv6Bytes(address: string): number[] | null {
  let value = address.trim();
  if (value.startsWith("[") && value.endsWith("]")) value = value.slice(1, -1);
  const zone = value.indexOf("%");
  if (zone >= 0) value = value.slice(0, zone);

  // A trailing embedded IPv4 (::ffff:127.0.0.1) becomes two hextets.
  const lastColon = value.lastIndexOf(":");
  const tail = lastColon >= 0 ? value.slice(lastColon + 1) : value;
  if (tail.includes(".")) {
    if (isIP(tail) !== 4) return null;
    const v4 = octets(tail);
    const high = ((v4[0] ?? 0) << 8) | (v4[1] ?? 0);
    const low = ((v4[2] ?? 0) << 8) | (v4[3] ?? 0);
    value = `${value.slice(0, lastColon + 1)}${hex(high)}:${hex(low)}`;
  }

  const doubleColon = value.indexOf("::");
  let groups: string[];
  if (doubleColon >= 0) {
    const head = value.slice(0, doubleColon).split(":").filter((p) => p.length > 0);
    const rest = value.slice(doubleColon + 2).split(":").filter((p) => p.length > 0);
    const missing = 8 - head.length - rest.length;
    if (missing < 0) return null;
    groups = [...head, ...Array(missing).fill("0"), ...rest];
  } else {
    groups = value.split(":");
  }
  if (groups.length !== 8) return null;

  const bytes: number[] = [];
  for (const group of groups) {
    if (!/^[0-9a-fA-F]{1,4}$/.test(group)) return null;
    const word = Number.parseInt(group, 16);
    bytes.push((word >> 8) & 0xff, word & 0xff);
  }
  return bytes;
}

function hex(value: number): string {
  return value.toString(16);
}

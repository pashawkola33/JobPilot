import { strict as assert } from "node:assert";
import { test } from "node:test";
import { isProhibitedIp } from "../src/addressClassifier.js";

test("blocks IPv4 loopback, private, CGNAT, link-local, metadata, TEST-NET, multicast", () => {
  for (const ip of [
    "127.0.0.1",
    "10.0.0.1",
    "10.255.255.255",
    "172.16.0.1",
    "172.31.255.255",
    "192.168.1.20",
    "100.64.0.1",
    "169.254.169.254",
    "0.0.0.0",
    "198.18.0.1",
    "198.51.100.7",
    "203.0.113.9",
    "224.0.0.1",
    "255.255.255.255",
  ]) {
    assert.equal(isProhibitedIp(ip), true, ip);
  }
});

test("allows genuine public IPv4", () => {
  for (const ip of ["93.184.216.34", "8.8.8.8", "1.1.1.1", "172.15.0.1", "172.32.0.1"]) {
    assert.equal(isProhibitedIp(ip), false, ip);
  }
});

test("blocks IPv6 loopback, ULA, link-local, multicast, documentation, benchmarking", () => {
  for (const ip of ["::1", "::", "fd00::1", "fe80::1", "ff02::1", "2001:db8::1", "2001:2::1"]) {
    assert.equal(isProhibitedIp(ip), true, ip);
  }
});

test("blocks IPv4 embedded in IPv6 (mapped, 6to4, NAT64, Teredo)", () => {
  for (const ip of [
    "::ffff:127.0.0.1",
    "::ffff:10.0.0.1",
    "2002:7f00:1::", // 6to4 -> 127.0.0.1
    "2002:a00:1::", // 6to4 -> 10.0.0.1
    "64:ff9b::7f00:1", // NAT64 -> 127.0.0.1
    "2001:0:0:0:0:0:7f00:1", // Teredo server 127.0.0.1
  ]) {
    assert.equal(isProhibitedIp(ip), true, ip);
  }
});

test("allows normal public IPv6 and NAT64 wrapping a public IPv4", () => {
  assert.equal(isProhibitedIp("2606:4700:4700::1111"), false);
  assert.equal(isProhibitedIp("2a00:1450:4001::1"), false);
  assert.equal(isProhibitedIp("64:ff9b::5db8:d822"), false); // NAT64 -> 93.184.216.34
});

test("rejects non-literal input (must be DNS-resolved first)", () => {
  assert.equal(isProhibitedIp("example.com"), true);
  assert.equal(isProhibitedIp("not-an-ip"), true);
});

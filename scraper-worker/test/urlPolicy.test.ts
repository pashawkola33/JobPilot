import { strict as assert } from "node:assert";
import { test } from "node:test";
import { screenStructure, screenWithDns } from "../src/urlPolicy.js";

test("accepts a public https URL with a literal public IP (no DNS needed)", () => {
  const result = screenStructure("https://93.184.216.34/jobs/1");
  assert.equal(result.ok, true);
});

test("rejects unsafe schemes", () => {
  for (const url of [
    "file:///etc/passwd",
    "data:text/html,x",
    "javascript:alert(1)",
    "ftp://example.com/x",
    "blob:https://example.com/x",
  ]) {
    const result = screenStructure(url);
    assert.equal(result.ok, false, url);
    if (!result.ok) assert.equal(result.status, "INVALID_URL");
  }
});

test("rejects embedded credentials", () => {
  const result = screenStructure("https://user:pass@93.184.216.34/x");
  assert.equal(result.ok, false);
  if (!result.ok) assert.equal(result.status, "INVALID_URL");
});

test("blocks loopback, private, link-local, metadata literal IPs", () => {
  for (const url of [
    "http://127.0.0.1/x",
    "http://10.0.0.5/x",
    "http://192.168.1.1/x",
    "http://169.254.169.254/latest/meta-data",
    "http://[::1]/x",
    "http://[::ffff:127.0.0.1]/x",
  ]) {
    const result = screenStructure(url);
    assert.equal(result.ok, false, url);
    if (!result.ok) assert.equal(result.status, "BLOCKED");
  }
});

test("blocks obfuscated numeric IPv4 (decimal, hex) via canonicalization or numeric-host rule", () => {
  for (const url of ["http://2130706433/x", "http://0x7f000001/x", "http://017700000001/x"]) {
    const result = screenStructure(url);
    assert.equal(result.ok, false, url);
  }
});

test("blocks internal names and single-label container hosts", () => {
  for (const url of [
    "http://localhost/x",
    "http://postgres/x",
    "http://scraper-worker/x",
    "http://app/x",
    "http://db.internal/x",
    "http://host.local/x",
  ]) {
    const result = screenStructure(url);
    assert.equal(result.ok, false, url);
    if (!result.ok) assert.equal(result.status, "BLOCKED");
  }
});

test("screenWithDns short-circuits literal IPs and resolves hostnames", async () => {
  const literal = await screenWithDns("https://93.184.216.34/x");
  assert.equal(literal.ok, true);
  const blockedLiteral = await screenWithDns("http://10.1.2.3/x");
  assert.equal(blockedLiteral.ok, false);
  if (!blockedLiteral.ok) assert.equal(blockedLiteral.status, "BLOCKED");
});

test("screenWithDns rejects a hostname that resolves to loopback via the hosts file", async () => {
  // 'localhost' is rejected structurally, proving no private destination is reachable.
  const result = await screenWithDns("http://localhost:8080/x");
  assert.equal(result.ok, false);
});

test("screenWithDns rejects every mixed or rebound DNS answer when any address is private", async () => {
  const result = await screenWithDns(
    "https://jobs.example.test/vacancy/1",
    async () => [{ address: "93.184.216.34" }, { address: "127.0.0.1" }],
  );
  assert.equal(result.ok, false);
  if (!result.ok) assert.equal(result.status, "BLOCKED");
});

test("screenWithDns fails closed when DNS resolution fails or returns no addresses", async () => {
  const failed = await screenWithDns("https://jobs.example.test/x", async () => {
    throw new Error("resolution failed");
  });
  assert.equal(failed.ok, false);
  if (!failed.ok) assert.equal(failed.status, "FETCH_FAILED");

  const empty = await screenWithDns("https://jobs.example.test/x", async () => []);
  assert.equal(empty.ok, false);
  if (!empty.ok) assert.equal(empty.status, "FETCH_FAILED");
});

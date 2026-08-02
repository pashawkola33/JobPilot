/**
 * Independent SSRF boundary for the worker. Spring Boot applies its own Stage 2
 * policy before calling; the worker re-validates the initial URL, every
 * main-frame redirect, and popup navigations, and screens subresources.
 */
import { lookup } from "node:dns/promises";
import { isIP } from "node:net";
import { isProhibitedIp } from "./addressClassifier.js";
import type { ResultStatus } from "./types.js";

export type DnsLookup = (
  hostname: string,
  options: { all: true; verbatim: true },
) => Promise<{ address: string }[]>;

export type ScreenResult =
  | { ok: true; url: URL }
  | { ok: false; status: Extract<ResultStatus, "INVALID_URL" | "BLOCKED" | "FETCH_FAILED"> };

const ALLOWED_SCHEMES = new Set(["http:", "https:"]);
const PROHIBITED_HOST_SUFFIXES = [".local", ".localhost", ".internal", ".localdomain"];
const PROHIBITED_HOST_EXACT = new Set([
  "localhost",
  "metadata.google.internal",
  "metadata.goog",
  "instance-data",
  "instance-data.ec2.internal",
]);

/** Synchronous structural + literal-IP + internal-name screening (no DNS). */
export function screenStructure(rawUrl: string): ScreenResult {
  let url: URL;
  try {
    url = new URL(rawUrl);
  } catch {
    return { ok: false, status: "INVALID_URL" };
  }
  if (!ALLOWED_SCHEMES.has(url.protocol)) return { ok: false, status: "INVALID_URL" };
  if (url.username !== "" || url.password !== "") return { ok: false, status: "INVALID_URL" };
  if (url.hostname === "" || rawUrl.length > 2000) return { ok: false, status: "INVALID_URL" };

  const host = hostWithoutBrackets(url.hostname).toLowerCase();
  const literalFamily = isIP(host);
  if (literalFamily !== 0) {
    return isProhibitedIp(host) ? { ok: false, status: "BLOCKED" } : { ok: true, url };
  }
  // A bracketed value that is not a valid IPv6 literal is malformed.
  if (url.hostname.startsWith("[")) return { ok: false, status: "INVALID_URL" };
  // Single-label hosts are internal/container service names, never public.
  if (!host.includes(".")) return { ok: false, status: "BLOCKED" };
  if (PROHIBITED_HOST_EXACT.has(host)) return { ok: false, status: "BLOCKED" };
  if (PROHIBITED_HOST_SUFFIXES.some((suffix) => host.endsWith(suffix))) {
    return { ok: false, status: "BLOCKED" };
  }
  // Reject bare-number and hex hostnames the URL parser did not canonicalize.
  if (/^(0x[0-9a-f]+|[0-9]+)$/i.test(host)) return { ok: false, status: "BLOCKED" };
  return { ok: true, url };
}

/** Full validation: structure + DNS resolution with every answer classified. */
export async function screenWithDns(
  rawUrl: string,
  resolver: DnsLookup = lookup,
): Promise<ScreenResult> {
  const structural = screenStructure(rawUrl);
  if (!structural.ok) return structural;
  const host = hostWithoutBrackets(structural.url.hostname).toLowerCase();
  if (isIP(host) !== 0) return structural; // Already classified above.

  let addresses: { address: string }[];
  try {
    addresses = await resolver(host, { all: true, verbatim: true });
  } catch {
    return { ok: false, status: "FETCH_FAILED" };
  }
  if (addresses.length === 0) return { ok: false, status: "FETCH_FAILED" };
  for (const entry of addresses) {
    if (isProhibitedIp(entry.address)) return { ok: false, status: "BLOCKED" };
  }
  return structural;
}

/** Cheap synchronous decision for a single intercepted subresource request. */
export function screenSubresource(rawUrl: string): boolean {
  const structural = screenStructure(rawUrl);
  return structural.ok;
}

function hostWithoutBrackets(host: string): string {
  return host.startsWith("[") && host.endsWith("]") ? host.slice(1, -1) : host;
}

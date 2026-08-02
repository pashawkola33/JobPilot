/**
 * Bounded structured logging. Only an opaque request id, a fixed result
 * category, a bounded duration, and a sanitized source category may appear.
 * Never a full URL/query string, HTML, description, header, cookie, secret,
 * fingerprint, browser path, or exception message.
 */
import type { ResultStatus } from "./types.js";
import { truncateUtf16 } from "./text.js";

export interface LogFields {
  requestId: string;
  status:
    | ResultStatus
    | "auth_failed"
    | "started"
    | "config_error"
    | "shutdown"
    | "request_handler_called";
  durationMs?: number;
  source?: "JSON_LD" | "EMBEDDED" | "DOM" | "META" | "NONE";
}

export function logEvent(fields: LogFields): void {
  const line = {
    ts: new Date().toISOString(),
    component: "scraper-worker",
    requestId: opaque(fields.requestId),
    status: fields.status,
    ...(fields.durationMs !== undefined ? { durationMs: Math.max(0, Math.round(fields.durationMs)) } : {}),
    ...(fields.source ? { source: fields.source } : {}),
  };
  process.stdout.write(`${JSON.stringify(line)}\n`);
}

/** Keep only a short, safe subset of the caller-supplied opaque id. */
function opaque(requestId: string): string {
  const safe = truncateUtf16((requestId ?? "").replace(/[^A-Za-z0-9._-]/g, ""), 64);
  return safe.length > 0 ? safe : "unknown";
}

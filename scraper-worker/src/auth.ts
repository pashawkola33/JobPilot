/**
 * Constant-time shared-secret check for POST /v1/extract. Missing and invalid
 * secrets are indistinguishable to the caller and the value is never logged.
 */
import { timingSafeEqual } from "node:crypto";

export const INTERNAL_SECRET_HEADER = "x-jobpilot-worker-secret";

export function isAuthorized(provided: string | undefined, expected: string): boolean {
  if (typeof provided !== "string" || provided.length === 0) return false;
  const a = Buffer.from(provided, "utf8");
  const b = Buffer.from(expected, "utf8");
  // Compare against a same-length buffer so length never short-circuits, then
  // require exact length equality.
  const padded = a.length === b.length ? a : Buffer.alloc(b.length);
  let equal = timingSafeEqual(padded, b);
  if (a.length !== b.length) equal = false;
  return equal;
}

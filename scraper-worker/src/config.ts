/**
 * Runtime configuration for the worker, validated fail-closed at startup.
 * Pure: no browser or network dependency, so it is fully unit-testable.
 */

export interface WorkerConfig {
  port: number;
  sharedSecret: string;
  navigationTimeoutMs: number;
  totalTimeoutMs: number;
  maxRedirects: number;
  maxRequestsPerRender: number;
  maxRequestBodyBytes: number;
  maxDescriptionChars: number;
  maxSearchResults: number;
  maxConcurrentRenders: number;
  executablePathOverride: string | undefined;
  version: string;
}

export class ConfigError extends Error {}

const MIN_SECRET_BYTES = 32;

export function loadConfig(env: NodeJS.ProcessEnv = process.env): WorkerConfig {
  const secret = (env.SCRAPER_WORKER_SHARED_SECRET ?? "").trim();
  if (Buffer.byteLength(secret, "utf8") < MIN_SECRET_BYTES) {
    // Never echo the value; report only the requirement.
    throw new ConfigError(
      "SCRAPER_WORKER_SHARED_SECRET must be set to at least 32 random bytes",
    );
  }

  const port = integer(env.PORT, 3000, 1, 65535);
  const navigationTimeoutMs = integer(env.WORKER_NAVIGATION_TIMEOUT_MS, 20000, 1000, 60000);
  const totalTimeoutMs = integer(env.WORKER_TOTAL_TIMEOUT_MS, 40000, 2000, 90000);
  if (totalTimeoutMs <= navigationTimeoutMs) {
    throw new ConfigError("WORKER_TOTAL_TIMEOUT_MS must exceed WORKER_NAVIGATION_TIMEOUT_MS");
  }
  const maxRedirects = integer(env.WORKER_MAX_REDIRECTS, 5, 0, 10);
  const maxRequestsPerRender = integer(env.WORKER_MAX_REQUESTS, 80, 1, 500);
  const maxRequestBodyBytes = integer(env.WORKER_MAX_REQUEST_BODY_BYTES, 8192, 256, 65536);
  const maxDescriptionChars = integer(env.WORKER_MAX_DESCRIPTION_CHARS, 50000, 40, 200000);
  const maxSearchResults = integer(env.WORKER_MAX_SEARCH_RESULTS, 50, 1, 100);
  const maxConcurrentRenders = integer(env.WORKER_MAX_CONCURRENT_RENDERS, 1, 1, 4);

  const override = (env.CLOAKBROWSER_EXECUTABLE_PATH ?? "").trim();
  const version = safeToken(env.WORKER_VERSION, "0.1.0");

  return {
    port,
    sharedSecret: secret,
    navigationTimeoutMs,
    totalTimeoutMs,
    maxRedirects,
    maxRequestsPerRender,
    maxRequestBodyBytes,
    maxDescriptionChars,
    maxSearchResults,
    maxConcurrentRenders,
    executablePathOverride: override.length > 0 ? override : undefined,
    version,
  };
}

function integer(raw: string | undefined, fallback: number, min: number, max: number): number {
  if (raw === undefined || raw.trim() === "") return fallback;
  const value = Number(raw);
  if (!Number.isInteger(value) || value < min || value > max) {
    throw new ConfigError(`A worker numeric setting is outside its safe bounds [${min}, ${max}]`);
  }
  return value;
}

function safeToken(raw: string | undefined, fallback: string): string {
  const value = (raw ?? "").trim();
  if (value === "") return fallback;
  if (!/^[A-Za-z0-9._+-]{1,100}$/.test(value)) {
    throw new ConfigError("WORKER_VERSION contains unsafe characters");
  }
  return value;
}

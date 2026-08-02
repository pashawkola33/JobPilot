/**
 * Minimal internal HTTP surface built on node:http (no framework dependency).
 *   GET  /health       -> bounded status/version only
 *   POST /v1/extract   -> shared-secret protected single-URL extraction
 * The browser render step is injected so the HTTP/validation layer is testable
 * without a real browser.
 */
import { createServer as createHttpServer, type IncomingMessage, type Server, type ServerResponse } from "node:http";
import { INTERNAL_SECRET_HEADER, isAuthorized } from "./auth.js";
import { buildResult } from "./parse.js";
import { screenWithDns, type ScreenResult } from "./urlPolicy.js";
import { logEvent } from "./logging.js";
import { buildLinkedInSearchResult, isLinkedInSearchUrl } from "./linkedin.js";
import type { AdmissionController } from "./admission.js";
import type { Metrics, MetricCategory } from "./metrics.js";
import type { WorkerConfig } from "./config.js";
import type {
  ExtractRequest,
  ExtractResponse,
  RenderFn,
  ResultStatus,
  SearchResponse,
} from "./types.js";

export interface ServerDeps {
  config: WorkerConfig;
  metrics: Metrics;
  render: RenderFn;
  admission: AdmissionController;
  browserReady: boolean;
  screenUrl?: (url: string) => Promise<ScreenResult>;
  now?: () => number;
}

export function createServer(deps: ServerDeps): Server {
  const now = deps.now ?? (() => Date.now());
  const server = createHttpServer((req, res) => {
    handle(req, res, deps, now).catch(() => sendJson(res, 500, { status: "ERROR" }));
  });
  server.requestTimeout = deps.config.totalTimeoutMs + 5_000;
  server.headersTimeout = Math.min(10_000, server.requestTimeout);
  server.keepAliveTimeout = 5_000;
  server.setTimeout(deps.config.totalTimeoutMs + 5_000);
  return server;
}

async function handle(
  req: IncomingMessage,
  res: ServerResponse,
  deps: ServerDeps,
  now: () => number,
): Promise<void> {
  const url = req.url ?? "";
  if (req.method === "GET" && (url === "/health" || url.startsWith("/health?"))) {
    sendJson(res, deps.browserReady ? 200 : 503, {
      status: deps.browserReady ? "READY" : "DEGRADED",
      component: "scraper-worker",
      version: deps.config.version,
      browser: deps.browserReady ? "READY" : "UNAVAILABLE",
    });
    return;
  }
  if (req.method === "POST" && (url === "/v1/extract" || url === "/v1/search")) {
    await handleExtract(req, res, deps, now, url === "/v1/search");
    return;
  }
  sendJson(res, 404, { status: "NOT_FOUND" });
}

async function handleExtract(
  req: IncomingMessage,
  res: ServerResponse,
  deps: ServerDeps,
  now: () => number,
  search: boolean,
): Promise<void> {
  const provided = headerValue(req, INTERNAL_SECRET_HEADER);
  if (!isAuthorized(provided, deps.config.sharedSecret)) {
    logEvent({ requestId: "unknown", status: "auth_failed" });
    sendJson(res, 401, { status: "UNAUTHORIZED" });
    return;
  }

  deps.metrics.increment("requested");
  let body: string;
  try {
    body = await readBody(req, deps.config.maxRequestBodyBytes);
  } catch (error) {
    sendJson(res, error === TOO_LARGE ? 413 : 400, { status: "BAD_REQUEST" });
    return;
  }

  const request = parseRequest(body);
  if (request === null) {
    sendJson(res, 400, { status: "BAD_REQUEST" });
    return;
  }

  const release = deps.admission.tryAcquire();
  if (release === null) {
    deps.metrics.increment("overloaded");
    sendJson(res, 503, { status: "BUSY" });
    return;
  }

  try {

    const started = now();
    if (search && !isLinkedInSearchUrl(request.url)) {
      finish(res, deps, { requestId: request.requestId, status: "INVALID_URL" }, started, now);
      return;
    }
    const screened = await (deps.screenUrl ?? screenWithDns)(request.url);
    if (!screened.ok) {
      finish(res, deps, { requestId: request.requestId, status: screened.status }, started, now);
      return;
    }
    if (!deps.browserReady) {
      finish(res, deps, { requestId: request.requestId, status: "FETCH_FAILED" }, started, now, true);
      return;
    }

    let response: ExtractResponse | SearchResponse;
    try {
      const outcome = await deps.render(request);
      response =
        outcome.kind === "PAGE"
          ? search
            ? buildLinkedInSearchResult(request, outcome.data, deps.config.maxSearchResults)
            : buildResult(request, outcome.data, deps.config.maxDescriptionChars)
          : { requestId: request.requestId, status: outcome.status };
    } catch {
      response = { requestId: request.requestId, status: "FETCH_FAILED" };
    }
    finish(res, deps, response, started, now);
  } finally {
    release();
  }
}

function finish(
  res: ServerResponse,
  deps: ServerDeps,
  response: ExtractResponse | SearchResponse,
  started: number,
  now: () => number,
  unavailable = false,
): void {
  deps.metrics.increment(unavailable ? "unavailable" : metricFor(response.status));
  logEvent({
    requestId: response.requestId,
    status: response.status,
    durationMs: now() - started,
    source:
      response.status === "EXTRACTED" && "evidence" in response
        ? response.evidence.descriptionSource ?? "NONE"
        : "NONE",
  });
  sendJson(res, unavailable ? 503 : 200, response);
}

function metricFor(status: ResultStatus): MetricCategory {
  switch (status) {
    case "EXTRACTED":
      return "extracted";
    case "INSUFFICIENT_DATA":
    case "UNSUPPORTED":
      return "insufficient";
    case "CHALLENGE_DETECTED":
      return "challenge";
    case "TIMEOUT":
      return "timeout";
    case "FETCH_FAILED":
      return "unavailable";
    case "BLOCKED":
    case "INVALID_URL":
    case "AUTH_REQUIRED":
    case "RATE_LIMITED":
      return "blocked";
    default:
      return "invalid_response";
  }
}

function parseRequest(body: string): ExtractRequest | null {
  let parsed: unknown;
  try {
    parsed = JSON.parse(body);
  } catch {
    return null;
  }
  if (parsed === null || typeof parsed !== "object" || Array.isArray(parsed)) return null;
  const record = parsed as Record<string, unknown>;
  const requestId = record["requestId"];
  const url = record["url"];
  const expectedSource = record["expectedSource"];
  if (typeof requestId !== "string" || !/^[A-Za-z0-9._-]{1,128}$/.test(requestId)) return null;
  if (typeof url !== "string" || url.length === 0 || url.length > 2000) return null;
  if (
    expectedSource !== undefined &&
    (typeof expectedSource !== "string" || !/^[A-Za-z0-9._:-]{0,64}$/.test(expectedSource))
  ) {
    return null;
  }
  return {
    requestId,
    url,
    expectedSource: typeof expectedSource === "string" && expectedSource !== "" ? expectedSource : undefined,
  };
}

const TOO_LARGE = Symbol("too-large");

function readBody(req: IncomingMessage, maxBytes: number): Promise<string> {
  return new Promise((resolve, reject) => {
    const chunks: Buffer[] = [];
    let total = 0;
    let settled = false;
    const onData = (chunk: Buffer): void => {
      if (settled) return;
      total += chunk.length;
      if (total > maxBytes) {
        settled = true;
        req.removeListener("data", onData);
        req.resume(); // Drain without buffering so the 413 response can still flush.
        reject(TOO_LARGE);
        return;
      }
      chunks.push(chunk);
    };
    req.on("data", onData);
    req.on("end", () => {
      if (!settled) {
        settled = true;
        resolve(Buffer.concat(chunks).toString("utf8"));
      }
    });
    req.on("error", (error) => {
      if (!settled) {
        settled = true;
        reject(error);
      }
    });
  });
}

function headerValue(req: IncomingMessage, name: string): string | undefined {
  const value = req.headers[name];
  return Array.isArray(value) ? value[0] : value;
}

function sendJson(res: ServerResponse, statusCode: number, payload: unknown): void {
  const body = JSON.stringify(payload);
  res.writeHead(statusCode, {
    "content-type": "application/json; charset=utf-8",
    "content-length": Buffer.byteLength(body),
    "x-content-type-options": "nosniff",
    "cache-control": "no-store",
  });
  res.end(body);
}

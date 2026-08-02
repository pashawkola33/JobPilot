import { strict as assert } from "node:assert";
import { test } from "node:test";
import { once } from "node:events";
import type { AddressInfo } from "node:net";
import { loadConfig } from "../src/config.js";
import { Metrics } from "../src/metrics.js";
import { createServer } from "../src/server.js";
import { INTERNAL_SECRET_HEADER } from "../src/auth.js";
import { AdmissionController } from "../src/admission.js";
import { screenStructure } from "../src/urlPolicy.js";
import type { RawPageData, RenderFn } from "../src/types.js";

const SECRET = "0123456789abcdef0123456789abcdef";
const DESCRIPTION =
  "Build and maintain Java backend services with Spring Boot, PostgreSQL, and REST APIs, " +
  "collaborating with a mentoring engineering team on tested production code.";

function baseEnv(extra: Record<string, string> = {}): NodeJS.ProcessEnv {
  return { SCRAPER_WORKER_SHARED_SECRET: SECRET, WORKER_MAX_REQUEST_BODY_BYTES: "512", ...extra };
}

function jobPage(): RawPageData {
  return {
    finalUrl: "https://93.184.216.34/jobs/1",
    jsonLdBlocks: [
      JSON.stringify({
        "@type": "JobPosting",
        title: "Java Backend Intern",
        hiringOrganization: { name: "Example Company" },
        description: DESCRIPTION,
        url: "https://93.184.216.34/jobs/1",
      }),
    ],
    meta: {},
    embeddedState: [],
    dom: { title: null, company: null, location: null, employmentType: null, description: null },
    signals: {
      hasPasswordField: false,
      hasLoginForm: false,
      challengeMarker: false,
      accessDeniedTitle: false,
      bodyTextLength: 4000,
    },
  };
}

interface Harness {
  url: string;
  metrics: Metrics;
  state: { renderCalls: number };
  close: () => Promise<void>;
}

async function startServer(options: {
  render?: RenderFn;
  browserReady?: boolean;
  env?: Record<string, string>;
} = {}): Promise<Harness> {
  const metrics = new Metrics();
  const state = { renderCalls: 0 };
  const inner: RenderFn = options.render ?? (async () => ({ kind: "PAGE", data: jobPage() }));
  const render: RenderFn = async (request) => {
    state.renderCalls++;
    return inner(request);
  };
  const config = loadConfig(baseEnv(options.env));
  const admission = new AdmissionController(config.maxConcurrentRenders);
  const server = createServer({
    config,
    metrics,
    render,
    admission,
    browserReady: options.browserReady ?? true,
    screenUrl: async (url) => screenStructure(url),
  });
  server.listen(0);
  await once(server, "listening");
  const port = (server.address() as AddressInfo).port;
  return {
    url: `http://127.0.0.1:${port}`,
    metrics,
    state,
    close: () =>
      new Promise<void>((resolve) => {
        server.closeAllConnections();
        server.close(() => resolve());
      }),
  };
}

async function post(
  url: string,
  body: string,
  headers: Record<string, string> = {},
  path = "/v1/extract",
): Promise<{ status: number; json: any }> {
  const res = await fetch(`${url}${path}`, { method: "POST", body, headers });
  const text = await res.text();
  return { status: res.status, json: text ? JSON.parse(text) : null };
}

const auth = { [INTERNAL_SECRET_HEADER]: SECRET, "content-type": "application/json" };

test("health returns bounded status", async () => {
  const h = await startServer();
  const res = await fetch(`${h.url}/health`);
  const body = (await res.json()) as Record<string, unknown>;
  assert.equal(res.status, 200);
  assert.equal(body.status, "READY");
  assert.ok(!("secret" in body) && !("executablePath" in body));
  await h.close();
});

test("rejects missing and wrong secret with a generic 401", async () => {
  const h = await startServer();
  const noSecret = await post(h.url, JSON.stringify({ requestId: "a", url: "https://93.184.216.34/x" }), {
    "content-type": "application/json",
  });
  assert.equal(noSecret.status, 401);
  const wrong = await post(h.url, JSON.stringify({ requestId: "a", url: "https://93.184.216.34/x" }), {
    [INTERNAL_SECRET_HEADER]: "wrong",
    "content-type": "application/json",
  });
  assert.equal(wrong.status, 401);
  assert.equal(h.state.renderCalls, 0);
  await h.close();
});

test("extracts a valid public-IP request via the injected renderer", async () => {
  const h = await startServer();
  const res = await post(h.url, JSON.stringify({ requestId: "req-1", url: "https://93.184.216.34/jobs/1" }), auth);
  assert.equal(res.status, 200);
  assert.equal(res.json.status, "EXTRACTED");
  assert.equal(res.json.sourceType, "BROWSER");
  assert.equal(res.json.job.title, "Java Backend Intern");
  assert.equal(h.metrics.snapshot().extracted, 1);
  assert.equal(h.metrics.snapshot().requested, 1);
  await h.close();
});

test("rejects malformed body and missing fields", async () => {
  const h = await startServer();
  assert.equal((await post(h.url, "{not json", auth)).status, 400);
  assert.equal((await post(h.url, JSON.stringify({ requestId: "a" }), auth)).status, 400);
  assert.equal((await post(h.url, JSON.stringify({ url: "https://93.184.216.34/x" }), auth)).status, 400);
  assert.equal(h.state.renderCalls, 0);
  await h.close();
});

test("enforces a request body size bound", async () => {
  const h = await startServer();
  const big = JSON.stringify({ requestId: "a", url: "https://93.184.216.34/" + "x".repeat(1000) });
  const res = await post(h.url, big, auth);
  assert.equal(res.status, 413);
  await h.close();
});

test("blocks an SSRF destination without invoking the renderer", async () => {
  const h = await startServer();
  const res = await post(h.url, JSON.stringify({ requestId: "req-2", url: "http://127.0.0.1/x" }), auth);
  assert.equal(res.status, 200);
  assert.equal(res.json.status, "BLOCKED");
  assert.equal(h.state.renderCalls, 0);
  await h.close();
});

test("returns a conservative 503 when the browser is unavailable", async () => {
  const h = await startServer({ browserReady: false });
  const res = await post(h.url, JSON.stringify({ requestId: "req-3", url: "https://93.184.216.34/jobs/1" }), auth);
  assert.equal(res.status, 503);
  assert.equal(res.json.status, "FETCH_FAILED");
  assert.equal(h.state.renderCalls, 0);
  assert.equal(h.metrics.snapshot().unavailable, 1);
  await h.close();
});

test("passes a typed browser failure straight through", async () => {
  const h = await startServer({ render: async (req) => ({ kind: "FAILURE", status: "CHALLENGE_DETECTED" }) });
  const res = await post(h.url, JSON.stringify({ requestId: "req-4", url: "https://93.184.216.34/jobs/1" }), auth);
  assert.equal(res.json.status, "CHALLENGE_DETECTED");
  assert.equal(h.metrics.snapshot().challenge, 1);
  await h.close();
});

test("releases the admission slot after a renderer failure", async () => {
  let calls = 0;
  const h = await startServer({
    render: async () => {
      calls++;
      if (calls === 1) throw new Error("renderer failed");
      return { kind: "PAGE", data: jobPage() };
    },
  });
  const body = JSON.stringify({ requestId: "slot", url: "https://93.184.216.34/jobs/1" });
  assert.equal((await post(h.url, body, auth)).json.status, "FETCH_FAILED");
  assert.equal((await post(h.url, body, auth)).json.status, "EXTRACTED");
  await h.close();
});

test("sets explicit HTTP request and header timeouts", () => {
  const config = loadConfig(baseEnv());
  const server = createServer({
    config,
    metrics: new Metrics(),
    render: async () => ({ kind: "PAGE", data: jobPage() }),
    admission: new AdmissionController(1),
    browserReady: true,
  });
  assert.equal(server.requestTimeout, config.totalTimeoutMs + 5_000);
  assert.equal(server.headersTimeout, 10_000);
  server.close();
});

test("rejects excess browser work immediately without queueing", async () => {
  let allowRender: (() => void) | undefined;
  let markStarted: (() => void) | undefined;
  const gate = new Promise<void>((resolve) => {
    allowRender = resolve;
  });
  const started = new Promise<void>((resolve) => {
    markStarted = resolve;
  });
  const h = await startServer({
    render: async () => {
      markStarted?.();
      await gate;
      return { kind: "PAGE", data: jobPage() };
    },
  });

  const first = post(
    h.url,
    JSON.stringify({ requestId: "first", url: "https://93.184.216.34/jobs/1" }),
    auth,
  );
  await started;
  const second = await post(
    h.url,
    JSON.stringify({ requestId: "second", url: "https://93.184.216.34/jobs/2" }),
    auth,
  );

  assert.equal(second.status, 503);
  assert.equal(second.json.status, "BUSY");
  const malformed = await post(h.url, "{not-json", auth);
  assert.equal(malformed.status, 400, "bounded parsing happens before admission");
  assert.equal(h.state.renderCalls, 1);
  assert.equal(h.metrics.snapshot().overloaded, 1);
  allowRender?.();
  assert.equal((await first).status, 200);
  await h.close();
});

test("serves bounded public LinkedIn search cards on the search endpoint", async () => {
  const searchPage: RawPageData = {
    ...jobPage(),
    finalUrl: "https://www.linkedin.com/jobs/search/?keywords=java",
    searchJobs: [
      {
        title: "Software Engineering Intern",
        company: "Example Company",
        location: "Bucharest, Romania",
        url: "https://ro.linkedin.com/jobs/view/software-engineering-intern-1234567890?trk=x",
      },
    ],
  };
  const h = await startServer({ render: async () => ({ kind: "PAGE", data: searchPage }) });
  const result = await post(
    h.url,
    JSON.stringify({
      requestId: "search-1",
      url: "https://www.linkedin.com/jobs/search/?keywords=java",
    }),
    auth,
    "/v1/search",
  );

  assert.equal(result.status, 200);
  assert.equal(result.json.status, "EXTRACTED");
  assert.equal(result.json.jobs.length, 1);
  assert.equal(result.json.jobs[0].externalId, "1234567890");
  assert.equal(result.json.jobs[0].url, "https://www.linkedin.com/jobs/view/1234567890");
  await h.close();
});

test("search endpoint rejects non-LinkedIn and detail URLs before rendering", async () => {
  const h = await startServer();
  for (const url of [
    "https://93.184.216.34/jobs/search",
    "https://www.linkedin.com/jobs/view/1234567890",
  ]) {
    const result = await post(
      h.url,
      JSON.stringify({ requestId: "bad-search", url }),
      auth,
      "/v1/search",
    );
    assert.equal(result.json.status, "INVALID_URL");
  }
  assert.equal(h.state.renderCalls, 0);
  await h.close();
});

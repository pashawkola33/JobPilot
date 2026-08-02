import { strict as assert } from "node:assert";
import { test } from "node:test";
import type { Configuration, RequestList } from "@crawlee/core";
import type { Page, Request, Response } from "playwright-core";
import { loadConfig } from "../src/config.js";
import { createBrowserRenderer } from "../src/extract.js";
import type { RawPageData } from "../src/types.js";

const SECRET = "0123456789abcdef0123456789abcdef";
const browser = { executablePath: "/fixture/chromium", stealthArgs: [], chromiumVersion: "fixture" };

interface FixtureOptions {
  requestHandler?: (context: { page: Page; response?: Response }) => Promise<void>;
  failedRequestHandler?: (context: { request: { errorMessages?: string[] } }) => void;
  launchContext?: { launchOptions?: Record<string, unknown> };
}

interface Scenario {
  chain?: string[];
  finalUrl?: string;
  status?: number;
  failureMessages?: string[];
  throwOnRun?: boolean;
  maxRedirects?: number;
}

function rawPage(finalUrl: string): RawPageData {
  return {
    finalUrl,
    jsonLdBlocks: [],
    meta: {},
    embeddedState: [],
    dom: {
      externalId: "1",
      title: "Java Intern",
      company: "Example",
      location: "Bucharest",
      employmentType: "Internship",
      description: "Java internship with Spring Boot, tests, mentoring, and production delivery.",
    },
    signals: {
      hasPasswordField: false,
      hasLoginForm: false,
      challengeMarker: false,
      accessDeniedTitle: false,
      bodyTextLength: 100,
    },
  };
}

function requestChain(urls: string[]): Request {
  let previous: Request | null = null;
  for (const url of urls) {
    const redirectedFrom = previous;
    previous = {
      url: () => url,
      redirectedFrom: () => redirectedFrom,
    } as unknown as Request;
  }
  if (previous === null) throw new Error("A request chain needs at least one URL");
  return previous;
}

async function execute(scenario: Scenario = {}) {
  const chain = scenario.chain ?? ["https://public.example/jobs/1"];
  const finalUrl = scenario.finalUrl ?? chain.at(-1)!;
  const state = { handlerCalls: 0, teardownCalls: 0, options: null as FixtureOptions | null };
  const config = loadConfig({
    SCRAPER_WORKER_SHARED_SECRET: SECRET,
    WORKER_MAX_REDIRECTS: String(scenario.maxRedirects ?? 5),
  });
  const page = {
    url: () => finalUrl,
    waitForLoadState: async () => undefined,
    waitForTimeout: async () => undefined,
    evaluate: async () => rawPage(finalUrl),
  } as unknown as Page;
  const response = {
    status: () => scenario.status ?? 200,
    request: () => requestChain(chain),
  } as unknown as Response;
  const render = createBrowserRenderer(config, browser, {
    screenUrl: async (raw) => {
      const url = new URL(raw);
      if (url.hostname === "127.0.0.1" || url.hostname.startsWith("10.")) {
        return { ok: false, status: "BLOCKED" };
      }
      if (url.hostname === "dns-fail.example") return { ok: false, status: "FETCH_FAILED" };
      return { ok: true, url };
    },
    openRequestList: async () => ({}) as RequestList,
    createCrawler: (options, _configuration: Configuration) => {
      state.options = options as unknown as FixtureOptions;
      return {
        run: async () => {
          if (scenario.throwOnRun) throw new Error("fixture failure");
          if (scenario.failureMessages) {
            state.options!.failedRequestHandler?.({ request: { errorMessages: scenario.failureMessages } });
          } else {
            state.handlerCalls++;
            await state.options!.requestHandler?.({ page, response });
          }
          return { requestsTotal: 1 };
        },
        teardown: async () => {
          state.teardownCalls++;
        },
      };
    },
  });
  const outcome = await render({ requestId: "renderer", url: chain[0]! });
  return { outcome, state };
}

test("production renderer processes one request, calls its handler once, and tears down", async () => {
  const { outcome, state } = await execute({
    chain: ["https://public.example/start", "https://redirect.example/jobs/1"],
  });
  assert.equal(outcome.kind, "PAGE");
  assert.equal(state.handlerCalls, 1);
  assert.equal(state.teardownCalls, 1);
  assert.equal(state.options?.launchContext?.launchOptions?.serviceWorkers, "block");
});

test("production renderer blocks loopback and RFC1918 redirect hops", async () => {
  for (const target of ["http://127.0.0.1/admin", "http://10.20.30.40/metadata"]) {
    const { outcome, state } = await execute({ chain: ["https://public.example/start", target] });
    assert.deepEqual(outcome, { kind: "FAILURE", status: "BLOCKED" });
    assert.equal(state.teardownCalls, 1);
  }
});

test("production renderer bounds redirects and fails closed on final DNS failure", async () => {
  const excessive = await execute({
    maxRedirects: 1,
    chain: [
      "https://public.example/start",
      "https://redirect.example/one",
      "https://redirect.example/two",
    ],
  });
  assert.deepEqual(excessive.outcome, { kind: "FAILURE", status: "FETCH_FAILED" });

  const dnsFailure = await execute({
    chain: ["https://public.example/start"],
    finalUrl: "https://dns-fail.example/final",
  });
  assert.deepEqual(dnsFailure.outcome, { kind: "FAILURE", status: "FETCH_FAILED" });
});

test("production renderer maps HTTP and navigation timeout failures precisely", async () => {
  for (const [status, expected] of [[401, "AUTH_REQUIRED"], [403, "BLOCKED"], [429, "RATE_LIMITED"]] as const) {
    const result = await execute({ status });
    assert.deepEqual(result.outcome, { kind: "FAILURE", status: expected });
    assert.equal(result.state.teardownCalls, 1);
  }
  const timedOut = await execute({ failureMessages: ["Navigation timed out after 20000 ms"] });
  assert.deepEqual(timedOut.outcome, { kind: "FAILURE", status: "TIMEOUT" });
  assert.equal(timedOut.state.teardownCalls, 1);
});

test("production renderer tears down after a thrown crawler error", async () => {
  const { outcome, state } = await execute({ throwOnRun: true });
  assert.deepEqual(outcome, { kind: "FAILURE", status: "FETCH_FAILED" });
  assert.equal(state.teardownCalls, 1);
});

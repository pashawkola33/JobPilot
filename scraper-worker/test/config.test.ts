import { strict as assert } from "node:assert";
import { test } from "node:test";
import { ConfigError, loadConfig } from "../src/config.js";

const SECRET = "0123456789abcdef0123456789abcdef";

test("requires a worker secret of at least 32 UTF-8 bytes", () => {
  for (const secret of [undefined, "", "x".repeat(31)]) {
    assert.throws(
      () => loadConfig({ SCRAPER_WORKER_SHARED_SECRET: secret }),
      ConfigError,
    );
  }
  assert.equal(loadConfig({ SCRAPER_WORKER_SHARED_SECRET: "🚀".repeat(8) }).sharedSecret.length, 16);
});

test("rejects invalid timeout and bound relationships", () => {
  for (const settings of [
    { WORKER_NAVIGATION_TIMEOUT_MS: "20000", WORKER_TOTAL_TIMEOUT_MS: "20000" },
    { WORKER_NAVIGATION_TIMEOUT_MS: "999" },
    { WORKER_TOTAL_TIMEOUT_MS: "90001" },
    { WORKER_MAX_REDIRECTS: "11" },
    { WORKER_MAX_REQUESTS: "0" },
    { WORKER_MAX_REQUEST_BODY_BYTES: "255" },
    { WORKER_MAX_DESCRIPTION_CHARS: "39" },
    { WORKER_MAX_CONCURRENT_RENDERS: "5" },
  ]) {
    assert.throws(
      () => loadConfig({ SCRAPER_WORKER_SHARED_SECRET: SECRET, ...settings }),
      ConfigError,
    );
  }
});

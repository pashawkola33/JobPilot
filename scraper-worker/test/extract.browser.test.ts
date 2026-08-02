/** Real-browser smoke through the production createBrowserRenderer factory. */
import { strict as assert } from "node:assert";
import { test } from "node:test";
import { loadConfig } from "../src/config.js";
import { resolveBrowser } from "../src/browserBinary.js";
import { createBrowserRenderer } from "../src/extract.js";
import { buildResult } from "../src/parse.js";
import { startFixture } from "./fixtures/server.js";

const SECRET = "0123456789abcdef0123456789abcdef";
const browserTestsEnabled = process.env.RUN_BROWSER_TESTS === "true";
const resolved = browserTestsEnabled
  ? await resolveBrowser(process.env.CLOAKBROWSER_EXECUTABLE_PATH)
  : null;
const skip = browserTestsEnabled
  ? resolved
    ? false
    : "CloakBrowser binary not installed"
  : "set RUN_BROWSER_TESTS=true";

test("production renderer processes one request and blocks subresources", { skip }, async () => {
  const fixture = await startFixture();
  const config = loadConfig({ SCRAPER_WORKER_SHARED_SECRET: SECRET });
  const render = createBrowserRenderer(config, resolved!, {
    // The fixture is loopback by design. Production uses the default DNS policy;
    // only this local content transport is substituted in the smoke test.
    screenUrl: async (raw) => ({ ok: true, url: new URL(raw) }),
  });
  try {
    const request = { requestId: "real-render", url: `${fixture.origin}/jsonld` };
    const outcome = await render(request);
    assert.equal(outcome.kind, "PAGE");
    if (outcome.kind === "PAGE") {
      outcome.data.finalUrl = "https://93.184.216.34/jobs/ld";
      assert.equal(buildResult(request, outcome.data, 50000).status, "EXTRACTED");
    }

    const image = await render({ requestId: "real-image", url: `${fixture.origin}/withimage` });
    assert.equal(image.kind, "PAGE");
    assert.equal(fixture.imageRequests, 0);
  } finally {
    await fixture.close();
  }
});

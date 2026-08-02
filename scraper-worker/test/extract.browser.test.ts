/**
 * Browser integration test. Exercises the real CloakBrowser Chromium binary via
 * playwright-core against a synthetic LOCAL fixture: JS-injected JSON-LD, a
 * JS-built DOM vacancy, login detection, request interception, and fresh-context
 * cookie isolation. Skipped when the binary is not installed. The loopback SSRF
 * rule is covered separately by urlPolicy.test.ts (it would block this fixture).
 */
import { strict as assert } from "node:assert";
import { test } from "node:test";
import { chromium } from "playwright-core";
import type { Route } from "playwright-core";
import { resolveBrowser, SANDBOX_DISABLING_ARGUMENTS } from "../src/browserBinary.js";
import { collectInPage } from "../src/extract.js";
import { buildResult } from "../src/parse.js";
import { buildLinkedInSearchResult } from "../src/linkedin.js";
import { providerDetailFixtures, startFixture } from "./fixtures/server.js";
import type { ExtractRequest, RawPageData } from "../src/types.js";

const browserTestsEnabled = process.env.RUN_BROWSER_TESTS === "true";
const resolved = browserTestsEnabled
  ? await resolveBrowser(process.env.CLOAKBROWSER_EXECUTABLE_PATH)
  : null;
const skip = browserTestsEnabled
  ? resolved
    ? false
    : "CloakBrowser binary not installed (run: node node_modules/cloakbrowser/dist/cli.js install)"
  : "set RUN_BROWSER_TESTS=true to run the real-browser integration test";

const BLOCKED = new Set(["media", "image", "font", "websocket", "eventsource"]);

test("browser render: JSON-LD, DOM, login, interception, cookie isolation", { skip }, async () => {
  const browser = await chromium.launch({
    headless: true,
    executablePath: resolved!.executablePath,
    args: resolved!.stealthArgs,
    chromiumSandbox: true,
    ignoreDefaultArgs: [...SANDBOX_DISABLING_ARGUMENTS],
  });
  const fixture = await startFixture();
  const req = (path: string): ExtractRequest => ({ requestId: "browser", url: `${fixture.origin}${path}` });

  try {
    const context = await browser.newContext({ acceptDownloads: false });
    const page = await context.newPage();
    await page.route("**/*", (route: Route) => {
      const detail = providerDetailFixtures.find((candidate) => candidate.url === route.request().url());
      if (detail) {
        return route.fulfill({ status: 200, contentType: "text/html; charset=utf-8", body: detail.html });
      }
      return BLOCKED.has(route.request().resourceType()) ? route.abort() : route.continue();
    });

    // The rendered CONTENT comes from the real browser against the local
    // fixture; production would arrive here with a screened public final URL,
    // so we set a public one (a loopback URL is correctly rejected as canonical).
    await page.goto(`${fixture.origin}/jsonld`, { waitUntil: "load" });
    await page.waitForTimeout(200);
    const ld = (await page.evaluate(collectInPage, 400000)) as RawPageData;
    ld.finalUrl = "https://93.184.216.34/jobs/ld";
    const ldResult = buildResult(req("/jsonld"), ld, 50000);
    assert.equal(ldResult.status, "EXTRACTED");
    if (ldResult.status === "EXTRACTED") assert.equal(ldResult.evidence.titleSource, "JSON_LD");

    await page.goto(`${fixture.origin}/dom`, { waitUntil: "load" });
    await page.waitForTimeout(200);
    const dom = (await page.evaluate(collectInPage, 400000)) as RawPageData;
    dom.finalUrl = "https://93.184.216.34/jobs/dom";
    const domResult = buildResult(req("/dom"), dom, 50000);
    assert.equal(domResult.status, "EXTRACTED");
    if (domResult.status === "EXTRACTED") assert.equal(domResult.evidence.descriptionSource, "DOM");

    await page.goto(`${fixture.origin}/linkedin-detail`, { waitUntil: "load" });
    const linkedInDetail = (await page.evaluate(collectInPage, 400000)) as RawPageData;
    linkedInDetail.finalUrl = "https://www.linkedin.com/jobs/view/1234567890";
    const linkedInDetailResult = buildResult(req("/linkedin-detail"), linkedInDetail, 50000);
    assert.equal(linkedInDetailResult.status, "EXTRACTED");
    if (linkedInDetailResult.status === "EXTRACTED") {
      assert.equal(linkedInDetailResult.job.company, "Fixture LinkedIn Company");
      assert.equal(linkedInDetailResult.job.location, "Bucharest, Romania");
    }

    await page.goto(`${fixture.origin}/linkedin-search`, { waitUntil: "load" });
    const linkedInSearch = (await page.evaluate(collectInPage, 400000)) as RawPageData;
    linkedInSearch.finalUrl = "https://www.linkedin.com/jobs/search/?keywords=java";
    const linkedInSearchResult = buildLinkedInSearchResult(
      req("/linkedin-search"),
      linkedInSearch,
      50,
    );
    assert.equal(linkedInSearchResult.status, "EXTRACTED");
    if (linkedInSearchResult.status === "EXTRACTED") {
      assert.equal(linkedInSearchResult.jobs.length, 1);
      assert.equal(linkedInSearchResult.jobs[0]?.externalId, "1234567890");
    }

    for (const detail of providerDetailFixtures) {
      await page.goto(detail.url, { waitUntil: "load" });
      const raw = (await page.evaluate(collectInPage, 400000)) as RawPageData;
      const result = buildResult(
        { requestId: `browser-${detail.provider.toLowerCase()}`, url: detail.url },
        raw,
        50000,
      );
      assert.equal(result.status, "EXTRACTED", `${detail.provider} detail fixture must extract`);
      if (result.status === "EXTRACTED") {
        assert.equal(result.job.provider, detail.provider);
        assert.ok(result.job.externalId.length > 0);
        assert.ok(result.job.sourceUrl.startsWith("https://"));
      }
    }

    await page.goto(`${fixture.origin}/login`, { waitUntil: "load" });
    const login = (await page.evaluate(collectInPage, 400000)) as RawPageData;
    login.finalUrl = `${fixture.origin}/login`;
    assert.equal(buildResult(req("/login"), login, 50000).status, "AUTH_REQUIRED");

    await page.goto(`${fixture.origin}/withimage`, { waitUntil: "load" });
    await page.waitForTimeout(100);
    assert.equal(fixture.imageRequests, 0, "interception must block image subresources");

    const fresh = await browser.newContext();
    assert.equal((await fresh.cookies()).length, 0, "a fresh context carries no prior cookies");
    await fresh.close();
    await context.close();
  } finally {
    await fixture.close();
    await browser.close();
  }
});

/**
 * Browser rendering step: Crawlee PlaywrightCrawler -> playwright-core ->
 * CloakBrowser Chromium binary. One requested vacancy URL per operation, a
 * fresh isolated browser per extraction, request interception, redirect
 * re-validation, and bounded timeouts. No recursion, link discovery, cookies,
 * screenshots, traces, or downloads.
 */
// Import the crawler from the targeted subpackages rather than the `crawlee`
// barrel: the barrel eagerly loads the cheerio/linkedom chain, which has a
// broken CJS->ESM require under Node 22 ESM. These are the same classes.
import { PlaywrightCrawler } from "@crawlee/playwright";
import { Configuration, RequestList } from "@crawlee/core";
import { chromium } from "playwright-core";
import type { Dialog, Download, Page, Route, WebSocketRoute } from "playwright-core";
import { screenStructure, screenWithDns } from "./urlPolicy.js";
import { logEvent } from "./logging.js";
import type { WorkerConfig } from "./config.js";
import type { ResolvedBrowser } from "./browserBinary.js";
import { SANDBOX_DISABLING_ARGUMENTS } from "./browserBinary.js";
import type { ExtractRequest, RawPageData, RenderFn, RenderOutcome, ResultStatus } from "./types.js";

const BLOCKED_RESOURCE_TYPES = new Set(["media", "image", "font", "websocket", "eventsource"]);
const DOM_SCAN_LIMIT = 400000;

export function createBrowserRenderer(config: WorkerConfig, browser: ResolvedBrowser): RenderFn {
  return async (request: ExtractRequest): Promise<RenderOutcome> => {
    let outcome: RenderOutcome | null = null;
    let requestCount = 0;
    let documentNavigations = 0;
    let requestHandlerCalls = 0;

    const crawlerConfig = new Configuration({
      persistStorage: false,
      purgeOnStart: true,
      // Crawlee defaults to 25% of available memory. With one fixed render slot
      // this incorrectly treats 256 MiB as the ceiling inside our 1 GiB cgroup.
      availableMemoryRatio: 1,
    });
    // `null` keeps the one-item list memory-only; no RequestQueue or persisted
    // waiter state is created. Crawlee 3.17's runtime validator rejects its
    // otherwise-declared `config` RequestList option, so configuration remains
    // on the crawler itself.
    const requestList = await RequestList.open(null, [{ url: request.url }]);
    const crawler = new PlaywrightCrawler(
      {
        requestList,
        launchContext: {
          launcher: chromium,
          launchOptions: {
            headless: true,
            executablePath: browser.executablePath,
            args: browser.stealthArgs,
            chromiumSandbox: true,
            ignoreDefaultArgs: [...SANDBOX_DISABLING_ARGUMENTS],
            acceptDownloads: false,
          },
        },
        browserPoolOptions: {
          useFingerprints: false,
          maxOpenPagesPerBrowser: 1,
          retireBrowserAfterPageCount: 1,
        },
        maxConcurrency: 1,
        maxRequestsPerCrawl: 1,
        maxRequestRetries: 0,
        navigationTimeoutSecs: Math.ceil(config.navigationTimeoutMs / 1000),
        requestHandlerTimeoutSecs: Math.ceil(config.totalTimeoutMs / 1000),
        preNavigationHooks: [
          async ({ page }: { page: Page }) => {
            page.on("dialog", (dialog: Dialog) => void dialog.dismiss().catch(() => undefined));
            page.on("popup", (popup: Page) => void popup.close().catch(() => undefined));
            page.on("download", (download: Download) => void download.cancel().catch(() => undefined));
            try {
              await page.routeWebSocket(/.*/, (ws: WebSocketRoute) => ws.close());
            } catch {
              // Older engines without WebSocket routing still block via resource type.
            }
            const screenedSubresourceOrigins = new Map<string, Promise<boolean>>();
            await page.route("**/*", async (route: Route) => {
              const req = route.request();
              const url = req.url();
              const type = req.resourceType();
              if (++requestCount > config.maxRequestsPerRender) return route.abort();
              if (BLOCKED_RESOURCE_TYPES.has(type)) return route.abort();
              const isMainDocument = type === "document" && req.frame() === page.mainFrame();
              if (isMainDocument) {
                if (++documentNavigations > config.maxRedirects + 1) return route.abort();
                const screened = await screenWithDns(url);
                if (!screened.ok) {
                  outcome ??= { kind: "FAILURE", status: screened.status };
                  return route.abort();
                }
                return continueWithoutCredentials(route);
              }
              const structural = screenStructure(url);
              if (!structural.ok) return route.abort();
              const origin = structural.url.origin;
              let allowed = screenedSubresourceOrigins.get(origin);
              if (allowed === undefined) {
                allowed = screenWithDns(origin).then((result) => result.ok);
                screenedSubresourceOrigins.set(origin, allowed);
              }
              if (!(await allowed)) return route.abort();
              return continueWithoutCredentials(route);
            });
          },
        ],
        requestHandler: async ({ page, response }) => {
          requestHandlerCalls++;
          logEvent({ requestId: request.requestId, status: "request_handler_called" });
          if (requestHandlerCalls !== 1) {
            outcome = { kind: "FAILURE", status: "FETCH_FAILED" };
            return;
          }
          const statusFailure = classifyHttpStatus(response?.status());
          if (statusFailure) {
            outcome = { kind: "FAILURE", status: statusFailure };
            return;
          }
          const finalScreen = await screenWithDns(page.url());
          if (!finalScreen.ok) {
            outcome = { kind: "FAILURE", status: finalScreen.status };
            return;
          }
          try {
            await page.waitForLoadState("load", { timeout: config.navigationTimeoutMs });
          } catch {
            // A slow 'load' is acceptable; DOM content is already available.
          }
          await page.waitForTimeout(400); // Allow immediate JS-injected content to settle.
          const data = (await page.evaluate(collectInPage, DOM_SCAN_LIMIT)) as RawPageData;
          data.finalUrl = finalScreen.url.toString();
          outcome = { kind: "PAGE", data };
        },
        failedRequestHandler: ({ request: crawleeRequest }) => {
          if (outcome === null) {
            outcome = { kind: "FAILURE", status: classifyNavigationError(crawleeRequest.errorMessages) };
          }
        },
      },
      crawlerConfig,
    );

    try {
      const statistics = await crawler.run();
      if (statistics.requestsTotal !== 1 || requestHandlerCalls !== 1) {
        outcome = { kind: "FAILURE", status: "FETCH_FAILED" };
      }
    } catch {
      outcome ??= { kind: "FAILURE", status: "FETCH_FAILED" };
    } finally {
      await crawler.teardown().catch(() => undefined);
    }
    return outcome ?? { kind: "FAILURE", status: "FETCH_FAILED" };
  };
}

function classifyHttpStatus(status: number | undefined): Exclude<ResultStatus, "EXTRACTED"> | null {
  if (status === undefined) return null;
  if (status === 401) return "AUTH_REQUIRED";
  if (status === 403) return "BLOCKED";
  if (status === 429) return "RATE_LIMITED";
  if (status >= 400) return "FETCH_FAILED";
  return null;
}

function classifyNavigationError(messages: string[] | undefined): Exclude<ResultStatus, "EXTRACTED"> {
  const joined = (messages ?? []).join(" ").toLowerCase();
  if (joined.includes("timeout") || joined.includes("timed out")) return "TIMEOUT";
  return "FETCH_FAILED";
}

/**
 * Runs INSIDE the rendered page. Returns only bounded, serializable candidates —
 * never HTML, cookies, storage, or headers. Self-contained (no module scope).
 * Exported so the browser integration test can evaluate it against a fixture.
 */
export function collectInPage(maxScan: number): RawPageData {
  const doc = (globalThis as unknown as { document: any; location: { href: string } }).document;
  const href = (globalThis as unknown as { location: { href: string } }).location.href;
  const text = (node: any): string | null => {
    if (!node) return null;
    const value = (node.innerText ?? node.textContent ?? "").toString();
    return value.trim().length > 0 ? value.slice(0, maxScan) : null;
  };
  const attr = (selector: string, name: string): string | null => {
    const el = doc.querySelector(selector);
    const value = el ? el.getAttribute(name) : null;
    return value && value.trim().length > 0 ? value : null;
  };

  const jsonLdBlocks: string[] = [];
  const ldNodes = doc.querySelectorAll('script[type="application/ld+json"]');
  for (let i = 0; i < ldNodes.length && jsonLdBlocks.length < 30; i++) {
    const raw = (ldNodes[i].textContent ?? "").toString();
    if (raw.trim().length > 0 && raw.length <= 200000) jsonLdBlocks.push(raw);
  }

  const embeddedState: string[] = [];
  let embeddedBytes = 0;
  const jsonScripts = doc.querySelectorAll('script[type="application/json"]');
  for (let i = 0; i < jsonScripts.length && embeddedState.length < 20; i++) {
    const raw = (jsonScripts[i].textContent ?? "").toString();
    const trimmed = raw.trim();
    if (
      trimmed.length > 1 &&
      trimmed.length <= 250000 &&
      embeddedBytes + trimmed.length <= 1000000 &&
      (trimmed.startsWith("{") || trimmed.startsWith("["))
    ) {
      embeddedState.push(trimmed);
      embeddedBytes += trimmed.length;
    }
  }

  const meta: Record<string, string> = {};
  const metaKeys: Array<[string, string]> = [
    ['meta[property="og:title"]', "og:title"],
    ['meta[name="twitter:title"]', "twitter:title"],
    ['meta[property="og:site_name"]', "og:site_name"],
    ['meta[property="og:description"]', "og:description"],
    ['meta[name="description"]', "description"],
    ['meta[name="job:location"]', "job:location"],
    ['meta[name="job:employment_type"]', "job:employment_type"],
    ['meta[name="job:published_at"]', "job:published_at"],
  ];
  for (const [selector, key] of metaKeys) {
    const value = attr(selector, "content");
    if (value) meta[key] = value.slice(0, 400);
  }
  const canonical = attr('link[rel="canonical"]', "href");
  if (canonical) meta["canonical"] = canonical.slice(0, 2000);
  const documentTitle = (doc.title ?? "").toString().trim();
  if (documentTitle) meta["document:title"] = documentTitle.slice(0, 400);

  const pageUrl = new URL(href);
  const host = pageUrl.hostname.toLowerCase();
  const path = pageUrl.pathname;
  let provider: "GREENHOUSE" | "ASHBY" | "LEVER" | "RECRUITEE" | "LINKEDIN" | null = null;
  if (host === "boards.greenhouse.io" || host === "job-boards.greenhouse.io") provider = "GREENHOUSE";
  else if (host === "jobs.ashbyhq.com") provider = "ASHBY";
  else if (host === "jobs.lever.co") provider = "LEVER";
  else if (host.endsWith(".recruitee.com")) provider = "RECRUITEE";
  else if (host === "linkedin.com" || host.endsWith(".linkedin.com")) provider = "LINKEDIN";

  const matchId = (pattern: RegExp): string | null => {
    const match = path.match(pattern);
    return match?.[1] ? decodeURIComponent(match[1]) : null;
  };
  const externalId =
    provider === "GREENHOUSE"
      ? matchId(/\/jobs\/(\d+)(?:\/|$)/i)
      : provider === "ASHBY"
        ? matchId(/\/([0-9a-f]{8}-[0-9a-f-]{27,})\/?$/i)
        : provider === "LEVER"
          ? matchId(/\/[^/]+\/([0-9a-f-]{20,})\/?$/i)
          : provider === "RECRUITEE"
            ? matchId(/\/o\/([^/]+)\/?$/i)
            : provider === "LINKEDIN"
              ? matchId(/\/jobs\/view\/(?:[^/]*-)?(\d{6,})\/?$/i)
              : null;

  const firstText = (selectors: string[]): string | null => {
    for (const selector of selectors) {
      const value = text(doc.querySelector(selector));
      if (value) return value;
    }
    return null;
  };
  const firstHref = (selectors: string[]): string | null => {
    for (const selector of selectors) {
      const element = doc.querySelector(selector);
      const value = element?.href ?? element?.getAttribute?.("href");
      if (typeof value === "string" && value.trim()) return value.trim().slice(0, 2000);
    }
    return null;
  };
  const providerSelectors: Record<string, {
    title: string[];
    company: string[];
    location: string[];
    employmentType: string[];
    workplaceType: string[];
    description: string[];
    application: string[];
  }> = {
    GREENHOUSE: {
      title: [".job__title", ".app-title", "#header h1", "main h1", "h1"],
      company: [".company-name", ".job__company", "header .company", "[data-company-name]"],
      location: [".job__location", ".location", "[data-job-location]"],
      employmentType: ["[data-employment-type]"],
      workplaceType: ["[data-workplace-type]"],
      description: [".job__description", "#content", "[data-job-description]", "main", "article"],
      application: ["a[href*='#app']", "a[href*='/jobs/'][href*='application']"],
    },
    ASHBY: {
      title: ["[data-testid='job-title']", "main h1", "h1"],
      company: ["[data-testid='company-name']", "[data-testid='organization-name']", "header img[alt]"],
      location: ["[data-testid='job-location']", "[class*='location']"],
      employmentType: ["[data-testid='job-employment-type']"],
      workplaceType: ["[data-testid='job-location-type']"],
      description: ["[data-testid='job-description']", "[class*='description']", "main", "article"],
      application: ["a[href*='/application']", "a[href*='applicationForm']"],
    },
    LEVER: {
      title: [".posting-headline h2", ".posting-title h2", "main h1", "h1"],
      company: [".main-header-logo img[alt]", ".posting-headline .company"],
      location: [".posting-categories .location", ".posting-headline .location"],
      employmentType: [".posting-categories .commitment"],
      workplaceType: [".posting-categories .workplaceTypes", ".posting-categories .workplace-type"],
      description: [".posting-page .content", ".posting-description", ".section-wrapper", "main"],
      application: ["a.postings-btn", "a[href*='/apply']"],
    },
    RECRUITEE: {
      title: ["[data-ui='job-title']", "[data-testid='job-title']", "main h1", "h1"],
      company: ["[data-ui='company-name']", "[data-testid='company-name']", "header img[alt]"],
      location: ["[data-ui='job-location']", "[data-testid='job-location']", "[class*='location']"],
      employmentType: ["[data-ui='employment-type']", "[data-testid='employment-type']"],
      workplaceType: ["[data-ui='workplace-type']", "[data-testid='workplace-type']"],
      description: ["[data-ui='job-description']", "[data-testid='job-description']", ".job-description", "main", "article"],
      application: ["a[href*='/c/new']", "a[href*='/apply']"],
    },
  };
  const selected = provider ? providerSelectors[provider] : undefined;
  const titleFromDocument = documentTitle.includes(" @ ")
    ? documentTitle.slice(0, documentTitle.lastIndexOf(" @ ")).trim()
    : null;
  const companyFromDocument = documentTitle.includes(" @ ")
    ? documentTitle.slice(documentTitle.lastIndexOf(" @ ") + 3).trim()
    : null;

  const dom = {
    externalId,
    provider,
    title: (selected ? firstText(selected.title) : null) ??
      text(doc.querySelector(".top-card-layout__title, .topcard__title, " +
        ".jobs-unified-top-card__job-title, h1")) ?? titleFromDocument,
    company:
      (selected ? firstText(selected.company) : null) ??
      text(doc.querySelector(".topcard__org-name-link, .top-card-layout__card " +
        ".topcard__flavor-row a, .jobs-unified-top-card__company-name a")) ??
      text(doc.querySelector("[itemprop=hiringOrganization] [itemprop=name]")) ??
      attr("[data-company]", "data-company") ??
      text(doc.querySelector(".company")) ?? companyFromDocument,
    location:
      (selected ? firstText(selected.location) : null) ??
      text(doc.querySelector(".topcard__flavor--bullet, " +
        ".jobs-unified-top-card__bullet, .jobs-unified-top-card__primary-description-container")) ??
      text(doc.querySelector("[itemprop=jobLocation]")) ??
      attr("[data-job-location]", "data-job-location"),
    employmentType: (selected ? firstText(selected.employmentType) : null) ??
      text(doc.querySelector("[itemprop=employmentType]")),
    workplaceType: selected ? firstText(selected.workplaceType) : null,
    publishedAt: attr("time[datetime]", "datetime"),
    applicationUrl: selected ? firstHref(selected.application) : null,
    description:
      (selected ? firstText(selected.description) : null) ??
      text(doc.querySelector(".show-more-less-html__markup, .description__text, " +
        ".jobs-description__content, .jobs-box__html-content")) ??
      text(doc.querySelector("[itemprop=description]")) ??
      text(doc.querySelector("[data-job-description]")) ??
      text(doc.querySelector(".job-description")) ??
      text(doc.querySelector("main")) ??
      text(doc.querySelector("article")),
  };

  const bodyText = (doc.body ? (doc.body.innerText ?? "") : "").toString().slice(0, maxScan);
  const lower = (doc.title ?? "").toLowerCase() + " " + bodyText.slice(0, 20000).toLowerCase();
  const signals = {
    hasPasswordField: doc.querySelector('input[type="password"]') !== null,
    hasLoginForm:
      doc.querySelector('form[action*="login"], form[action*="signin"], form[action*="sign-in"]') !==
      null,
    challengeMarker:
      doc.querySelector(
        '#cf-challenge-running, .cf-challenge, iframe[src*="challenges.cloudflare.com"], ' +
          'script[src*="/cdn-cgi/challenge-platform/"]',
      ) !== null ||
      lower.includes("verify you are human") ||
      lower.includes("checking your browser") ||
      lower.includes("just a moment"),
    accessDeniedTitle:
      (doc.title ?? "").toLowerCase().includes("access denied") ||
      (doc.title ?? "").toLowerCase().includes("attention required"),
    bodyTextLength: bodyText.length,
  };

  const searchJobs: Array<{
    title: string | null;
    company: string | null;
    location: string | null;
    url: string | null;
  }> = [];
  const cards = doc.querySelectorAll(
    ".base-card, .job-search-card, li.jobs-search-results__list-item, " +
      ".jobs-search-results__list-item",
  );
  for (let i = 0; i < cards.length && searchJobs.length < 100; i++) {
    const card = cards[i];
    const link = card.querySelector(
      'a.base-card__full-link, a.job-card-container__link, a[href*="/jobs/view/"]',
    );
    searchJobs.push({
      title: text(card.querySelector(
        ".base-search-card__title, .job-card-list__title, " +
          ".job-card-container__link, h3, h4",
      )),
      company: text(card.querySelector(
        ".base-search-card__subtitle, .job-card-container__company-name, " +
          ".job-search-card__subtitle",
      )),
      location: text(card.querySelector(
        ".job-search-card__location, .job-card-container__metadata-item",
      )),
      url: link ? link.getAttribute("href") : null,
    });
  }

  return { finalUrl: href, jsonLdBlocks, meta, embeddedState, dom, signals, searchJobs };
}

function continueWithoutCredentials(route: Route): Promise<void> {
  const headers = { ...route.request().headers() };
  delete headers["authorization"];
  delete headers["cookie"];
  delete headers["proxy-authorization"];
  return route.continue({ headers });
}

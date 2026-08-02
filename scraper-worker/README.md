# JobPilot scraper-worker

An **optional** JavaScript-rendering fallback for JobPilot. It renders one
already-validated **public** vacancy URL with Crawlee + CloakBrowser (via
playwright-core) and returns bounded, typed JSON. It is stateless and narrow.

The worker **never**: connects to PostgreSQL, calls an LLM, calls Telegram,
creates applications, scores candidates, normalizes jobs as a Java replacement,
submits applications, or contacts employers/recruiters. Spring Boot owns all of
that; the worker only turns a URL into bounded fields.

## Contract

- `GET /health` — bounded `{status, component, version, browser}` only.
- `POST /v1/extract` — protected by a fixed internal header
  `x-jobpilot-worker-secret` (constant-time compared; ≥32 random bytes; no
  committed default; missing and wrong secrets return the same generic `401`).
- `POST /v1/search` — the same protected, bounded contract for public LinkedIn
  guest search pages. It returns at most `WORKER_MAX_SEARCH_RESULTS` normalized
  cards and never performs pagination or link discovery.

Request: `{ "requestId": "...", "url": "https://public.example/jobs/123", "expectedSource": "OPTIONAL" }`

Result statuses: `EXTRACTED`, `INSUFFICIENT_DATA`, `UNSUPPORTED`, `BLOCKED`,
`AUTH_REQUIRED`, `CHALLENGE_DETECTED`, `RATE_LIMITED`, `TIMEOUT`, `INVALID_URL`,
`FETCH_FAILED`. `EXTRACTED` requires a title, a company, a meaningful bounded
description, and a valid public URL. Responses never contain HTML, cookies,
storage, screenshots, headers, stack traces, fingerprints, proxy details,
filesystem paths, or internal exception messages.

## SSRF boundary

The worker is an independent SSRF boundary (Spring Boot also applies its Stage 2
policy first). It allows only `http`/`https` and rejects credentials, localhost
/`.local`/`.internal`, loopback, private, link-local, multicast, unspecified,
reserved and cloud-metadata ranges, IPv4 embedded in IPv6, obfuscated/numeric IP
forms, `file:`/`data:`/`javascript:`/`blob:`/`ftp:`, and internal container
service names. It resolves the hostname and rejects the URL if any resolved
address is prohibited. After navigation it walks Playwright's complete
`response.request().redirectedFrom()` chain, enforces `WORKER_MAX_REDIRECTS`,
DNS-screens every hop, and screens the final page URL again. Request interception
blocks prohibited destinations, downloads, WebSockets, media, and unnecessary
images/fonts, strips authorization headers, and blocks service workers. Each
render uses a fresh isolated context, so cookies never carry across operations. Application
DNS validation cannot fully eliminate a narrow DNS-rebinding race; production
network isolation remains required.

## Extraction order

1. schema.org `JobPosting` JSON-LD (object, array, `@graph`, multiple, malformed-safe);
2. known public embedded job state;
3. semantic DOM;
4. OpenGraph/meta as a limited fallback (never for the description).

Public LinkedIn guest detail pages use dedicated title/company/location/
description selectors after JSON-LD, and public guest search pages use dedicated
card selectors. Login/CAPTCHA/Turnstile/Cloudflare-challenge/access-denied pages
stop and return a typed conservative result. There is **no** CAPTCHA/challenge
bypass, login/session-cookie automation, pagination queue, proxy rotation, or
protected-portal scraping.

## CloakBrowser / Crawlee

`PlaywrightCrawler` → `playwright-core` → the CloakBrowser stealth-Chromium
**binary** (resolved from `CLOAKBROWSER_EXECUTABLE_PATH` or CloakBrowser's
`binaryInfo()`; never downloaded on a request path). Crawlee fingerprints are
disabled (`browserPoolOptions.useFingerprints=false`); a fresh isolated browser
context per extraction; `maxConcurrency=1`, `maxRequestsPerCrawl=1`,
`maxRequestRetries=0`; no enqueueing/link discovery; dialogs dismissed; popups
closed; `serviceWorkers="block"`; no screenshots/video/trace/HAR; bounded navigation/total timeouts.
Chromium's process sandbox is explicitly enabled: Playwright's and the vendor's
`--no-sandbox` / `--disable-setuid-sandbox` arguments are removed. A
non-queuing admission controller permits only
`WORKER_MAX_CONCURRENT_RENDERS` active operations (default `1`, maximum `4`);
excess requests receive `503 BUSY` immediately and are never stored in a waiter
queue.

## Configuration (environment)

`SCRAPER_WORKER_SHARED_SECRET` (required, ≥32 bytes), `PORT` (default 3000),
`WORKER_NAVIGATION_TIMEOUT_MS`, `WORKER_TOTAL_TIMEOUT_MS`, `WORKER_MAX_REDIRECTS`,
`WORKER_MAX_REQUESTS`, `WORKER_MAX_REQUEST_BODY_BYTES`,
`WORKER_MAX_DESCRIPTION_CHARS`, `WORKER_MAX_SEARCH_RESULTS`,
`WORKER_MAX_CONCURRENT_RENDERS`, `CLOAKBROWSER_EXECUTABLE_PATH` (optional override).
Startup fails closed on a missing/weak secret.

## Development

Requires Node 22.11.0 / npm 10.9.0.

```bash
npm ci
npm run build
npm run lint
npm run typecheck
npm test        # node --test; the browser test needs the CloakBrowser binary
npm run test:browser  # opt-in real CloakBrowser integration test
npm audit       # reports transitive findings; do NOT run "npm audit fix --force"
```

Prepare the CloakBrowser binary once (also done in the Docker image build):

```bash
node node_modules/cloakbrowser/dist/cli.js install
```

`npm audit` currently reports high-severity **transitive** findings through the
Crawlee → `got-scraping`/`adm-zip` and `@crawlee/jsdom`/`@crawlee/linkedom`
chains, with no safe non-breaking automatic fix. They are not forced.

## Dependencies and licensing

Pinned exactly: `crawlee` 3.17.0 (+ `@crawlee/playwright`/`@crawlee/core`),
`playwright-core` 1.61.1, `cloakbrowser` 0.4.12. The full `playwright` package is
**not** installed. **CloakBrowser** is a third-party stealth-Chromium package; it
downloads its patched Chromium from the vendor at image-build time — the binary
and any browser cache are never committed. Review CloakBrowser's license and its
Chromium redistribution terms before production use.

## Not committed

`node_modules/`, `dist/`, Crawlee storage, any browser binary/cache, `.env`,
screenshots, videos, traces, HAR files (all in `.gitignore`).

## Container boundary

Compose runs the worker as an unprivileged user with a read-only filesystem,
all Linux capabilities dropped except the `SYS_CHROOT` capability required by
Chromium's user-namespace jail, `no-new-privileges`, the official Playwright
seccomp profile (default deny plus `clone`/`setns`/`unshare` for user namespaces),
a bounded temporary mount,
explicit CPU/memory/PID/file-descriptor/shared-memory limits, and no host port.
It joins only `scraper-egress`; PostgreSQL joins only `database`, so the browser
container has no route or service membership to the database network.

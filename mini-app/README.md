# JobPilot Mini App

A Telegram Mini App for reviewing screened vacancies and tracking applications. It is
the primary review interface; the Telegram bot stays for notifications and quick actions.

Runs entirely on mock data. Spring Boot, PostgreSQL and Telegram credentials are not
needed and are never contacted.

## Local setup

```bash
cd mini-app
npm install
npm run dev          # http://localhost:5173
```

If Vite or oxlint fails with "Cannot find native binding", npm dropped the optional
platform packages ([npm/cli#4828](https://github.com/npm/cli/issues/4828)):

```bash
rm -rf node_modules package-lock.json && npm install
npm install --no-save @rolldown/binding-darwin-arm64 @oxlint/binding-darwin-arm64
```

## Scripts

| Script | What it does |
| --- | --- |
| `npm run dev` | Vite dev server |
| `npm run build` | Type check, then production build |
| `npm run typecheck` | `tsc -b`, strict mode |
| `npm run lint` | oxlint |
| `npm test` | Playwright: mock-mode smoke tests at 390×844, 430×932 and 1024×768, plus API-mode specs |

`npm test` starts its own dev servers — 5179 in mock mode, 5180 in API mode — and needs
no backend: the API specs fake Telegram in the page and the API at the network boundary.

## Mock mode and API mode

The build picks one `JobPilotRepository` implementation from `VITE_JOBPILOT_MODE`:

| Mode | Value | Implementation | Needs |
| --- | --- | --- | --- |
| Mock | unset, or `mock` (default) | `src/data/mockRepository.ts` | nothing |
| API | `api` | `src/data/httpRepository.ts` | JobPilot served same-origin, opened inside Telegram |

```bash
npm run dev                              # mock
VITE_JOBPILOT_MODE=api npm run dev       # API
```

`src/data/repository.ts` is the whole selector. Mock stays the default so `npm run dev`
and the smoke tests never need a server.

### Mock mode

Fixtures live in `src/data/sample.ts`: twelve vacancies and eight tracked applications,
shaped after the candidate profile the backend scorer is calibrated for — early-career
Java backend, Bucharest, remote-eligible from Romania. Company names are fictional; these
are fabricated postings and must not be attributable to a real employer.

Loads carry ~420 ms of artificial latency so the loading state is real. Append
`?mock=fail` to reject the initial load, or `?mock=fail-write` to reject mutations and
watch the optimistic change roll back.

### API mode

Requests go to same-origin relative paths under `/api/mini-app/v1`, so no CORS rule and
no preflight are involved. Every request carries the raw, still-signed
`Telegram.WebApp.initData` in an `X-Telegram-Init-Data` header — never a query parameter,
never a cookie, never `localStorage`, never a log line. Requests abort after 10s.

Outside Telegram, or when Telegram supplies no launch data, API mode **fails closed** with
"Open JobPilot from Telegram". It never falls back to mock data.

Three fields the mock has are honestly absent in API mode, because serving them would mean
an LLM call per vacancy or an endpoint that does not exist: `matchSummary` (null — the
detail sheet says so rather than inventing prose), `requirements` and `activity` (empty).

## Telegram environment adapter

`src/lib/telegram.ts` is the only module that touches `window.Telegram`. Outside
Telegram every method is a no-op and `available` is `false`, so the app runs unchanged
in a browser. It covers `ready`/`expand`, colour scheme plus change events, `openLink`,
haptics and the native back button.

Telegram's own SDK sets the `--tg-theme-*` and safe-area custom properties on `:root`,
which is why nothing in the adapter writes CSS variables.

To deploy inside Telegram, add the host SDK to `index.html`:

```html
<script src="https://telegram.org/js/telegram-web-app.js"></script>
```

It is omitted by default so the mock build and the test suite stay offline.

### Theming

`src/styles/tokens.css` defines three modes, selected by `data-theme` on `<html>`:

- `telegram` — surfaces follow Telegram's theme params, local values are fallbacks
- `light` / `dark` — local palette, Telegram's surface params ignored

The accent is Telegram's button colour whenever the app is hosted in Telegram and the
user has not overridden the theme, so the app takes on the colour of whichever Telegram
theme it opens in. Every tinted element resolves to `--accent`.

## Backend integration boundary

The whole seam is two methods in `src/data/types.ts`; nothing else talks to a server:

```ts
interface JobPilotRepository {
  load(): Promise<Snapshot>;
  setWorkflowStatus(jobId: number, status: WorkflowStatus): Promise<void>;
}
```

### Endpoints

| Method | Path | Purpose |
| --- | --- | --- |
| `GET` | `/api/mini-app/v1/snapshot` | Up to 50 active vacancies and the 20 newest tracked applications |
| `PUT` | `/api/mini-app/v1/jobs/{jobId}/workflow` | Body `{"status":"SAVED\|APPLIED\|DISMISSED\|UNREVIEWED"}`; idempotent |

Errors share the project's `{ "category", "message" }` shape. `httpRepository` maps
`category` to one of the messages in `FAILURE_MESSAGES`:

| HTTP | `category` | Shown as |
| --- | --- | --- |
| 503 | `MINI_APP_DISABLED` | Mini App is switched off |
| 401 | `UNAUTHENTICATED` | Sign-in details missing |
| 401 | `INVALID_AUTH` | Sign-in could not be verified |
| 401 | `EXPIRED_AUTH` | Session expired |
| 403 | `FORBIDDEN` | Account not allowed |
| 404 | `JOB_NOT_FOUND` | Vacancy no longer available |
| 409 | `INVALID_WORKFLOW` | Change was rejected |

The server answers malformed, tampered and wrongly-signed payloads identically, so no
response reveals which check rejected it.

### Server configuration

| Key | Env | Default | Notes |
| --- | --- | --- | --- |
| `jobpilot.mini-app.enabled` | `MINI_APP_API_ENABLED` | `false` | Off returns 503 from every Mini App route |
| `jobpilot.mini-app.allowed-user-ids` | `MINI_APP_ALLOWED_USER_IDS` | empty | Numeric Telegram **user** ids; empty denies everyone, and enabling without them fails startup |
| `jobpilot.mini-app.max-auth-age` | `MINI_APP_MAX_AUTH_AGE` | `1h` | Maximum 24h; 5 minutes of forward clock skew is tolerated |

Enabling also requires `TELEGRAM_BOT_TOKEN`, which signs the initData being verified.
Telegram user ids are not chat ids, so the Mini App keeps its own allow-list rather than
reusing `TELEGRAM_ALLOWED_CHAT_IDS`.

### Packaging

The production image builds and carries the Mini App itself; there is no separate
frontend host, no CDN and no static bucket:

```
Dockerfile
  node:22-alpine   npm ci → VITE_JOBPILOT_MODE=api npm run build → dist/
  maven            dist/ → src/main/resources/static/mini-app/ → mvn package
  temurin JRE      the jar only — no Node, no npm, no node_modules, no sources
```

`vite.config.ts` sets `base: '/mini-app/'` for builds and injects the Telegram host SDK
into `index.html`; both apply to `npm run build` only, so `npm run dev` and the Playwright
suite stay at the root and stay offline.

`MiniAppWebConfig` serves the result:

| Request | Answer |
| --- | --- |
| `/mini-app` | Redirect to `/mini-app/` |
| `/mini-app/` | `index.html` |
| `/mini-app/assets/**` | The hashed bundles; a miss is a real 404, never HTML |
| `/mini-app/<anything else>` | `index.html`, so a refresh is not a raw 404 |
| `/api/mini-app/v1/**` | The API — a controller mapping, matched before any resource handler |

The fallback is scoped to `/mini-app/**`, so it can never answer `/health`, `/internal/**`
or an API route. `MiniAppRoutingTest` boots a real container and pins that boundary.

### Deployment expectations

- **Same origin.** Frontend and API come out of one Spring Boot container, so relative
  paths just work. No CORS configuration exists, deliberately.
- **HTTPS.** Telegram only loads Mini Apps over HTTPS. The container listens on
  `127.0.0.1:8080`; terminating TLS in front of it is a separate, later step.
- **BotFather is not part of this change.** The menu button would point at
  `https://<host>/mini-app/`; no bot configuration was created or modified.
- **Azure production is unchanged.** `docker-compose.prod.yml` forwards
  `MINI_APP_API_ENABLED`, `MINI_APP_ALLOWED_USER_IDS` and `MINI_APP_MAX_AUTH_AGE`, and the
  flag ships `false` in `.env.prod.example`. The assets are served either way; the API is
  not, until the flag and an allow-list are set.

### Already served by the backend

| Frontend | Backend |
| --- | --- |
| `Job.workflowStatus` | `WorkflowStatus`, via `JobReviewService.save/applied/dismiss/reset` |
| `Job.score`, `Job.band` | `JobScore`, `ScoreCard`, `ScoreBand` |
| `Job.strengths`, `Job.risks` | `ScoreCard.strengths` / `.risks` |
| `Job.disposition` | `ScreeningDisposition` (`REJECT` is never persisted) |
| `Job.requirements` | `JobAnalysisData.mustHaveRequirements` |
| `Job.remoteType`, `seniority`, `employmentType` | `Job` entity columns |
| `Job.canonicalUrl` | `JobDetailView.canonicalUrl`, filtered by `TelegramMessageRenderer.safeUrl` |
| `Job.diagnostics.screeningReasons` | `JobReasonView` (stage / code / message) |
| `Application.*` | `ApplicationView`, `ApplicationStatus` |
| `ReviewStats` | `JobReviewStats` |

`BAND_THRESHOLDS` mirrors the 55 / 70 / 85 cut-offs in `JobMatchingService`. The score
rail draws its ticks from them, so those must stay in step.

### Not yet served — needs a new endpoint or projection

| Frontend field | Closest existing value | Note |
| --- | --- | --- |
| `Job.matchSummary` | `JobAnalysisData.roleSummary` | LLM analysis is per-job and on demand; loading the queue must not trigger one. Sent as `null`. |
| `Job.activity` | `ApplicationStatusHistory` + workflow transitions | Two sources today, one feed in the UI. Sent as `[]`. |
| `Job.requirements` | `JobAnalysisData.mustHaveRequirements` | Same on-demand LLM analysis. Sent as `[]`. |
| `Application.score` | — | The applications table stores no score; the client borrows it from the matching vacancy when that vacancy is still in the snapshot, and shows `—` otherwise. |
| Snapshot paging | `JobQueuePage` (paged, per queue) | The Mini App reads one bounded snapshot of 50. Add paging if a real queue outgrows it. |
| Notification preference | — | The Settings toggle is local state only. |

## Structure

```
src/
  App.tsx            shell, screen switching, theme and preferences
  components/        ScoreRail, JobRow, BottomNav, UndoToast, States, icons
  features/          Discover, Review, Saved, Applications, Settings, JobDetails, JobCard
  lib/               telegram adapter, formatters, useJobPilot store
  data/              types, repository selector, mock + HTTP repositories, sample data
  styles/            tokens.css, app.css
```

`main.tsx` and `App.tsx` sit at the root of `src/` rather than in an `app/` folder —
two files did not warrant a directory.

## Notes

- No router, no state library, no CSS framework, no component library.
- The details sheet is a native `<dialog>`: focus trap, Esc, inert background and
  `::backdrop` come from the platform. Its transitions are CSS
  (`@starting-style` + `allow-discrete`), not JavaScript.
- Progressive disclosure uses native `<details>`.
- Motion is used in five places only: the review card swap, the undo toast, the score
  rail fill, the nav indicator and the applications filter underline. `MotionConfig`
  is set to `reducedMotion="user"`, and the Settings toggle forces `"always"`.
- Networking is `fetch` plus `AbortSignal.timeout` — no HTTP client dependency.
- A rejected write rolls the optimistic change back and says so. A decision carries a
  token so a late rejection cannot undo something the user already undid.

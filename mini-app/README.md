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
  setWorkflowStatus(jobId: number, status: WorkflowStatus): Promise<Snapshot>;
}
```

### Endpoints

| Method | Path | Purpose |
| --- | --- | --- |
| `GET` | `/api/mini-app/v1/snapshot` | Separate bounded review (50), saved (50), and application (20) projections with authoritative totals |
| `PUT` | `/api/mini-app/v1/jobs/{jobId}/workflow` | Body `{"status":"SAVED\|APPLIED\|DISMISSED\|UNREVIEWED"}`; idempotent |

Every idempotent workflow mutation returns the new authoritative snapshot in its response.

### Durable workflow invariant

`SAVED` and `APPLIED` are committed through one Spring transaction. The workflow row and
the application tracker transition therefore both commit, or both roll back. Tracker
updates continue to use `ApplicationTransitionPolicy`, so idempotency, legal transitions,
timestamps and status history have one existing source of truth rather than a Mini App
special case.

`MiniAppWorkflowService` owns that transaction and the conflict retry around it. Each
attempt runs in a `REQUIRES_NEW` transaction covering the whole mutation — workflow,
application and snapshot — so a rolled-back attempt is discarded together with its
persistence context before the next one begins. Retrying *inside* a caller's transaction
cannot work: the failed attempt has already marked that transaction rollback-only, so the
retry can only fail again, surfacing as `UnexpectedRollbackException` or a Hibernate
`AssertionFailure` rather than a recoverable outcome. `ApplicationTrackerService` keeps its
own retry for standalone callers — the Telegram bot and `ApplicationController` — and
offers `transitionInCurrentTransaction` for callers that already hold a transaction.
Either way an exhausted retry raises `ApplicationTrackingException(CONFLICT)`, which the
controller already maps to `409 INVALID_WORKFLOW`.

Every successful mutation returns a fresh snapshot from that same transaction. The client
may move a card optimistically, but then replaces all bounded rows, totals and status
counts with the server response. A reload or a second browser context therefore observes
the same PostgreSQL state.

`UNREVIEWED` deliberately resets only the review workflow. It never deletes an existing
application or its history. Consequently, Undo after Save restores the vacancy to the
review queue while the tracked Saved application remains visible. Redesigning that
product behaviour and the current single global pending-action token belongs to P0-B.

### Undo boundary in P0-A

Save and Dismiss are undoable. **Applied is not**, and no Undo is offered after it —
selecting Applied also clears an Undo still armed for an earlier vacancy. The reason is
structural rather than cosmetic: `ApplicationTransitionPolicy` has no `APPLIED → SAVED`
edge, so an Undo could only ever be refused, leaving the client showing a state the server
had rejected. That edge is deliberately **not** added here, because reversing an
application raises questions this phase does not answer — who owns the application row,
and what becomes of its status history. Nothing is deleted, rewritten or compensated.

Every Undo that remains possible reconciles before it complains: when the write is
refused, the client re-reads the authoritative snapshot and reconciles jobs, Saved,
Applications, totals and counters, and only then shows the typed failure. The optimistic
rollback is never left on screen.

Deterministic Applied reversal — an explicit reversal command with defined ownership of
the application row and its history — belongs to P0-B.

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
| `Application.*` | `ApplicationView`, `ApplicationStatus`; active vacancy details are bulk-projected for the bounded application page |
| `ReviewStats` | `JobReviewStats` |

`BAND_THRESHOLDS` mirrors the 55 / 70 / 85 cut-offs in `JobMatchingService`. The score
rail draws its ticks from them, so those must stay in step.

### Not yet served — needs a new endpoint or projection

| Frontend field | Closest existing value | Note |
| --- | --- | --- |
| `Job.matchSummary` | `JobAnalysisData.roleSummary` | LLM analysis is per-job and on demand; loading the queue must not trigger one. Sent as `null`. |
| `Job.activity` | `ApplicationStatusHistory` + workflow transitions | Two sources today, one feed in the UI. Sent as `[]`. |
| `Job.requirements` | `JobAnalysisData.mustHaveRequirements` | Same on-demand LLM analysis. Sent as `[]`. |
| `Application.score` | — | The applications table stores no score; active vacancy details carry the current score for the bounded application page, and the UI shows `—` when that job is no longer active. |
| Full snapshot paging | `JobQueuePage` (paged, per queue) | Review and Saved return at most 50 rows and Applications at most 20. Each projection carries `total`, `limit` and `truncated`; Review offers an explicit next-batch reload when its bounded window is exhausted, while general interactive pagination remains deferred. |
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

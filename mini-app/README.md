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
| `npm test` | Playwright smoke tests at 390×844, 430×932 and 1024×768 |

`npm test` starts its own dev server on port 5179.

## Mock mode

`src/data/repository.ts` implements `JobPilotRepository` against the fixtures in
`src/data/sample.ts`. Twelve vacancies and eight tracked applications, shaped after the
candidate profile the backend scorer is calibrated for: early-career Java backend,
Bucharest, remote-eligible from Romania. Company names are fictional — these are
fabricated postings and must not be attributable to a real employer.

Loads carry ~420 ms of artificial latency so the loading state is real. Workflow
mutations are applied optimistically in the client; the repository call only models the
round trip.

Append `?mock=fail` to the URL to make the initial load reject and see the error state.

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

Replacing the mock means implementing two methods in `src/data/types.ts`:

```ts
interface JobPilotRepository {
  load(): Promise<Snapshot>;
  setWorkflowStatus(jobId: number, status: WorkflowStatus): Promise<void>;
}
```

Nothing else in the app talks to a server.

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
| `Job.matchSummary` | `JobAnalysisData.roleSummary` | LLM analysis is per-job and on demand; the review queue needs it inline. |
| `Job.activity` | `ApplicationStatusHistory` + workflow transitions | Two sources today, one feed in the UI. |
| `Snapshot` | `JobQueuePage` (paged, per queue) | The app loads one snapshot; a real backend should page. |
| Notification preference | — | The Settings toggle is local state only. |

No backend code was written or modified for this app.

## Structure

```
src/
  App.tsx            shell, screen switching, theme and preferences
  components/        ScoreRail, JobRow, BottomNav, UndoToast, States, icons
  features/          Discover, Review, Saved, Applications, Settings, JobDetails, JobCard
  lib/               telegram adapter, formatters, useJobPilot store
  data/              types, mock repository, sample data
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

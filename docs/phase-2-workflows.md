# Phase 2: candidate-scoped workflows

## Outcome and preflight

Vacancies remain shared in `jobs`. Workflow decisions, applications, notes, follow-up dates, history, revision rows and mutation/undo records now belong to a candidate. Two invited Telegram accounts can save, dismiss, apply to and undo changes on the same vacancy independently. Beta screens describe a shared catalogue and do not display the legacy owner's match scores, strengths, risks or screening reasons.

The mandatory independent Phase 1 review inspected the actual uncommitted diff, schema constraints, authentication, Telegram boundaries, frontend generations and rollback harness. It found no Critical or High issue. It identified a Medium caveat: disabling identity previously reopened internal routes if the proxy forwarded them. Phase 2 closes that case: private routes are denied whenever either identity or the public Mini App is enabled. A real PostgreSQL/MVC regression verifies disabled v2, working owner v1, denied beta v1 and denied internal application/document/source endpoints together. A runtime with both public surfaces disabled retains its existing trusted internal-network administration contract; continue restricting proxy routes.

The final independent review found no Critical or High security issue. Its one Medium functional finding was missing application-ID discovery. The fix adds `GET /v2/jobs/{jobId}/application`, scoped to the authenticated candidate. The ownership tests now obtain application IDs through this API, not direct database reads. The regression returned 404 before the fix and passes afterward.

## Ownership and consistency

- Every v2 entry point receives an immutable `AuthenticatedCandidate` and revalidates its session through `TelegramIdentityService.me` before private storage access. Null, expired, revoked, forged or no-longer-admitted identities cannot become the default owner.
- Candidate-scoped repositories select by candidate plus job/application/token identity. Foreign application IDs return the same 404 as missing IDs. Foreign undo tokens return the same 409 stale result as unusable tokens. No ownership parameter is accepted in the URL or query string.
- Legacy repository methods deliberately select the `default` candidate. Legacy insertion defaults name that identity explicitly in SQL. New-user writes always set the validated candidate ID. No request thread context or `RuntimeCandidateContext` fallback selects a beta owner.
- Mutations use the existing READ COMMITTED, transaction-owned retry and durable ledger mechanics. Each candidate's revision row is inserted idempotently, then locked until commit. Keys and revisions are unique within that candidate; the same key in another account is independent.
- Snapshot service transactions remain REPEATABLE READ. Neutral beta queries do not join `job_scores` or read personal eligibility reasons. Catalogue ordering is public recency and job ID; all nonexpired jobs are eligible for this neutral view, including jobs rejected by the old owner's screening.
- Undo retains provenance and freshness checks. Changes to application notes/follow-up/status move the optimistic version and invalidate older undo. Cross-candidate workflow/application/history fingerprints are rejected. The status path resolves the owned job ID as a scalar, locks the job, then loads/locks the owned application, preventing both lock-order inversion and stale entity overwrites during legacy writes.

The beta catalogue/review and Saved windows contain at most 50 rows each; Applications contains at most 20. Complete counts, limits and truncation flags accompany the windows. There is no personalized matching or full pagination in this phase.

## V17 migration

`V17__candidate_workflow_ownership` is a Java Flyway migration with checksummed PostgreSQL and H2 dialect resources under `db/ownership/`. H2 exists only for the established fast regression suite; PostgreSQL containers provide the ownership, migration and concurrency guarantees. V1–V16 are unchanged.

| Storage | Change |
|---|---|
| `job_workflow_state` | Candidate FK, unique `(candidate_id, job_id)`, surrogate primary key. Existing job IDs remain unchanged and initialize the surrogate identity. Versions, status, notes and timestamps are preserved. |
| `applications` | Candidate FK and per-candidate job uniqueness; original application IDs preserved. |
| `application_status_history` | Owner backfilled from its application; composite candidate/application FK prevents foreign history associations. |
| `mini_app_state` | Candidate primary key replaces global singleton identity. Original `id=1` and revision are retained; new candidates have separate revision rows. |
| `mini_app_mutation` | Candidate FK, per-candidate mutation key/revision uniqueness, same-owner created-history and reversal references. Existing mutation IDs, tokens, states and provenance remain. |
| Documents | Owner derived from immutable candidate profile; composite profile/job/document FKs prevent cross-candidate resume, cover-note and application associations. PostgreSQL insert triggers preserve trusted legacy document insertion behavior. Selection services also query by owner before reading/selecting document metadata. |

Historical private rows are assigned to the established `default` candidate by stable key, irrespective of beta registration order. Documents derive their candidate from the referenced profile. No historical delivery rows or bot offsets are reassigned. Invalid ownership relationships abort migration rather than being silently repaired.

This migration takes exclusive schema locks, adds/backfills columns, replaces uniqueness/primary keys and validates composite foreign keys. It must run in a separately approved maintenance window with bounded lock waits and a tested backup/restore procedure. Synthetic execution time does not estimate production lock duration. No destructive downgrade script is supplied.

## API contracts

All paths below use `/api/mini-app/v2`; all workflow endpoints require `Authorization: Bearer <opaque-session>`, omit cookies and return `Cache-Control: no-store`. Phase 1 exchange and `/me` contracts are unchanged. Queries cannot carry credentials or candidate ownership. JSON bodies are accepted only on the declared mutation paths; authentication exchange still accepts only its signed header and no body.

| Method/path | Body/result |
|---|---|
| `GET /snapshot` | V1-compatible bounded snapshot shape, with candidate-specific state/revision/counts and neutral catalogue fields. `score=0`, `band=null`, `strengths=[]`, `risks=[]`, `disposition=REVIEW` are transport compatibility values, not recommendations. |
| `PUT /jobs/{jobId}/workflow` | `{status, mutationId}`; existing operation-result contract, no snapshot. |
| `POST /undo` | `{mutationId, undoToken}`; existing operation-result contract, no snapshot. |
| `GET /jobs/{jobId}/application` | Current candidate's `ApplicationView`, including internal `applicationId`, or 404. This discovers the ID needed below. |
| `GET /applications/{id}` | Owned application details, notes and follow-up dates, or 404. |
| `GET /applications/{id}/history` | Owned ordered status history, or 404. |
| `PUT /applications/{id}/notes` | `{notes}`; `{application, changed}`. Existing normalization and 2,000-character bound apply. |
| `PUT /applications/{id}/follow-up` | `{date: "YYYY-MM-DD"}` or `{date: null}`; `{application, changed}`. |
| `PUT /applications/{id}/status` | `{status, interviewAt?, rejectionReason?}`; `{application, changed}`. Existing transition policy applies. |

Workflow mutation IDs are 8–64 characters from `[A-Za-z0-9_-]`. Replaying the same candidate/key/payload returns the recorded outcome; a conflicting payload returns 409. Undo is opaque, candidate-scoped and validated against current durable state. Other failures retain typed 400/401/403/404/409/503 responses.

The v1 API retains its response shape and strictly requires the explicitly configured signed legacy owner. Its Discover, Review, Saved, Applications, mutation and undo behavior remains. Legacy Telegram commands/callbacks and both notifier implementations retain Phase 1's owner/private-chat boundary. There is no notification redesign or second poller.

## Frontend and demonstration

The original owner uses the existing v1 UI. Beta uses a small neutral workspace with Catalogue, Saved and Applications. It reuses the existing per-job queue, stable mutation IDs, read-generation reconciliation and undo mechanics. Notes/history/follow-up and later application status changes are available through the scoped API; the beta UI's current tracking actions are save/dismiss/applied/undo.

Bearer credentials stay in memory. A repository captures its API mode, session, signed launch and generation for its lifetime. Checks before requests and after asynchronous completions prevent old requests or automatic recovery from using a new account's credentials or invalidating its screen. Pending row controls are disabled; unresolved operations remain recoverable even if their vacancy leaves Saved. A fresh signed launch reloads persisted account state; reusing a consumed launch requires reopening Telegram.

The local disposable rehearsal exercised these actions through both HTTP and two actual browser contexts, with no API response stubs:

| Action on one shared vacancy | Account A | Account B |
|---|---|---|
| A saves | Saved | Unreviewed |
| B dismisses | Saved | Dismissed |
| A marks applied | Applied | Dismissed |
| A undoes apply | Saved | Dismissed |
| A reopens with a fresh signed launch | Still Saved | Still Dismissed |

To reproduce the browser portion manually, use a **disposable loopback** instance with the synthetic test bot token `123456:test-bot-token-not-a-real-secret`, owner `4242`, invited beta users `9002,9003`, identity/registration enabled, scheduler/Telegram polling/notifications disabled. Seed one nonexpired public job titled `Browser shared vacancy` and no existing beta workflow/application state. From `mini-app`, run `node tests/real-backend-smoke.mjs http://127.0.0.1:<port>` with the installed Playwright browser path. The script opens the actual frontend, uses synthetic signed Telegram SDK data, forwards requests to the real API, checks the table above and confirms empty browser credential storage. It accepts only a loopback backend. Never use this synthetic configuration against production.

## Verification evidence

Local evidence is under `/private/tmp/jobpilot-phase2/`:

Final full verification on 19 October 2026: Java unit **1,216 passed / 0 failed / 1 existing live smoke skip** (1,217 total); PostgreSQL integration **131 passed / 0 failed / 0 skipped**; Playwright **236 passed / 0 failed or flaky / 4 existing desktop touch skips**. Type checking, lint and the API production build passed. The standalone compatibility/browser rehearsal passed **14 checks**.

- `final-verify.log`: complete Java verification, including existing authentication, migration, Telegram, notification, application, snapshot and consistency suites.
- `Phase2IsolationIT`: ten PostgreSQL/MVC cases for neutral shared catalogue, independent states/apps, private notes/history/follow-up, foreign IDs/undo, separate key/revision sequences, 16 concurrent mutations, eight concurrent duplicate deliveries, fresh-session persistence, owner v1 compatibility and anonymous/expired denial. The forced status lock-order test failed with PostgreSQL row-lock refusal before the scalar/lock-order fix and passes afterward.
- `V17WorkflowMigrationIT`: deployed V13 fixture through V17; compares every original row/column, checks V1–V16 checksums, repeat migration and application startup, beta-before-owner ownership, original IDs/versions/timestamps/notes/history/delivery/bot data, and database-level foreign document/history rejection. The previously rejected test-file edit was confirmed absent, then created and executed.
- `PublicMiniAppBoundaryIT`: disabled identity cannot reopen internal application/document/source routes while the public Mini App runs. RED→GREEN evidence is in `public-boundary-red.log` / `backend-pg-green.log`.
- `application-discovery-red.log`: review finding reproduced as 404 before adding the candidate-scoped job→application lookup. Tests now consume that public lookup.
- `frontend-playwright-final.log`: 236 passed, four existing desktop touch-test skips, zero failed or flaky tests. Type checking, lint and the API production build passed. Browser regressions cover two accounts, expiry/replay, three delayed identity-switch outcomes, pending actions and ambiguous write recovery.
- `real-browser-backend.log` and `rollback-demo-rehearsal.log`: actual browser→API→PostgreSQL workflow and previous-binary rehearsal. All 14 rehearsal checks passed; the unsafe rollback behavior is an expected compatibility finding, not a claimed safe rollback.
- CI report validation now requires the Phase 2 isolation, migration and public-boundary suites in addition to the Phase 1 required suites. Missing suites, failures, errors or skips fail the guard.

One earlier parallel verification run failed before authentication tests started because the local disposable PostgreSQL connection failed during SSL setup. Evidence is retained in `parallel-database-connection-failure.log`; the isolated authentication suite passed 14/14 and the subsequent complete serial Java run passed all 131 integration tests. No test was disabled or application authentication weakened to obtain the final result.

## Rollback boundary and GO / NO-GO

The preserved **actual Phase 1 binary** was started against disposable V17. Before beta writes it read owner history and performed an owner write successfully. After a second candidate had independent rows, its owner snapshot returned **500**, even with identity admission disabled and sessions revoked. Global lookups and singleton revision assumptions are incompatible with the expanded data. Removing beta access does not remove that schema/data incompatibility.

Therefore **do not roll back to Phase 1 after beta writes**. Do not delete beta rows or drop V17 to simulate compatibility. A release needs a verified candidate-aware rollback binary or forward-fix strategy plus tested managed-database recovery. Keep beta routes/admission closed during recovery and retain all users' state. The current candidate-aware binary preserved the owner's workflow after multi-user writes in the rehearsal.

The older V13-era production binary was not certified against V17. The successful owner-only Phase 1 rehearsal does not authorize deploying or rolling back that older production binary.

Local completion is **GO** with the recorded final full suites green. Production release and Phase 1 rollback readiness are **NO-GO** pending a separately approved compatibility/recovery release review. No Azure connection, production credential/database/configuration access, deployment, push, commit, public registration enablement, ATS change, Phase 3 work, onboarding or personalized matching was performed.

## Changed-file map

Phase 2 extends the existing application/workflow entities, services and repositories under `applications/`, `jobreview/` and `miniapp/`; document selection, document entities and repositories under `resume/`; and `identity/IdentityWebBoundary`. New backend files are `CandidateWorkflowService`, `CandidateSnapshotQuery`, `CandidateWorkflowController`, Java migration `V17__candidate_workflow_ownership` and its two `db/ownership/` resources. Existing V16 migration assertions are bounded to V16 before current startup advances V17.

New tests are `CandidateWorkflowBoundaryTest`, `Phase2IsolationIT`, `PublicMiniAppBoundaryIT`, `V17WorkflowMigrationIT`, `beta.api.spec.ts` and the opt-in `real-backend-smoke.mjs`. Frontend changes cover `account.ts`, `httpRepository.ts`, `AccountGate`, `BetaWorkspace`, shared `useJobPilot`/repository setup, styles and identity browser tests. The CI integration-report guard adds the new required suites. All pre-existing uncommitted Phase 1 work remains in the same worktree and branch.

# Phase 1: account identity and the legacy owner boundary

Phase 1 registers Telegram identities and provides account-only access. Shared vacancies, scoring, applications, saved state, undo, ingestion, and candidate document facts remain legacy workflows. New accounts cannot use them.

## Configuration and admission

| Environment variable | Default | Meaning |
|---|---|---|
| `IDENTITY_API_ENABLED` | `false` | Enables v2 authentication and account routes; blocks internal HTTP controllers and source health routes in this public runtime |
| `IDENTITY_REGISTRATION_ENABLED` | `false` | Permits invited beta users to exchange credentials; when false, only the owner can exchange |
| `LEGACY_TELEGRAM_USER_ID` | unset | Explicit verified numeric owner of the existing `default` candidate |
| `IDENTITY_BETA_ALLOWED_USER_IDS` | empty | Comma-separated positive numeric Telegram user IDs allowed into account-only beta |
| `IDENTITY_SESSION_LIFETIME` | `15m` | Absolute session TTL; positive, at most 15 minutes |
| `IDENTITY_CREDENTIAL_EPOCH` | `1` | Operator-controlled session credential epoch; changing it invalidates existing sessions |

The owner must be explicitly configured before enabling either identity or legacy interactive surfaces in this build. For v1, the owner must also remain in `MINI_APP_ALLOWED_USER_IDS`; no allowlist is expanded automatically. Legacy command chat/user IDs must both be the owner's private user ID. Group-based interactive workflows are rejected. A persisted default-candidate binding that disagrees with configuration prevents startup. Configuration cannot prove that an unbound ID belongs to the human owner: an operator must verify that ID independently before initial enablement.

The registration flag does not weaken the legacy owner boundary. V1 accepts only correctly signed initData for the explicit owner, even with registration disabled. Commands/callbacks require that owner and a private chat. Review notifications and the legacy excellent-match/digest notifier send candidate explanations only to the owner's private destination. Existing non-owner/group/channel destinations are suppressed; their historical delivery rows remain untouched. Configure the correct private destination in a separately approved release.

## HTTP contract

`POST /api/mini-app/v2/auth/telegram` carries raw signed launch data exclusively in `X-Telegram-Init-Data`, with no query parameters or request body. Successful exchange returns `{accessToken, expiresAt}`. The server validates Telegram's HMAC, canonical payload, numeric user ID, five-minute launch age, and at most 30 seconds future skew. Usernames and client-provided account IDs play no authorization role.

`GET /api/mini-app/v2/me` requires `Authorization: Bearer <opaque-token>` and accepts no query parameters or body. Its response contains `accountId` (string), `accountState`, `onboardingRequired`, `legacyOwner`, and `sessionExpiresAt`. Phase 1 accounts report `ONBOARDING_REQUIRED`; the legacy owner can continue the existing workspace. No profiles, notes, job scores, histories, document metadata, or other users' data are returned.

Sessions use 32 cryptographically random bytes. Only SHA-256 hashes are stored. Responses use `Cache-Control: no-store`. No cookies, URL credentials, localStorage, sessionStorage, raw initData persistence, or token logging are used. The service revalidates session ownership, expiry, revocation, credential scope, and current admission policy when consuming the immutable principal. Server-side revocation is available through `TelegramIdentityService.revoke`; there is no new public administration endpoint.

Launch exchange is single-use, including concurrent requests and alternate URL encodings. Replay returns `409 AUTH_LAUNCH_REPLAYED`. Idempotent registration means independent valid launches for one Telegram ID resolve to one candidate; it does not mean issuing another token for a spent launch. A reload or ambiguous failed exchange requires a fresh Telegram launch. The frontend never silently retries an exchange or substitutes mock data. When the server explicitly reports `503 IDENTITY_DISABLED`, it retains the owner-protected v1 workspace behavior.

The frontend coalesces StrictMode initialization, loads `/me` before mounting the workspace, shows an account-only placeholder for beta users, clears content at expiry, and resets the workspace on a changed launch. Each workspace binds its HTTP operations and retries to the original account generation. Responses from an old launch cannot trigger further network operations or invalidate the new account. Reopening with fresh Telegram launch data permits a new exchange.

Authentication errors are typed: `401` invalid/missing/expired credentials, `403` uninvited/conflicting owner, `409` consumed launch, `429` exchange rate limit, `503` disabled feature. Malformed parameter/body/header combinations receive `400`. Limits are 120 exchange attempts/minute per process before signature work and 60 successful exchanges/minute per verified user, enforced under the database registration lock. The global limiter is deliberately single-instance; coordinate it before adding replicas.

## Persistence and transaction guarantees

V16 only adds `telegram_identities`, `auth_sessions`, and `telegram_auth_exchanges`, with indexes, owner FKs, unique Telegram/candidate mappings, unique session hashes, and unique consumed-launch fingerprints. No applied migration or legacy workflow table changes. A PostgreSQL transaction advisory lock serializes each Telegram user's registration across processes. Candidate, identity, session, and launch consumption commit atomically. The default candidate row is separately locked for deliberate owner binding; every other user gets an empty candidate with an opaque generated stable key.

Rotating the bot token or credential epoch invalidates sessions through credential scope checks. A session-epoch change does not reset consumed-launch protection. Removing a beta invitation or disabling registration also makes that user's sessions unusable. Replay markers must remain at least through their acceptance window. Expired session/exchange rows contain only hashes, IDs, and timestamps; a separate maintenance policy is required before sustained public volume. Phase 1 introduces no external queue/cache or background identity poller.

## Verification

Use Java 21 and Node 22. `./mvnw -Dapi.version=1.44 verify` runs Java and disposable PostgreSQL tests. `python3 scripts/check-integration-results.py target/failsafe-reports` rejects missing or skipped required integration suites. Frontend checks are `npm ci`, `npm run typecheck`, `npm run lint`, `VITE_JOBPILOT_MODE=api npm run build`, and `npm test` after installing Playwright Chromium.

`IdentityBoundaryIT` covers live MVC routing, two accounts, concurrent registration, replay, malformed credentials, expiry/revocation, service principal checks, and denial of legacy/internal routes. `V16IdentityMigrationIT` seeds synthetic V13 profiles, jobs, application history, workflow/undo state, delivery rows, and bot state; compares every original column/row across V14–V16 and application startup; validates stored checksums and new ownership constraints. No test connects to Azure or uses real credentials.

### Local results, 19 October 2026

- Maven verification: 1,215 unit tests passed; the existing live ATS smoke test stayed skipped. All 119 PostgreSQL integration tests executed and passed. The CI report guard also rejected synthetic missing, skipped, and failed reports.
- Frontend: 229 tests passed, zero failures or flaky tests, four existing desktop touch-test skips. Seven identity browser cases include held owner requests completing successfully, failing authorization, and losing their connection after a new launch; none can affect the new account or retry with its credential. Type checking, lint, and the API production build passed.
- Disposable V13→V16 migration preserved all seeded legacy rows and original column values. The unchanged `da4d2c9` baseline then started healthy against V16 with an empty beta account and revoked sessions. Its owner snapshot preserved saved/application records, beta access was denied, and all 16 migration checksums remained valid: five rollback checks passed.
- Independent security review found one important frontend launch race. Regression tests reproduced all three completion paths before the fix and passed after operations were bound to their original launch. No other Critical or Important findings were reported.

Evidence logs are local under `/private/tmp/jobpilot-phase1/`: `final-verify.log`, `final-playwright-results.json`, `generation-red.log`, `generation-green.log`, and `rollback-rehearsal.log`. Temporary evidence is not a release artifact; rerun the documented checks for a release candidate.

## Release boundaries — managed Azure PostgreSQL

No deployment is part of this implementation. Production remains the existing single application container connected to Azure Database for PostgreSQL. Do not launch the repository's local PostgreSQL service as a production prerequisite.

Before a separately authorized release:

1. Verify the owner ID, candidate key, current V13 history/checksums, external database URL/TLS requirements, and private notification destination without copying secrets into logs or documentation.
2. Validate backups and restore recovery for the managed database and private document artifacts. Rehearse V13→V16 against an isolated restored database under controlled access.
3. V14 and V15 require exclusive locks on `candidate_profiles`; drain relevant traffic and bound lock waits. V16 creates new tables without rewriting legacy workflow tables. Synthetic execution time is not a production estimate.
4. Keep registration/API flags off initially; explicitly configure the owner before starting this build's legacy interactive surfaces. Rehearse v1 and notifications with synthetic credentials first.
5. Restrict Caddy to `/mini-app/**`, `/api/mini-app/v1/**`, and the intended v2 endpoints. Do not publish `/internal/**`, `/api/sources/**`, or a broad `/api/**` proxy. Application-level denial is additional protection when identity is enabled; source-IP checks would not distinguish Caddy from a local administrator.
6. Keep one scheduler/poller instance. Enable v2 for the owner, then invited account-only users. Do not add beta users to legacy chat or workflow allowlists.
7. Before application rollback, close v2 admission/routes and revoke sessions. Rehearse rollback to owner-configured `da4d2c9` on the expanded schema; Phase 1 creates no extra active profiles. Do not assume V13-era `7134d76` can safely create profiles after V14. Retain V16 and forward-fix data; do not drop identity/history tables as routine rollback.

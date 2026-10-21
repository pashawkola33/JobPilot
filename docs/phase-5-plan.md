# Phase 5 Personalized Telegram Notifications Implementation Plan

> **For agentic workers:** Use superpowers:subagent-driven-development task by task; no commits. Preserve this durable plan/ledger and all prior evidence.

**Goal:** Implement opt-in, candidate-owned private Telegram notifications with bounded delivery and recovery safety.
**Architecture:** Additive V20 state, existing verified identity and matching pointers, true ingestion discovery, one bot poller and a conservative PostgreSQL delivery protocol. Candidate row serializes consent/profile/revoke/admission and immediate/digest arbitration; one-attempt external transport never retries uncertain outcomes.
**Tech Stack:** Java 21, Spring Boot/JdbcTemplate, Flyway, PostgreSQL, React/TypeScript/Playwright, existing Docker image tooling.
**Spec:** phase-5-design.md (durable approved copy: /Users/pavlo/.codex/visualizations/2026/10/20/01a15928-b90a-7f00-890b-9a6b0f4ab7ef/private-phase5-evidence/phase5-design.md), including approved concurrency refinement; original pasted Phase5 acceptance groups remain binding.

## Global Constraints

- Existing worktree /Users/pavlo/.codex/worktrees/jobpilot-phase1-identity/JobPilot, current baseline a062a68838990d8793fa82aedd3cb6d24b4f916d; no reset/new checkout.
- Do not commit, push, merge, deploy, publish images, access Azure/production or implement Phases6/12.
- V1–V19 immutable; retained images/evidence/checkpoint immutable. New migration V20 only.
- Candidate/job lifetime identity; UNKNOWN and INELIGIBLE never proactively send; immediate + optional 09:00 digest only; owner remains legacy-exclusive.
- Single-attempt Telegram connect2sec/exchange5sec/body16KiB; no redirect, sanitized typed outcomes; ambiguity never auto-retry.
- Lock ranks and timeouts from spec are mandatory; new methods fail closed in recovery before writes/claims/network.
- All tests synthetic/local; tests must execute on PostgreSQL, never count skipped IT as pass. Full verification after integration, not after every small edit.

## Review Focus

- A legacy CREATED outcome can promote an existing job: discovery must be actual insert, not outcome.
- Expired Mini App sessions should not silently end proactive consent, while explicit revocation must end it.
- A committed SENDING marker surviving process death must never be reclaimed for external dispatch.
- Timezone changes and DST overlap must not reset rolling budgets or digest identity.
- Restored DB markers cannot prove no delivery history was lost; external reconciliation gate is required.

## Task 1 — Preferences, verified private activation and V20 foundation

Files: create `notifications/NotificationProperties.java`, `NotificationPreferences.java`, `NotificationSettingsService.java`, `NotificationController.java`, `NotificationActivationService.java`, `NotificationLocks.java`; `db/migration/V20__candidate_notifications.java` plus checksummed `db/notifications/V20-schema.sql` and `V20-postgresql.sql`; modify IdentityWebBoundary/TelegramIdentityService/TelegramUpdate/TelegramApiClient/TelegramUpdateProcessor/SearchProfileService/JobPilotApplication/config only as required for this boundary. Tests: NotificationPolicyTest, NotificationSettingsIT, V20NotificationMigrationIT, activation parser/API tests.

Interfaces: NotificationSettingsService.view(principal), save(principal,Update), disconnect(principal); NotificationActivationService.challenge(principal), process(TelegramUpdate):boolean; NotificationLocks.bound(JdbcTemplate) and lockCandidate(id); static notification rebaseline helper usable by profile activation/revoke. GET/PUT `/me/notifications`, POST `/me/notifications/activation`, DELETE `/me/notifications/connection`. Update fields expectedRevision,requestId,enabled,paused,immediate,digest,minimumScore,timezone,quietStart,quietEnd; response revision/preferences plus connectionStatus/pauseReasons/legacyManaged; challenge activationUrl/expiresAt. No client identity selectors. Defaults disabled,true immediate,false digest,85,UTC,null quiet; optimistic current replay semantics match SearchProfileService.

- [x] Write failing tests for disabled defaults, foreign identity/chat/token, single-use private challenge, forward/group/missing evidence, exact off command, revision replay/conflict and old session revocation. Run focused Maven tests and retain RED log.
- [x] Implement additive schema/composite ownership FKs/bounds and lock helpers, consent/activation/session scope and strict API routes. Migrate existing data with no consent or discovery backfill. Retain all old migration bytes. No sender/network work yet.
- [x] Test expiry versus explicit revocation, two-account isolation, owner LEGACY_MANAGED, profile rebaseline/cancellation, recovery rejection and V13→V20 preservation. Run focused PostgreSQL/unit tests GREEN. Record exact counts/changed files/known cross-task dependencies.

## Task 2 — Discovery, durable outbox, arbitration and transport

Files: create `notifications/NotificationDiscovery.java`, `NotificationOutboxService.java`, `NotificationDeliveryWorker.java`, `NotificationSchedule.java`, `TelegramNotificationTransport.java`; modify actual-insert JobProcessor capture paths, MatchingCatalogue.bootstrap into separate one-job transactions, matching reconciliation hook, existing poller gate. Tests: NotificationScheduleTest, TelegramNotificationTransportTest, NotificationDeliveryIT, NotificationConcurrencyIT, NotificationCapacityIT.

Consumes Task1 preferences/locks/identity/schema. Produces bounded `admitOne()` and `deliverOne()` methods usable by scheduler and tests, both independently recovery/arming gated; fake transport injection only through ordinary interface. Full lifetime `(candidate,job)` uniqueness and immutable attempts; no test-only controls in production. Add discovery rows only on actual new-job insertion within ingestion transaction after catalogue capture, using committed catalogue barrier as final lock. Reconciliation reads bounded current match/discovery rows under candidate lock. Final dispatcher follows exact rank graph. Operator external reconciliation approval file must match both persisted runtime epoch and fresh in-memory process nonce before any dispatch/claim; every startup is disarmed. Never pretend SQL auto-detects every rewind.

- [x] Write/run RED tests for no historical flood (including >100 catalogue jobs, bootstrap, old CREATED promotion, profile edits/resume), duplicate publication and ELIGIBLE-only current matching ownership.
- [x] Implement producer/state machines/bounded cursors; write/run RED then GREEN transport fixtures for valid success, 429, blocked, 5xx, malformed success, timeout-after-acceptance and oversized body, asserting one HTTP request only.
- [x] Write/run PostgreSQL barriers for two immediate/digest claimers, consent/profile-before-send and send-before-mutation, held-lock timeout, claim crash before SENDING, crash after SENDING, outcome-write failure and restart. Implement gates/fencing/atomic envelope membership until GREEN; no uncertainty resend.
- [x] Test DST gaps/overlaps, quiet hours, midnight, UTC/non-hour timezone, rolling daily budgets and no empty digest. Stress multiple claimers, capture SQL deadlock count/time bounds and performance/storage measurements. Preserve owner notifier regression behavior and one poller.

## Task 3 — Progressive Mini App notifications

Files: create mini-app/src/data/notifications.ts, features/NotificationSettings.tsx and tests/notifications.api.spec.ts; modify BetaWorkspace navigation, safe Telegram link helper if needed and scoped styles. Consume exact Task1 API; use captured profile credential generation before/after all asynchronous completions. No local/session storage.

- [x] Write Playwright RED cases for default opted out, clear consent/connect/disable/disconnect/paused status, hidden advanced settings, 09:00 digest copy, eligible/new-only explanation, failed/ambiguous saves and retained request identity.
- [x] Implement mobile Settings section, simple switches, collapsed minimum score/quiet hours, timezone confirmation and explicit bot activation link/refresh. Keep recommendation browsing unaffected by caps.
- [x] GREEN tests for account switching (late success/401/network), session expiry, conflict/reload, stale drafts and mobile overflow/accessibility. Typecheck/lint/API build; record exact counts.

## Task 4 — Recovery, independent review and complete verification

Files: RecoveryConfigurationGuard/SchemaCompatibilityGuard/application-recovery.yml/Dockerfile.recovery/build-recovery-artifact.py/check-integration-results.py and dependent recovery tests; docs phase5 implementation/runbook. Existing old migration fixtures may require bounded-target changes, never migration edits.

- [ ] RED→GREEN V20 recovery/futureV21/unsafe overrides tests. Ensure direct producer/worker/activation calls fail closed. Add required PostgreSQL suite guard entries and artifact-script tests.
- [ ] Complete independent security review of actual diff/current source and close all Critical/High with executable regressions; retain reviewer report/source binding. Reviewer must assess lock graph, uncertainty fences, disarm-after-restore and preservation, not just endpoints.
- [ ] Freeze source. Full clean Maven verify + required PG gate, frontend npm ci/typecheck/lint/full Playwright/API production build, artifact self-checks. Record exact XML/Playwright pass/fail/skip counts. Repair only demonstrated failures and repeat affected/final frozen-source checks as needed.
- [ ] Build/save a new private local V20 recovery image from verified source, pinned existing base image; no push. Rehearse actual V13→V20 and failed-release→recovery→normal after real synthetic preferences/outbox/attempt records exist. Assert complete outbox/attempt fingerprints/owner access/recovery silence, preserved actual V19 refusesV20 and V20 refusesV21; unstubbed browser→API→PostgreSQL flow.
- [ ] Rehash all V1–V19/checkpoint/prior artifacts; publish durable final report with architecture/API/UI, retry/flood evidence, exact tests/security findings/image identity/measurements/limits. LOCAL GO only if every mandatory check passed; production remains NO-GO. Stop after Phase5.

## Execution record

Read phase5-progress.md before continuing after compaction. User explicitly authorized creating this plan then local implementation, so no second plan approval round is required. Never mark a task complete merely because its author wrote code. No commits; keep review packages as dirty-source file hashes/diffs rather than commit ranges.

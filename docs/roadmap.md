# JobPilot roadmap

Phase status for the human-in-the-loop vacancy discovery service. See
[Phase 2 architecture](phase-2-architecture.md) for the detailed design of the
completed workflow phases.

## Phase 1 — Core ingestion and persistence: COMPLETE

Deterministic public ATS ingestion, normalization, canonicalization,
deduplication, requirement extraction, and PostgreSQL persistence with
Flyway-managed schema.

## Phase 2 — Candidate workflow, scoring, Telegram, LLM, and documents: COMPLETE

Versioned candidate truth model, manual public vacancy URL submission, human
application tracking, optional budgeted structured job analysis, truthful résumé
and cover-note generation for private review, Telegram notification and digest,
and maintenance/readiness endpoints.

## Phase 2.5 — Eligibility screening and rejection reconciliation: COMPLETE

Hard location and early-career screening plus relevance screening, resolved into
a single MATCH / REVIEW / REJECT disposition. Rejected vacancies are reconciled
rather than silently dropped. Tenant-aware job identity
(`source + provider_tenant + external_id`). Migrations V6–V9.

## Phase 3 — Source reliability and coverage: COMPLETE (3.1-3.3F)

### 3.1 Per-tenant source health — COMPLETE

Every Greenhouse, Lever, Ashby, Recruitee, and SmartRecruiters tenant fetch is recorded as one
immutable attempt row plus a current health roll-up, with a closed failure
taxonomy instead of a bare `ExternalHttpException`. Adds migration V10
(`source_tenant_fetch_logs`, `source_tenant_health`, and a nullable
`ingestion_run_id` on `source_fetch_logs`), one ingestion run identifier per run,
structured safe logging, and a read-only `GET /api/sources/health` endpoint.

Diagnostics only: no tenant is ever disabled or removed automatically, and a
failing external tenant never affects application `/health`.

Example symptoms this phase makes legible rather than special-cases:
repeated `INVALID_TENANT` on an Ashby board that has been renamed, or
`SERVER_ERROR` bursts from one Greenhouse tenant while the rest succeed.

### 3.2 Tenant registry audit and cleanup — COMPLETE

Audited all 46 configured tenants against ingestion run
`f4020aec-e4fe-47ab-977a-7fcb49bfa61b`: 32 succeeded, 14 failed.

Only **one** removal was justified: `recruitee/xebiapoland` returned a
deterministic HTTP 404 both during ingestion and on a single direct verification
request, with no verifiable replacement board.

The other 13 failures were all `RESPONSE_PARSE_ERROR` — *"response exceeded the
configured size limit"*. Those boards are live and answered in 194–2313 ms; they
exceeded `jobpilot.http.max-response-bytes`, which was then 2 MiB in
`application.yml`. **These were a client-side configuration limit, not dead
tenants, and were all retained.**

That open follow-up is now Phase 3.2.5 below.

`SourceRegistryValidationTest` now binds the same configuration path production
uses and fails the build on blank, untrimmed, duplicated, or malformed
identifiers, on non-deterministic ordering, and on reintroduction of the removed
board.

### 3.2.5 Bounded large ATS response support — COMPLETE

Raises the deployed `jobpilot.http.max-response-bytes` default from 2 MiB to
**10 MiB (10485760)** so the 13 large-but-valid boards found in 3.2 can be read,
without weakening any size protection.

- Overridable with `JOBPILOT_HTTP_MAX_RESPONSE_BYTES`; the tracked default needs
  no `.env` change.
- Valid range **1048576 (1 MiB) – 33554432 (32 MiB)**. An out-of-range value
  fails startup naming the property and the range; it is never silently clamped.
- Reading stays strictly bounded. A declared `Content-Length` above the limit is
  refused from the header before the body is consumed; chunked responses and
  responses with no declared length are bounded by the same streaming cap; the
  partial buffer is discarded and the stream closed. Raising the limit changes
  the bound, never the fact that there is one.
- Oversized responses are now `RESPONSE_TOO_LARGE`, distinct from
  `RESPONSE_PARSE_ERROR` (malformed JSON, wrong content type, mapping failure).
  The classification comes from structured exception state, never message text,
  and the failure remains deterministic and non-retryable.
- Migration `V11__tenant_response_too_large_category.sql` widens the two
  failure-category CHECK constraints only. No table is recreated and no
  historical `RESPONSE_PARSE_ERROR` row is rewritten, so rows recorded under the
  old 2 MiB limit stay exactly as observed.

First live validation was **partial**. Run
`88482391-3535-4409-85b7-d1c3ae7dd027` completed its ATS fetch stage — 45
attempts, 45 distinct provider+tenant pairs, all four providers, 12 of the 13
formerly size-capped boards recovered, `xebiapoland` not attempted — but it
**never emitted its Source health summary or Vacancy ingestion report**. It was
interrupted during screening, which exposed the blocker addressed in 3.2.6 and
was finally validated end-to-end in 3.2.7.

`lever/veeva` still exceeds the 10 MiB cap. It is deliberately retained and not
special-cased; supporting it needs a separate pagination or streaming decision,
not a further limit increase.

### 3.2.6 Location screening performance hardening — COMPLETE

Root cause of the interrupted run: `LocationEligibilityService` re-normalized
whole text fields inside its predicate helpers. `word(text, needle)` called
`normalize(text)` on every invocation, and `countryRestriction` issued seven such
calls for each of ~254 countries against a string containing the full job
description — roughly 1,778 full-description normalizations per call, and
`countryRestriction` ran twice per vacancy. Each normalization performed an NFD
decomposition plus three `String.replaceAll` passes, so a single large vacancy
cost seconds of pure CPU. Measured on a 119 KiB description: 1,778
normalizations take **5,230 ms**.

The fix normalizes each field once per evaluation, threads the results through an
immutable per-vacancy context, pre-normalizes the fixed country/city/state
vocabulary at class initialisation, and compiles every reusable expression once.
Phrase checks run against pre-normalized text and prune on country tokens before
scanning. After the change the same vacancy needs **18** normalize calls, **5**
full-size normalizations, and 5.0x its own length in normalized characters.

Screening semantics are unchanged. A 38-fixture characterization suite captured
the complete observable output — disposition, eligibility, workplace, scope,
normalized city and country, reason text, reason codes and their order, and
detected restrictions — from the pre-refactor implementation, and passes byte-for
-byte against the new one.

The adjacent early-career and relevance predicates on the same per-vacancy path
recompiled fixed expressions on every call; both now use a bounded cache keyed
only by fixed application vocabulary.

### 3.2.7 Final live validation — COMPLETE

Run `54ffc10e-9252-46cb-95fc-b53f4a8af10f` completed end to end in **70.6
seconds** on the persistent environment, emitting both the Source health summary
and the Vacancy ingestion report for the first time.

- 45 tenant attempts, 45 distinct tenants, exactly one attempt each;
- 44 success, 1 failure (`lever/veeva`, `RESPONSE_TOO_LARGE`);
- fetched 4,779 and uniqueRaw 4,779, up from 1,208 before 3.2.5;
- finalMatch 0, finalReview 66, finalReject 4,713 — reconciling to uniqueRaw;
- persistedNew 37, updated 4, existingUnchanged 48, duplicateRaw 0;
- `xebiapoland` not attempted; no second run started.

Screening 4,779 vacancies took **12.5 seconds** after the last tenant fetch,
against tens of minutes before 3.2.6. Peak app usage was 158% CPU and 540 MiB of
3.8 GiB. No rejected job retained a score.

`lever/veeva` still exceeds the 10 MiB cap and is deliberately retained rather
than special-cased; supporting it needs a pagination or streaming decision, not
a further limit increase.

### 3.3A Supported ATS source expansion — COMPLETE

Expand the registry using only the four existing adapters, with every tenant
identifier taken from an official careers page or a public ATS URL. Fourteen
employers were researched and three tenants added — ashby `uipath`, greenhouse
`scbitdefendersrl` (Bitdefender), and greenhouse `showpad` — taking the registry
from 45 to 48. Full per-candidate evidence is in
[source-expansion-audit.md](source-expansion-audit.md).

This is below the 12–25 target for the phase. The limit was evidence
acquisition, not the acceptance bar: most careers pages render their board
client-side, several strong Bucharest employers run unsupported ATS platforms,
and search results degraded to aggregators partway through. Guessing identifiers
would have closed the gap and is prohibited, so the gap is carried forward
rather than filled. Four employers remain `HOLD_AMBIGUOUS` and are the cheapest
next wins.

Expanded live run `d507d7c2-ca0d-413e-b694-644748db8822` completed all 48
tenant attempts with 47 successes and the single expected
`lever/veeva RESPONSE_TOO_LARGE` failure. It fetched 4,960 unique raw jobs and
reconciled them to 1 MATCH, 71 REVIEW, and 4,888 REJECT, with no duplicate raw
jobs and no rejected job retaining a score. `xebiapoland` was not attempted.

The new sources delivered the first MATCH from `ashby/uipath` (Software
Engineer, Bucharest) and five Bucharest REVIEW candidates from
`greenhouse/scbitdefendersrl` (QA Engineer, QA Engineer Mobile, Node.js
Developer, iOS Developer, and ERP Developer). `greenhouse/showpad` fetched 30
jobs successfully but produced no MATCH or REVIEW in this run; it remains a
verified tenant with low current yield.

### 3.3B Unsupported-ATS gap analysis — COMPLETE

Audited **24 employers** from official careers flows, including every
unsupported/ambiguous Phase 3.3A lead. Six named unsupported provider families
were confirmed — SmartRecruiters, Workday, SAP SuccessFactors Career Site
Builder, Teamtailor, Avature, and Oracle Recruiting Cloud — alongside custom or
still-ambiguous flows. The audit also corrected Sysdig to the already-supported
Lever provider.

**SmartRecruiters** is selected for Phase 3.3C with a weighted score of
**4.75/5**. Five confirmed relevant employers (Bosch Group, Ubisoft, Endava,
Gameloft, and AECOM) exposed 242 current Romania jobs, including 112 explicitly
Bucharest, through a documented unauthenticated JSON Posting API with 100-item
offset pagination, stable IDs, country filtering, and complete detail records.
It outranked the strong Workday alternative because Workday's public CXS flow
is undocumented, uses composite shard/tenant/site coordinates and smaller
pages, and carries higher maintenance risk.

Full evidence and rejected alternatives are in
[unsupported-ats-gap-analysis.md](unsupported-ats-gap-analysis.md); the bounded,
generic implementation contract is in
[next-ats-provider-spec.md](next-ats-provider-spec.md). Phase 3.3B changed only
documentation: implementation remains unstarted, no tenant was added or
removed, and no runtime, database, migration, screening, or scoring change
occurred.

### 3.3C SmartRecruiters provider implementation — COMPLETE (offline verified)

Implemented the generic public unauthenticated Posting API adapter with strict,
case-preserving configuration and exact-host validation. Every tenant queries the
Romania and remote partitions in fixed order, uses 100-item offset pagination,
deduplicates before sequential detail hydration, and is bounded by ten aggregate
list pages and 500 aggregate unique postings. A page, detail, schema, identity, or
cap failure discards the whole tenant result and records one classified zero-count
attempt; later tenants and providers continue.

Synthetic minimized fixtures and offline tests cover configuration, DTO validation,
pagination termination, duplicate/conflict handling, URL canonicalization, location
metadata, failure isolation, source health, and PostgreSQL tenant-aware persistence.
The response limit remains 10 MiB and Flyway remains at V11; no RawJob or database
shape changed. No live API validation occurred, and the SmartRecruiters registry is
empty in both base and development configuration. BoschGroup, Ubisoft2, Endava,
Gameloft, and AECOM2 remain candidates only for Phase 3.3D.

### 3.3D SmartRecruiters live validation and registry expansion — COMPLETE

One controlled live cycle, run `c7643227-60fe-4fa6-8ea7-57ccd5e39e7a`, validated
the 3.3C adapter against the public Posting API. 53 configured tenants, 49
success, 4 failure, complete in 2 min 15 s. Full record in
[smartrecruiters-live-validation.md](smartrecruiters-live-validation.md).

Two companies were activated, taking the registry from 48 to 50:

- `BoschGroup` — 103 postings, one REVIEW role;
- `AECOM2` — 471 postings, one Bucharest-local `ENTRY_LEVEL` REVIEW role.

`Ubisoft2`, `Endava`, and `Gameloft` each returned a deterministic
`RESPONSE_PARSE_ERROR` in 250–400 ms with a null HTTP status, and were **not**
activated. They were also not retired: the null status means the endpoint
answered rather than rejecting the identifier, and the same adapter parsed the
other two companies in the same run, so the cause is more likely one unhandled
response shape than three invalid identifiers. Distinguishing the two needs a
direct look at the response, which 3.3D prohibited.

Provider mechanics held: one health attempt per company with no per-page or
per-detail rows, both partitions executed, no pagination loop, neither the
10-page nor the 500-posting cap reached, `duplicateRaw=0`, and zero rows
retained for the three failed companies. Bucharest-located vacancies rose from
57 to 134 against the same-day scheduled run — the strongest evidence that
SmartRecruiters reaches Romanian employers the other four adapters miss.

### 3.3E SmartRecruiters parse-failure investigation — COMPLETE

Root cause of the three 3.3D failures: SmartRecruiters serialises a reference
object's `id` (and sometimes `label`) as a **JSON number** for some companies and
a string for others. The adapter read every reference field through a strictly
textual accessor, so a numeric `department.id` failed the whole tenant. The list
responses for these boards carry string ids and the detail responses carry
numbers, which is why both list partitions parsed cleanly and the failures were
fast. Full analysis in
[smartrecruiters-response-compatibility.md](smartrecruiters-response-compatibility.md).

Two things changed, both provider-generic:

- `optionalScalarText` accepts a string, number, or boolean for a reference
  `id`/`label` only. Containers are still rejected, and every mandatory field
  keeps the strict path. Reference values are display text and never touch
  identity, canonical URL, or eligibility.
- `ExternalHttpException` now carries a bounded `parseDetail` such as
  `detail.department.id: expected STRING but was NUMBER`, built only from
  compile-time field paths and JSON type names. Previously all ~15 validation
  points threw the same contextless error, which is why 3.3D could not name the
  stage.

After the fix all three parse: Ubisoft2 4 postings, Endava 102, Gameloft 5 —
each with a stable external ID, preserved tenant, https canonical URL, and a
non-empty description. `BoschGroup` and `AECOM2` are unchanged, asserted by a
characterization test against the original fixture.

No ingestion cycle ran and the tracked registry was **not** changed: it remains
`BoschGroup,AECOM2`.

### 3.3F SmartRecruiters held-company activation — COMPLETE

One controlled cycle, run `4d1ddf9c-07b0-488e-8bc1-23bc6b1c16c0`, fetched all 53
configured tenants in 2 min 44 s: 52 success, 1 failure. The only failure is
`lever/veeva` (`RESPONSE_TOO_LARGE`), unchanged and still not special-cased.
**Zero parse errors of any kind** — the 3.3E scalar-reference fix held in
production.

All five SmartRecruiters companies succeeded, each with exactly one attempt:

| Company | Fetched | Duration |
|---|---|---|
| `BoschGroup` | 103 | 16.7 s |
| `AECOM2` | 470 | 69.6 s |
| `Ubisoft2` | 4 | 0.7 s |
| `Endava` | 102 | 15.2 s |
| `Gameloft` | 5 | 0.9 s |

`Ubisoft2`, `Endava`, and `Gameloft` were classified **ACTIVATE** and added to
the tracked registry, taking it from 50 to **53 tenants**.

Cycle totals: fetched 5,647, uniqueRaw 5,647, duplicateRaw 0, finalMatch 1,
finalReview 73, finalReject 5,573, persistedNew 0, updated 1,
existingUnchanged 96. Bucharest-located vacancies rose from 134 to **170**
against the 3.3D run.

The three newly activated boards contributed no MATCH or REVIEW this cycle:
their 111 vacancies were all screened out. They are retained as valid boards
with low current yield, the same basis used for comparable Greenhouse and Ashby
tenants — a board is kept because it is live and correctly parsed, not because
it happened to surface an eligible role on one particular day.

## Phase 4 — REVIEW workflow and ranking calibration

### 4A Telegram review queue MVP — COMPLETE

Triage for the MATCH and REVIEW buckets, delivered in Telegram.

- `V12` adds `job_workflow_state` (one row per job; SAVED, APPLIED, DISMISSED;
  absence means UNREVIEWED; optional bounded note; nullable `applied_at`) and
  `telegram_job_delivery` (confirmed sends only, unique per chat + job + type).
- Private review bot behind `TELEGRAM_BOT_ENABLED`, disabled by default and
  requiring no token while disabled. Authorization is numeric and explicit: a
  private chat whose ID appears in `TELEGRAM_ALLOWED_CHAT_IDS`. Usernames are
  never used, and every command and callback is authorized independently.
- Commands `/start`, `/help`, `/matches`, `/review`, `/saved`, `/applied`,
  `/stats`, `/job <id>`, `/note <id> <text>`, `/reset <id>`, with bounded
  pagination and inline Open/Save/Applied/Dismiss/Reset/Next actions.
- Queues cover active MATCH and REVIEW only; REJECT, expired, and dismissed
  vacancies are excluded. Order is UNREVIEWED, then SAVED, then score, then
  recency, then job ID.
- After ingestion the bot pushes capped MATCH cards and one REVIEW digest for
  vacancies **newly persisted by that run only**, so enabling it never replays
  the backlog. Delivery rows are written only after Telegram confirms a send;
  failures stay retryable and can never fail or roll back ingestion.
- Reuses the existing project-owned `TelegramClient` and long-polling stack. No
  Telegram SDK dependency was added.
- **An accidental Thymeleaf web review interface (REST `/api/jobs`, MVC `/jobs`,
  templates, CSS, and the `spring-boot-starter-thymeleaf` dependency) was removed
  in the same change.** Its reusable backend parts — the workflow status enum,
  workflow entity and repository, and the queue query and workflow services —
  were refactored into delivery-neutral application services and kept. JobPilot
  remains Telegram-first with no user-facing web frontend.

### 4B Ranking calibration — NEXT

Calibrate scoring and ranking against the triage outcomes now being recorded in
`job_workflow_state`.

## Phase 5 — Browser-worker production hardening: HOLD

The optional Crawlee + CloakBrowser `scraper-worker` remains **disabled by
default and is not production-ready**. Before it could be considered, it needs
at minimum: network-layer egress denial for private and cloud-metadata ranges,
a recorded dependency-reachability analysis for its transitive npm advisories,
and a human decision on the CloakBrowser licence and Chromium redistribution
terms.

## Phase 6 — Deployment and operations: PLANNED

Deployment runbook, backup and restore procedure, migration rollout process,
log and metric retention, and alerting thresholds.

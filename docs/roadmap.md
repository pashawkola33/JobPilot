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

## Phase 3 — Source reliability and coverage: IN PROGRESS (3.1-3.3B complete, 3.3C next)

### 3.1 Per-tenant source health — COMPLETE

Every Greenhouse, Lever, Ashby, and Recruitee tenant fetch is recorded as one
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

### 3.3C SmartRecruiters provider implementation — NEXT

Implement the generic public Posting API adapter, strict configuration and host
validation, bounded country/remote query partitions, 100-item offset pagination,
N+1 detail hydration, aggregate page/job caps, all-or-nothing tenant semantics,
source-health integration, sanitized offline fixtures, and zero-network
automated tests. Do not add production tenants in this phase.

### 3.3D SmartRecruiters live validation and registry expansion — PLANNED

After 3.3C is reviewed and green, validate a small evidence-backed initial
registry in one authorized live run. Confirm one health attempt per company,
bounded request volume, Romania/Bucharest discovery, and correct reconciliation
before considering the two very large global boards. Registry additions and any
runtime restart belong only to this separate phase.

## Phase 4 — REVIEW workflow and ranking calibration: PLANNED

Operator workflow for the REVIEW bucket, and calibration of scoring and ranking
against reviewed outcomes.

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

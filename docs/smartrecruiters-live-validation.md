# SmartRecruiters live validation (Phase 3.3D)

Record of the single controlled live ingestion cycle that validated the Phase 3.3C
SmartRecruiters adapter against the public unauthenticated Posting API and decided which
companies enter the tracked registry.

- **Controlled run:** `c7643227-60fe-4fa6-8ea7-57ccd5e39e7a`
- **Trigger:** one-time cron `0 7 0 4 8 *` evaluated in `Europe/Bucharest`
- **Window:** 2026-08-04 00:07:00 → 00:09:15 EEST (2 min 15 s)
- **Configured tenants:** 53 (greenhouse 12, lever 6, ashby 23, recruitee 7,
  smartrecruiters 5) — the run recorded **53 attempts across 53 distinct provider+tenant
  pairs**, exactly one attempt each
- **Bounds in force, all unchanged by this phase:** page size 100, aggregate cap of 10 list
  pages and 500 unique postings per tenant, and the existing 10 MiB per-response limit
- **Schema:** no migration was created or applied; the database stayed at version 11
- **Method:** shell-only environment override. `.env` was not modified, and the runtime was
  restored to the normal 48-tenant registry immediately after the report appeared.

A normal scheduled six-hour run (`a9189333-f4d5-43f2-a7f4-89d7e89e35bc`, 48 attempts)
completed at 00:01:28 EEST, before the temporary runtime started. It is not part of this
validation and is recorded here only to keep the accounting unambiguous.

## Per-company result

| Company | Status | Category | HTTP | Fetched | Duration | Consecutive failures | Health |
|---|---|---|---|---|---|---|---|
| `BoschGroup` | SUCCESS | NONE | — | 103 | 15.98 s | 0 | SUCCESS |
| `Ubisoft2` | FAILURE | `RESPONSE_PARSE_ERROR` | null | 0 | 0.25 s | 1 | FAILURE |
| `Endava` | FAILURE | `RESPONSE_PARSE_ERROR` | null | 0 | 0.35 s | 1 | FAILURE |
| `Gameloft` | FAILURE | `RESPONSE_PARSE_ERROR` | null | 0 | 0.40 s | 1 | FAILURE |
| `AECOM2` | SUCCESS | NONE | — | 471 | 69.81 s | 0 | SUCCESS |

Each company was attempted exactly once. No per-page or per-detail health row was written:
SmartRecruiters produced exactly five attempt rows for five configured companies.

Persisted failure detail is the bounded form `Could not parse the smartrecruiters jobs
response for tenant <company>` — no response body, header, or description.

## Decisions

| Company | Classification | Basis |
|---|---|---|
| `BoschGroup` | **KEEP_PRODUCTIVE** | 103 postings; contributed a REVIEW role (Data Engineer, score 48) |
| `AECOM2` | **KEEP_PRODUCTIVE** | 471 postings; contributed a Bucharest-local `ENTRY_LEVEL` REVIEW role |
| `Ubisoft2` | **HOLD_AMBIGUOUS** | deterministic parse failure, cause not yet distinguishable |
| `Endava` | **HOLD_AMBIGUOUS** | same |
| `Gameloft` | **HOLD_AMBIGUOUS** | same |

`BoschGroup` and `AECOM2` were added to the tracked registry. The other three were not.

### Why the three failures are not `REMOVE_BEFORE_COMMIT`

Removal requires deterministic evidence of an invalid tenant, an unsupported contract, or a
deterministic incompatible response. The observed failures are suggestive but not yet
conclusive:

- `http_status` is **null**, so no 404 was returned — the endpoint answered rather than
  rejecting the identifier, which is not what a wrong company slug normally looks like;
- all three failed in 250–400 ms with the identical category, which reads more like one
  unhandled response shape than three independent invalid identifiers;
- the same adapter parsed `BoschGroup` and `AECOM2` in the same run, so the base contract
  is implemented correctly.

Distinguishing a wrong identifier from an adapter gap needs one direct look at the response
shape, and this phase prohibits manual probing of the five endpoints. They are therefore
held, not removed, and not activated — activating them would add three guaranteed failures
per cycle for zero vacancies.

**Follow-up — resolved in Phase 3.3E and closed in Phase 3.3F.** The cause was a numeric
`department.id` in the detail response, a legitimate Posting API variant the adapter's
strictly textual accessor rejected. After a provider-generic fix, controlled run
`4d1ddf9c-07b0-488e-8bc1-23bc6b1c16c0` fetched all five companies with **zero parse
errors** — Ubisoft2 4 postings, Endava 102, Gameloft 5 — and all three were activated.
The tracked registry is now `BoschGroup,AECOM2,Ubisoft2,Endava,Gameloft`. See
[smartrecruiters-response-compatibility.md](smartrecruiters-response-compatibility.md).

## Pagination and detail behaviour

Observable from bounded operational logs and source-health rows:

- both partitions ran per company (Romania `country=ro`, remote `q=remote`);
- no pagination loop: every tenant terminated on its own, and the whole 53-tenant cycle
  finished in 2 min 15 s;
- neither cap was hit — `SmartRecruitersLimitException` was never raised, and the largest
  board (`AECOM2`, 471 unique postings) stayed under the 500-posting cap and the 10-page
  aggregate cap;
- throughput implies one detail request per unique posting and no repeats: 103 postings in
  15.98 s and 471 in 69.81 s are both ≈6.5 requests/second;
- `duplicateRaw=0` for the whole run, so postings returned by both partitions were mapped
  once;
- the three failed companies returned **zero** rows — all-or-nothing tenant semantics held,
  with no partial results retained;
- no raw response or job description was logged; the longest emitted log line was 773
  characters.

## Cycle totals

```
Source health summary: tenantAttempts=53, success=49, emptySuccess=0, failed=4,
  failuresByCategory={RESPONSE_PARSE_ERROR=3, RESPONSE_TOO_LARGE=1}
Vacancy ingestion report: fetched=5538, uniqueRaw=5538, duplicateRaw=0,
  finalMatch=1, finalReview=73, finalReject=5464,
  persistedNew=2, updated=2, existingUnchanged=93
```

Both invariants hold: `1 + 73 + 5464 = 5538 = uniqueRaw`, and
`27 + 47 + 167 = 241 = locationCareerEligible`.

The single `RESPONSE_TOO_LARGE` is `lever/veeva`, unchanged and still not special-cased.

## Marginal value

Against the 00:00 scheduled run on the same day and same 48-tenant registry:

| Counter | Before | After | Δ |
|---|---|---|---|
| fetched / uniqueRaw | 4,963 | 5,538 | +575 |
| Bucharest-located | 57 | 134 | **+77** |
| early-career eligible | 24 | 30 | +6 |
| location + career eligible | 222 | 241 | +19 |
| finalReview | 71 | 73 | +2 |
| finalMatch | 1 | 1 | 0 |

The Bucharest count more than doubled from two companies. That is the clearest argument for
SmartRecruiters as a provider: it reaches Romanian employers the four existing adapters do
not.

## Roles surfaced

| Company | Title | Location | Disposition | Score | Eligibility |
|---|---|---|---|---|---|
| AECOM2 | Data Engineering Specialist III | Bucharest, RO | REVIEW | 0 (UNSUITABLE) | `BUCHAREST_LOCAL`, `ENTRY_LEVEL`, early-career ELIGIBLE |
| BoschGroup | Data Engineer | Timișoara, TM, RO, Remote | REVIEW | 48 (LOW_MATCH) | `REMOTE_ELIGIBILITY_UNKNOWN`, `ENTRY_LEVEL`, early-career ELIGIBLE |

- <https://jobs.smartrecruiters.com/AECOM2/744000132474785-data-engineering-specialist-iii>
- <https://jobs.smartrecruiters.com/BoschGroup/744000122656549-data-engineer->

Both are data-engineering roles rather than the Java/backend, cybersecurity, or QA targets
at the centre of the profile, so both landed in REVIEW rather than MATCH. Screening was not
tuned in response to this run.

## Persistence identity

- provider identity `smartrecruiters`; `provider_tenant` preserves the exact configured
  case (`BoschGroup`, `AECOM2`);
- `external_id` is the SmartRecruiters posting ID (`744000122656549`, `744000132474785`);
- canonical URL is the stable public posting URL, derived from company and posting ID only
  — never from title, location, page, partition, or retrieval time;
- duplicates by `source + provider_tenant + external_id`: **0**;
- `rejected_jobs_with_scores`: **0**.

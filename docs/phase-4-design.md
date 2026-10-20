# JobPilot Phase 4 — proposed matching design

Date: 20 October 2026. Status: proposed; Phase 4 product code and V19 have not been implemented. This document is a reviewable design, not a completion report or release approval.

## Intended outcome and boundaries

Keep one shared vacancy catalogue. Evaluate a vacancy separately for each completed, active search-profile revision, using deterministic Java code. Store eligibility, compatibility, explanations and computation identity per candidate. Add beta recommendations and vacancy details to v2 and the existing Mini App. Preserve owner v1, global legacy scoring, Telegram commands, notifications, workflow isolation, applications and undo. No beta notifications, AI onboarding, automated applications, external queue, production access, commit, push or image publication.

The starting point is the existing linked worktree at `/Users/pavlo/.codex/worktrees/jobpilot-phase1-identity/JobPilot`, branch `codex/phase1-identity`, HEAD `da4d2c992dd8090f0af7671ed383c4ca1cfc17ea`. Do not switch, reset, merge, rebase or replace it. Keep all existing dirty source and recovery artifacts.

## Preflight and preservation

A private source checkpoint contains all 762 tracked and nonignored untracked source files, including nonsecret environment examples. Its archive SHA-256 is `6851b7f85fc0371b80d28f8edebe81350a9ed77c876a2c6c2022fd8838436a9a`. Every member was hashed after archive creation and compared with the live source. The evidence directory is mode 0700 and checkpoint files are 0600. The archive is outside the working tree; no commit was created.

The current source exactly matches the final Phase 3 source identity `6388db7cb758ec9f8c5bb0e90349bf13bb002218f6434b33f4af8bed17b01e27` under its existing inventory rules. All 22 migration files/resources through V18 match that proof. Eleven final V18 proof files, both retained recovery JARs and image archives were hash-verified. A separate inventory hashes 128 retained evidence files, including prior images, reports and screenshots. Preserve these in place.

The independent security review found no reproducible Critical/High issue in the reviewed profile/authentication/workflow paths. Profile access derives ownership from a revalidated bearer principal. Activation locks the candidate, revalidates the session and writes revision/pointer in one transaction; stale or superseded requests cannot reactivate old data. The database enforces composite ownership and immutable revisions. Six executable account-generation checks passed. Baseline rerun results and harness diagnostics are recorded separately in `preflight-report.md`.

## Architectural alternatives

1. **Recommended: append-only vacancy facts and candidate results, with PostgreSQL-backed incremental candidate work.** Keeps shared facts separate, preserves legacy behavior, makes stale publication testable and bounds memory without new infrastructure.
2. Compute matches on each read. Less persistence, but fails bounded incremental backfill, stable versioned results and predictable request latency.
3. Replace `job_scores` and global screening with the new engine. Less coexistence code, but changes owner behavior and notification semantics before a tested cutover. Excluded from Phase 4.

## Where the existing owner influences the catalogue

| Stage | Existing path and effect | Phase 4 treatment |
|---|---|---|
| Discovery | Configured ATS tenant/company/site lists and development source registry were selected for the original search. | Retain the approved registry and access policy. State the resulting coverage limits. No dynamic user-driven tenant discovery. |
| Retrieval | Workday `fetchSite` discovers the Romania facet, then pages and fetches details. Search text is empty. Missing facet already falls back to bounded unfiltered paging. | Preserve this existing behavior and every limit: page size 20, 20 pages/site, 300 unique postings/details, three-minute site deadline, response limits and tenant health monitoring. Do not silently remove the Romania facet or add countries. |
| Admission | `JobIngestionService.runSource` short-circuits location, career and role rejection. `JobProcessor.reconcileRejected` does not create a new rejected job. | Introduce one candidate-independent catalogue persistence path, reached for every valid fetched vacancy. Do not treat legacy rejection as a catalogue exclusion. |
| Extraction | `DeterministicRequirementExtractor` emits a technology list and broad experience/language/education signals. It loses required/preferred distinctions and includes a Romania-specific remote interpretation. | Preserve it for legacy scoring. Add independent, versioned fact extraction with requirement modality, evidence and unknowns. Never use legacy eligibility flags as facts about another candidate. |
| Eligibility | `LocationEligibilityService`, early-career screening and `JobRelevanceFilter` target Bucharest/Romania, junior experience and engineering/Java. | Retain as owner screening only; evaluate beta hard requirements using explicit candidate input and neutral facts. |
| Scoring | `JobScoreCalculator`/`JobMatchingService` use the configured legacy candidate and Java-specific dimensions. Backend denominator uses candidate skill count. | Keep global score behavior for owner consumers. New role-neutral scoring uses job requirements as the skill denominator. |
| Notifications | Ingestion excellent-match send and review digest use legacy disposition, global score, new-job IDs and owner-only destination checks. | Keep those inputs and checks. New catalogue-only rows and personalized results never enter a beta send path. No send after backfill/profile edits. |

Global canonical URL and source/tenant/external-ID deduplication stay global. Content/fact changes update the existing shared job, never create a per-candidate vacancy. Newly admitted legacy-rejected rows retain legacy REJECT visibility and no legacy recommendation. Existing owner notification and digest membership must remain unchanged in differential fixtures. The existing neutral beta snapshot can browse active catalogue rows independently of owner screening.

Coverage remains limited to configured sources and their existing retrieval envelopes. Historical vacancies that were never persisted cannot be recovered by a database backfill; later authorized ordinary fetches may admit them. A remote professional outside Romania can only be matched against vacancies already supplied by those bounded sources. Catalogue completeness must never be implied.

## Neutral facts and evidence

Add a dedicated package beside legacy `matching`, for example `personalizedmatching`. Pure calculation accepts a SearchProfile revision, a VacancyFacts revision and an explicit clock; it performs no identity lookup, HTTP call or LLM call.

Facts preserve original title, description, structured locations/applicant restrictions, work format, employment type, source dates, status/deadline and salary data. Extracted constraints carry type, REQUIRED/PREFERRED/UNSPECIFIED modality, normalized value, exact source field and evidence quote/offset. A source/content hash covers every field used by matching, not just description. An extractor-version change creates a new facts identity even when the source content is unchanged. Candidate-independent source fields must be captured before legacy interpretation mutates flattened fields.

Required skill extraction supports explicit required/preferred structured fields and bounded requirement clauses/bullets. Use one generic representation and a small tested alias dictionary; no separate marketing, finance or logistics scorers. Avoid substring errors such as Java/JavaScript, C/C++, Go/the verb, and SQL/NoSQL. Arbitrary unrecognized skill names can match normalized explicit names without being promoted from unrelated prose. Do not call every mentioned technology mandatory.

Parse explicit mandatory numeric experience, attached to its scope. General experienceYears does not prove six years in Python or in a regulated profession. Mandatory role-specific experience with only general experience known is UNKNOWN. Preferred experience remains a ranking signal. Senior title alone is a role/experience preference, not proof of a mandatory numeric qualification.

Language facts distinguish a named language, an explicit CEFR minimum, a preferred language and an ambiguous fluency requirement. Do not manufacture CEFR levels from vague adjectives. Parse negation and alternative clauses conservatively; unresolved requirements produce UNKNOWN with quoted evidence. Contradictory facts remain unresolved rather than selecting whichever gives a match.

Location restrictions, commuting/office attendance, timezone and legal work authorization are distinct dimensions. A remote label does not grant worldwide eligibility; a target country does not prove residence; residence does not prove permission to work. Unknown/unrecognized geography remains unknown. Salary compares only the same currency and compatible stated period/basis; no invented exchange rates, hours, net/gross conversions or unpaid-role assumptions.

Education and other supported hard requirements are evaluated only when explicitly mandatory and comparable to a confirmed candidate statement. A general graduation label does not prove a specific degree, licence or institution. Explicit alternative qualification paths are represented as alternatives, not independent mandatory blockers. Unparsed mandatory clauses produce an uncertainty reason.

## Candidate facts and one bounded profile extension

Use the candidate's saved, explicit, self-stated search profile. Call these candidate-confirmed statements, not independently verified résumé facts. Do not copy them into candidate_profiles or document-generation evidence. ExperienceYears=0 is a stated zero; absence is unknown. Omission from a possessed-skill list does not prove inability.

Phase 3's global workAuthorization enum and free-text authorizationDetails do not reliably identify a jurisdiction. Add an optional bounded `authorizationByCountry` list of ISO country plus existing authorization status, with manual review controls in the profile editor. Old immutable payloads deserialize this absent field as an empty list. Preserve existing global enum/narrative fields and their history. A user confirms new scoped values by saving a new revision. Do not infer permits from nationality, residence, region membership or narrative keywords. Profiles without scoped authorization remain UNKNOWN where a job requires it.

Keep education conservative using the existing explicit education statement; unsupported equivalences remain UNKNOWN. Do not expand onboarding into résumé uploads or AI questioning.

## Eligibility contract

Return `ELIGIBLE`, `INELIGIBLE` or `UNKNOWN`, with typed checks and source evidence:

- A confirmed incompatible required constraint produces INELIGIBLE: insufficient comparable mandatory experience, insufficient stated CEFR level, confirmed conflicting residence/office restriction without relocation, confirmed missing required authorization/sponsorship conflict, explicit education/licence mismatch or expired/closed vacancy.
- Missing candidate information, ambiguous job restrictions, incomplete requirement extraction, incomparable salary/experience/education or an unlisted required skill produce UNKNOWN unless another check already proves INELIGIBLE.
- ELIGIBLE means all extracted supported hard requirements have positive evidence or are explicitly inapplicable. It does not imply selection, legal certification or a role preference match.
- Preference mismatches do not become hard blockers. Target location, desired employment type, work-format preference and salary preference lower preference fit unless an explicit required constraint independently conflicts.

Each check includes code, dimension, SATISFIED/BLOCKED/UNKNOWN/PREFERENCE status, requirement evidence and the candidate field/revision used. Explanations are templates over these checks. Examples: EXPERIENCE_BELOW_REQUIRED, EXPERIENCE_SCOPE_UNKNOWN, LANGUAGE_BELOW_REQUIRED, LANGUAGE_LEVEL_UNKNOWN, LOCATION_RESTRICTED, REMOTE_SCOPE_UNKNOWN, WORK_AUTHORIZATION_UNKNOWN, SPONSORSHIP_UNAVAILABLE, REQUIRED_SKILL_UNCONFIRMED and MANDATORY_REQUIREMENT_UNPARSED. Never state an omitted qualification as a confirmed absence.

## Compatibility and ranking

Use a transparent 0–100 compatibility score, independent of eligibility. Initial beta weights are a documented product choice, not a probability or empirical hiring prediction:

- Role: 35 points. Maximum normalized title/category phrase-token coverage against any desired title/category. Use bounded aliases and explicit phrase evidence, ignoring generic level/noise tokens. No candidate-independent taxonomy may turn a vague role into a confirmed match.
- Skills: 35 points. If both required and preferred skill sets exist, 80% of this dimension is required coverage and 20% preferred coverage. If only one exists, it receives the whole dimension. Denominator is the applicable job skill set; unrelated extra candidate skills cannot lower it. If no extractable skills exist, use a labelled neutral half-value and report missing evidence. A missing candidate skill gives zero coverage for that requirement and an UNKNOWN check.
- Experience: 15 points. Full value for satisfied comparable requirements or explicitly stated no prior experience; zero for a confirmed shortfall; labelled half-value for unknown/incomparable facts. General years do not satisfy a skill-specific requirement.
- Preferences: 15 points. Equal three-point factors for target geography, work format, employment type, industry and comparable salary. Match=1, known mismatch=0, unknown/incomparable=0.5. Unknown is explicitly reported and never described as satisfied.

Round only the final value, with deterministic tests. Return a factor breakdown and evidence coverage so neutral unknown components cannot masquerade as confirmed fit. Do not add arbitrary excellent/good cutoffs or notification thresholds in Phase 4.

INELIGIBLE rows may retain raw compatibility for audit, but have no recommendation score; the UI shows the blocker rather than a percentage. UNKNOWN rows may show compatibility with a prominent eligibility-unconfirmed label and missing checks. No score changes eligibility.

Freshness is separate ordering data: eligibility group, compatibility descending, published/first-seen descending, job ID descending. Time alone does not rewrite compatibility. Expiry removes a vacancy from current recommendation listings and is checked in details even before background work catches up. No freshness bonus can remove a blocker.

### Synthetic example to implement and test

One stored job: Marketing Assistant, Bucharest, hybrid, full-time, marketing industry; required SEO and Google Analytics; English B2; Romania work authorization required; explicitly no prior experience required; no comparable salary published.

| Candidate | Explicit profile | Role / skills / experience / preference points | Compatibility | Eligibility |
|---|---|---|---:|---|
| A | Junior Java developer, Bucharest; Java/SQL; zero years; English B2; target Romania/hybrid/full-time; unstated industry and scoped permit | 0 / 0 / 15 / 12 | 27 | UNKNOWN: required skills unconfirmed and authorization unknown |
| B | Marketing graduate seeking Marketing Assistant; SEO/Google Analytics; zero years; English C1; same geography/format/type; marketing industry; confirmed Romania authorization | 35 / 35 / 15 / 13.5 | 99 | ELIGIBLE |
| C | Experienced data analyst, Romania; SQL/Python; six general years; English C1; remote/full-time; confirmed Romania authorization; industry unstated | 0 / 0 / 15 / 9 | 24 | UNKNOWN: required skills unconfirmed; remote preference mismatch remains soft |
| D | Logistics professional, Germany; five years; English A2; Germany/onsite/full-time/logistics; no relocation; Germany-only authorization | 0 / 0 / 15 / 4.5 | 20 raw; no recommendation score | INELIGIBLE: English below B2 and incompatible mandatory office location |

These are proposed deterministic expectations, not results from an implemented engine. Add Java, remote-data and logistics vacancies to prove each category can rank first for its matching profile and to test authorization separately from geography. Add monotonicity tests for unrelated extra skills and metamorphic tests for preferred/required changes and evidence-preserving formatting.

## V19 persistence

V19 is next in the inspected inventory. Preserve V1–V18 byte-for-byte, including Java checksums and dialect resources. Use additive tables and FKs; no data deletion, job_scores replacement or rollback script.

Proposed logical tables:

- `vacancy_fact_revisions`: immutable source/fact payload, job_id, job_revision, extractor_version, source hash, created_at. Unique identity `(job_id,job_revision,extractor_version)`; job FK with RESTRICT. `job_revision` increments under a shared-job lock when matching input changes.
- `vacancy_fact_state`: one current fact identity per job, protected by a composite FK. Existing stored jobs are materialized by bounded background bootstrap, not expensive extraction during schema migration. Bootstrap may use preserved source fields only; restrictions that cannot be reconstructed from legacy data remain UNKNOWN with legacy provenance. PostgreSQL triggers reject updates/deletes of fact and result history.
- `matching_catalogue_state`: singleton committed change revision. A short row lock allocates each next change revision and is held until the publication transaction commits.
- `matching_catalogue_changes`: append-only change revision -> current fact identity, inserted with the fact/pointer update. Commit ordering must not rely on an ordinary sequence: a lower allocated sequence can commit after a higher one and be missed by a cursor.
- `candidate_match_results`: immutable identity `(candidate_id,job_id,search_profile_version,job_revision,extractor_version,scorer_version)`, eligibility, raw compatibility, recommendation score, bounded reason/breakdown payload, computed_at. Composite candidate/profile FK and job/fact FK; score and eligibility CHECK constraints. Preserve historical results.
- `candidate_match_current`: one pointer per candidate/job to the exact result identity, enforced with a full composite FK. Index the pointer by candidate/job and the immutable result table by candidate/profile/semantics versions, eligibility, score, publication date and job ID for listing; validate the query plan with retained historical generations. Current queries also validate active profile and current fact/scorer/extractor identity; a pointer alone is insufficient.
- `candidate_matching_state`: one row per candidate, desired profile/semantics version, backfill high-water job ID and last processed job ID, catalogue event cursor, publication revision, state, lease token/expiry, attempts and retry_after. Candidate-owned FK; bounded error category without private payload logs.

Schema must prevent cross-owner profile/result associations and duplicate identity rows. All API predicates still use the principal: FKs do not replace HTTP authorization. H2 may cover fast tests; real PostgreSQL covers constraints, locks, leases, migration and concurrency.

## Incremental scheduling, leases and publication

A completed profile activation initializes/restarts that candidate's matching state in the same transaction. Incomplete profiles suspend recommendations. Semantic-version changes restart the bounded scan through persisted desired versions; no request runs candidate × catalogue calculation.

Bootstrap existing jobs in ID batches and persist neutral facts/events. A candidate's initial backfill first records the committed catalogue watermark, then records the current fact-table high-water job ID. Scan at most 25 current jobs at a time, with job-ID keysets. After the initial scan, consume catalogue changes after the captured watermark in batches of 25; coalesce repeated job changes to the latest fact. This covers concurrent inserts/updates, including lower generated job IDs that commit later. Older matching generations remain historical.

Workers claim candidate state with `FOR UPDATE SKIP LOCKED` in a short transaction, increment a fencing token and set a 60-second lease. Calculate a bounded batch outside database locks. Only one active lease owns a candidate's next cursor batch. An expired worker can continue calculating but loses publication authority. Retry failures at most five times with capped backoff; persistent failures become visible FAILED state and require a deliberate local/operator retry or a new profile/semantics generation. Do not endlessly poison the queue.

Publication transaction lock order is candidate -> candidate_matching_state -> job/fact-state rows in ascending job ID. Revalidate lease token, desired and active completed profile revision, scorer/extractor versions and every current fact identity before publishing. Insert immutable results idempotently; update current pointers, candidate publication revision and work cursors atomically. If a prerequisite changed, discard/requeue that batch without advancing its cursor. Claim transactions take only the state row and release it before requesting candidate/job locks, avoiding the reverse lock order against profile edits. Catalogue fact publication locks job then the global catalogue revision row; it never acquires candidate locks.

A crash before publication leaves a reclaimable lease and unadvanced cursor; a crash after commit leaves complete results and advanced progress. No mixed partial batch can be marked complete. Profile saves invalidate old leases. Workers never call notification services or update delivery ledgers.

Initial defaults: matching disabled unless explicitly enabled in normal runtime; one worker; 25 jobs/batch; one batch/tick; one-second tick delay; 60-second lease; five attempts; five-second initial and five-minute maximum retry delay; API page default 25/max 50; fact-bootstrap batch 100. Validate finite configuration ceilings. Test enabling matching explicitly. Recovery overrides matching and fact-bootstrap to false and rejects unsafe overrides before startup events.

## API v2

Keep existing session/authentication, no-store, expiry and admission rules. No candidate selector, legacy score fallback or public personalized route.

- `GET /api/mini-app/v2/me/matching`: active profile revision, required extractor/scorer versions, NOT_READY/PROCESSING/READY/FAILED state, bounded progress counts and publication revision. No other candidate details.
- `GET /api/mini-app/v2/matches?limit=25&cursor=...&eligibility=...`: current owned match rows, each with shared vacancy summary, private workflow status, eligibility, valid compatibility/recommendation score, check codes/explanations and complete computation identity. Default includes ELIGIBLE and clearly labelled UNKNOWN, ordered separately; explicit filter can inspect INELIGIBLE. No fallback when results are missing.
- `GET /api/mini-app/v2/jobs/{jobId}`: shared vacancy details plus only the current candidate's valid matching projection and workflow. Missing/stale matching returns a null match with computation state. Missing vacancy is 404. No arbitrary candidate/result ID route.

The existing IdentityWebBoundary rejects every query string, so change only the exact matches GET route to allow a small explicit set of validated scalar query fields. Reject duplicate, unknown, oversized or malformed parameters; retain credential/body prohibitions and all other route rules. Queries interpolate no client SQL fragments.

Use keyset ordering `(eligibility group, compatibility DESC, published_at DESC, job_id DESC)`. Cursor captures profile/semantics versions, candidate publication revision, catalogue change revision, filter and final tuple; it does not select the owner. Scope every SQL statement by authenticated candidate regardless of cursor data. If those version stamps changed, return typed 409 MATCH_PAGE_STALE and restart; never stitch together silently changing score orders. Session-bound cursor authentication can use existing server-known session hash with JCA HMAC rather than new deployment secrets. Cap cursor length and parse before SQL. Stable-page tests cover equal scores/timestamps, concurrent publication, profile edits, malformed/foreign cursors, ownership and no duplicate tuples.

## Frontend

Extend beta workspace with Recommended beside Catalogue/Saved/Applications. Use a dedicated generation-bound v2 matching client, following current account.ts/searchProfile.ts credential capture and post-response checks. A matching request finishing after account replacement must not paint, invalidate, retry or mutate the new account.

Show PROCESSING/backfill state and current validated results without fabricated placeholder percentages. Show UNKNOWN prominently, with evidence-based missing checks; show hard blockers without a recommendation percentage. Explain neutral score components and compatibility coverage. For stale matching, clear the percentage until recomputed. A stale-page response resets pagination visibly and safely.

Use existing workflow mutation/undo queue for save/dismiss/applied; recommended rows join or refresh through the same per-job state, so controls respect pending and ambiguous writes even if a row leaves the current recommendation page. Keep neutral catalogue browsing available. Do not alter owner v1 navigation or notifications. New manual country authorization inputs save a new profile revision and preserve draft/conflict behavior.

## Verification and release gates

Before Phase 4 completion, run all existing backend/unit/PostgreSQL/frontend tests, typecheck, lint, API production build and artifact self-checks. Preserve exact pass/fail/error/skip counts; reject skipped required integration suites. Add the 20 requested Phase 4 acceptance cases, synthetic cross-category scoring fixtures, query/body boundary tests and deterministic concurrency barriers for profile/fact races, lease expiry, restart and late publication. Run EXPLAIN ANALYZE on disposable seeded PostgreSQL; report actual throughput and memory rather than treating estimates as measurements.

Required migration rehearsal: deployed V13 fixture -> V19; compare historical rows, identities, values, timestamps, delivery/bot state and V1–V18 checksums. V18 image/evidence remains untouched and is explicitly incompatible with committed V19. Extend strict schema/recovery guards, artifact packaging, required-suite gate and actual-image rehearsal to exact V19; reject V20, disabled Flyway, changed history and unsafe recovery settings.

Save the new local-only V19-aware image and checksummed archive after source freeze. Rehearse normal -> failed release -> recovery -> normal on real disposable PostgreSQL after beta matching/profile/workflow histories exist. Fingerprint all new match/fact/work-state tables and older private tables. Owner workflows must work while beta is closed; all polling, sends, scheduling, ingestion, matching, bootstrap and maintenance must remain off. No destructive downgrade, deletion of beta records or image publication.

The final independent review covers authorization, blockers, concurrency, stale publication, migration preservation, legacy notifications and recovery. Reproduce/fix Critical or High implementation findings before completion. A Critical/High preflight security finding stops Phase 4 before implementation, as requested.

## Capacity estimate and explicit limits

With C active candidates, J new/changed vacancies and H stored current vacancies, steady work is approximately C × J deterministic evaluations; a profile change costs at most H evaluations for that one candidate. One new vacancy is extracted once, not once per candidate. Historical identity checks avoid recomputing the same candidate/profile/job/fact/semantics identity.

At 50 candidates and 20,000 current jobs, a full first-generation backfill is one million evaluations; at 1,000 candidates it is 20 million. Fifty candidates and 500 changed vacancies/day is 25,000 daily evaluations; 1,000 candidates gives 500,000. These are arithmetic estimates, not measured throughput. Unbounded repeated profile revisions can grow historical results and consume backfill work; per-candidate claim fairness, bounded batches and coalescing to the latest desired revision prevent a stale-work backlog from starving the latest profile. Preserve historical data; do not add an automatic purge in Phase 4.

Memory is O(batch × bounded fact/profile payload), never O(C × H). A worker holds one profile and at most a small batch of facts/results. Benchmark a small Azure-like CPU/memory limit locally; measure SQL indexes, log growth, average/p95 evaluation time, publications/sec, backlog drain time and maximum heap before claiming a capacity target. The singleton catalogue publication lock serializes only catalogue changes, not candidate computation; measure its contention before changing it. No Redis, broker or parallel VM topology is justified at the initial 10–50-user beta without measurements.

Local Phase 4 completion remains NO-GO until implementation, independent review, full verification and V19 image rehearsal pass. Production remains separately NO-GO until approved Azure operational checks and deployment approval, even after local completion.

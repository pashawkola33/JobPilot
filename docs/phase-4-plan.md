# Personalized Matching Implementation Plan

**Goal:** Deliver the approved Phase 4 design locally with existing owner alerts and digest preserved.
**Architecture:** Shared versioned vacancy facts, deterministic candidate evaluation, PostgreSQL incremental work and authenticated candidate projections. Legacy owner screening/scoring/sends remain separate.
**Tech stack:** Existing Java 21, Spring, JDBC/Flyway/PostgreSQL, React/TypeScript/Playwright; no new dependencies.
**Spec:** docs/phase-4-design.md plus the user's four mandatory conditions and notification clarification.

## Constraints and execution

The user explicitly approved local implementation and tests. Execute natively in the existing linked worktree, without further approval pauses, commits, pushes, worktree changes, production/Azure access, deployment or Phase 5. Preserve every prior recovery artifact and V1–V18 byte-for-byte. Retain this ledger/evidence; do not apply skill commit/cleanup defaults.

## Review focus

- Catalogue-only records must never leak into legacy notification/recommendation paths; later first legacy admission must still send normally.
- Missing skills and authorization are unknown, with self-stated evidence never described as verified résumé facts.
- Country, remote restrictions and authorization are independent; ambiguous qualifications stay UNKNOWN.
- Lease expiry/profile edits/fact edits must fence stale publication, including concurrent commit ordering.
- Retaining previously rejected vacancies increases storage/work; measure the difference and prove finite batches.

## Tasks

- [x] 1. Tests first: add Phase4EngineTest for cross-category profiles, blockers/unknowns, required/preferred skills, monotonic extra skills and compatibility/coverage. Implement VacancyFacts, VacancyFactExtractor, PersonalizedMatcher and scoped authorization profile field. Run focused tests RED→GREEN.
- [x] 2. Tests first: migration V19 assertion, persistence/ownership/claims/revision/restart/backfill tests in PersonalizedMatchingIT and V19MatchingMigrationIT. Add V19 additive schema and immutable histories, MatchingCatalogue, MatchingWorkService and MatchingWorker. Validate short claims, bounded publication, fencing, committed catalogue ordering and recovery disabled behavior on PostgreSQL.
- [x] 3. Tests first: catalogue admission/dedup/legacy notification differential assertions. Extend JobProcessor rejected persistence while preserving owner scoring and first legacy admission semantics; record neutral facts transactionally on every meaningful job update. Keep Workday and source request limits. Run ingestion/notification regressions.
- [x] 4. Tests first: authenticated GET /me/matching, /matches and /jobs/{id}, strict query validation, owned details, versioned keyset pagination. Add PersonalizedMatchingController and Query service; never substitute global scores. Run cross-user/expired/stale-page tests.
- [x] 5. Tests first: matching browser fixtures for UNKNOWN/high compatibility/coverage, processing/stale scores, saves, expiry/account replacement. Add generation-bound matching client and beta Recommended UI; preserve owner UI and existing workflow queue. Add scoped authorization editor fields. Run full browser/type/lint/API build.
- [x] 6. Extend exact schema/artifact/recovery guards to V19; retain V18 images and prove they reject V19. Extend required test gate, migration checksums, full historical fingerprints and actual-image normal→recovery→normal/unknown V20 rehearsal. Run focused recovery RED→GREEN, then full suites.
- [ ] 7. Measure catalogue-only row/storage growth, evaluation/worker/backfill throughput, query plans and bounded memory on disposable synthetic PostgreSQL. Run independent final security/correctness/concurrency/notification/recovery review; reproduce/fix Critical/High findings. Freeze source and build/rehearse final local image; save full final evidence and separate local/production verdicts.

## Interfaces

VacancyFacts describes neutral source/requirements and provenance; PersonalizedMatcher.evaluate(SearchProfile,VacancyFacts,Instant) returns immutable Match with eligibility, raw compatibility, recommendationScore, evidenceCoverage and typed reasons. MatchingCatalogue.capture(Job,RawJob) publishes fact revision plus commit-ordered event in the caller transaction. MatchingWorkService.claim/process/publish uses leased candidate progress and validates active profile/facts/semantics at publication. Query APIs consume only revalidated AuthenticatedCandidate and valid versioned results. Worker has no notification dependency.

## Ledger

- Preflight complete: 762-file checkpoint; V1–V18 and prior V17/V18 artifacts hash-verified; independent review found no Critical/High; baseline 1239 unit pass/1 existing skip, 152 PostgreSQL pass/0 skip, 248 frontend pass/4 existing skips.
- Ruling: explicit implementation approval authorizes native execution of this plan without another approval gate. User no-commit and evidence-preservation requirements override skill commit and cleanup defaults.
- Shared-interface audit: engine result/fact identities feed persistence and API; owned API feeds UI; V19 schema boundary feeds packaging and recovery. All use extractor/scorer version constants and immutable profile revision identity.

- Engine: 10 deterministic cases now cover aliases/unknowns, hard blockers, alternatives/negation, salary and education. Initial missing API failures and subsequent green logs are retained.
- Persistence/API: PostgreSQL claim/concurrency/profile/fact/lease and owned cursor tests pass. V19 preserves V13 rows and V1–V18 checksums.
- Ruling: retain the neutral Catalogue as beta's initial tab and add explicit Recommended beside it, preserving existing beta navigation and pending workflows.
- Phase 5 planning only: later extend the existing Telegram experience per user with configurable compatibility thresholds, immediate alerts, optional daily digest and candidate-scoped delivery deduplication; historical/profile backfill must not send. No Phase 5 behavior is implemented.

- Ruling: add optional UNKNOWN/GROSS/NET salary basis, preserving old payloads as UNKNOWN. Same currency/period alone does not establish a safe gross/net comparison.
- Provider source inputs are retained without raw response blobs; bootstrap reuses them so it cannot erase richer applicant restrictions. Country lists express alternatives.
- Actual-image normal rehearsal enables only the matching worker; synthetic fetch/digest cron are explicitly disabled and all Telegram/network sends remain off. Production configuration is unchanged.

- Current Phase 3 request replay is compared after decoding/canonicalizing absent optional fields; immutable historical JSON remains unchanged. Regression reproduces the old raw-string comparison conflict.

- Independent final reviews reproduced three initial High and three Medium matching findings; follow-up reproduced two High and one Medium variants. All have focused red/green regressions. Original reports/probes are retained; final independent closure and frozen-source verification are required before completion.
- Bound fix: requirement insertion centrally reserves a mandatory truncation marker; source values/quotes are capped. A recognized substring cannot erase an unsupported mandatory clause. Education possession requires an exact comparable self-statement, and structured suffix scope is retained.
- Artifact metadata is V19/phase4; actual-image evidence output refuses existing paths. Final reports are stored outside source so verification identity stays stable.

- Final independent closures: security follow-up closed output cap, V19 metadata and evidence-preservation issues; matching final compiled current sources and passed16 assertions with all reported High/Medium findings closed. Focused final engine22/22 and PostgreSQL13/13 passed; final frozen-source full verification/image rehearsal pending.

- Final clean-install audit exposed two inherited dev-only transitive advisories. Lockfile updates only nanoid3.3.17→3.3.20 and source-map-js1.2.1→1.2.2; npm audit now reports0 vulnerabilities. Preserve the earlier verification attempt; final artifact uses a new r2 output directory and repeats all checks.

- Final metamorphic probe reproduced a numeric-experience negation false blocker (3years is not required). The exception now applies only to exact explicit no-experience clauses; numeric negation stays UNKNOWN. Regression RED→GREEN23 engine cases. Earlier r2 image/rehearsal remains preserved as intermediate; final source/image/evidence use r3.

- Independent final isolation probe reproduced a High leak from historical jobs.status through shared facts. VacancyFacts now restricts availability to OPEN/EXPIRED on construction/deserialization, retaining old stored JSON unchanged. Real PostgreSQL tests cover all6 private legacy states and existing persisted APPLIED facts;23 engine and15 matching integration tests passed. Earlier r2/r3 artifacts stay preserved; final source/image use r4.

- Final source-format review reproduced a High false blocker: legacy city inference added an office restriction to an explicit worldwide-remote vacancy. Neutral extraction now uses exact positive source wording, marks explicit office contradictions UNKNOWN, and preserves independent blockers. Legacy normalization/scoring/Telegram are unchanged. Focused engine and PostgreSQL pipeline regressions precede final r5 verification/image rehearsal; r4 evidence remains preserved.

- Neutral-format closure: independent32/32 compiled probes, engine28/28 and PostgreSQL matching16/16 passed. Actual retained-ingestion/API test proves cross-country eligible neutral remote with legacy ONSITE/pending admission, no owner score and no Telegram delivery. Final r5 source freeze now begins; final full verification and image results are recorded externally to preserve that source identity.

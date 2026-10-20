# Phase 3 implementation plan

**Goal:** Collect explicit candidate job-search preferences, persist immutable revisions, and support onboarding/editing without changing matching or résumé truth.

**Architecture:** Separate `search_profile_revisions` from verified `candidate_profiles`; `search_profile_state` holds one candidate-owned active revision. Composite foreign keys enforce ownership. Serialize activation by locking the authenticated candidate row; compare expected revision after checking the candidate/request idempotency key. JSON payloads are typed and normalized at the API boundary, stored as text for PostgreSQL/H2 compatibility. PostgreSQL rejects revision updates/deletes with an immutable-record trigger. No candidate ID is accepted by the API.

**Spec:** The user-supplied Phase 3 request. It authorizes implementation and defines preservation, security, local-only and no-commit constraints; those override skill commit/approval defaults.

**Tech stack:** Existing Java 21/Spring/JDBC/Flyway/PostgreSQL and React/TypeScript/Playwright; no new dependencies.

## Decisions

- Required for completion: at least one desired title or role category, at least one target region, and at least one work format. Skills, experience, location, employment, languages, authorization and optional fields can be explicitly unstated/unknown. A draft revision may be saved while incomplete; onboarding remains required.
- Candidate has: technical/nontechnical skills, self-stated experience, location, languages, authorization, education/student status. Candidate wants: roles, industries, targets, work formats, employment, optional salary and relocation. Nothing inferred or copied into résumé facts.
- Language level and work authorization use explicit UNKNOWN enums; omitted levels normalize only to UNKNOWN. No default language, country, experience or qualification.
- Drafts stay in account-scoped component memory, survive steps/errors and are discarded on identity replacement. Reload restores server-confirmed preferences after fresh Telegram authentication. Do not persist credentials or private drafts in browser storage.
- Legacy owner keeps v1 workspace without onboarding blocking and can opt into profile setup/editing. Beta users complete onboarding before entering existing workspace. Catalogue remains neutral; saved preferences do not affect ranking.
- New runtime supports exact validated V18, including recovery mode; preserved V17 image remains exact V17 and must reject V18. Future V19/corrupt history fails. Verify normal→recovery→normal on disposable PostgreSQL with beta profile/workflow fingerprints; production stays NO-GO without an independently retained/rehearsed V18 artifact and operational approval.

## Tasks and checks

- [x] Verify branch/base, inventory all tracked/nonignored files, inspect existing implementation/docs and archive/hash source excluding .env/secrets. Retain verified V17 evidence durably.
- [x] Write failing PostgreSQL API tests: two users; no foreign reads/writes; session failures; required fields/unknowns; salary validation; stale expected revision; concurrent writes; duplicate request; history and résumé facts unchanged.
- [x] Implement V18, typed SearchProfile models/service/controller, identity body allowlist and /me completion. Run focused PostgreSQL suite to green.
- [x] Write failing V13→V18 preservation and immutable/ownership tests; extend existing V17 tests to explicitly migrate to V17 before testing latest runtime. Implement/test exact V18 runtime/recovery configuration and future-schema refusal.
- [x] Write failing browser onboarding/edit/reload/expiry/conflict/generation tests. Implement account-bound transport, questionnaire, review/save, owner opt-in, beta gate and mobile styles. Update prior beta fixtures to represent completed profiles. Run focused browser suite to green.
- [ ] Run full Maven clean verify, required PostgreSQL report guard, frontend tests/typecheck/lint/API build, recovery artifact self-checks. Inspect exact pass/fail/skip counts.
- [x] Independent read-only security and migration/recovery reviews; fix important findings with regression evidence.
- [ ] Record exact Phase 3 modified files, isolation/migration/checksum evidence and recovery constraints, local and production GO/NO-GO. No commit, push, deployment, Azure/production access or Phase 4.

## Review focus

- Ambiguous save response: retry the same immutable request key, keep expected revision and draft; no duplicate activation.
- Late responses after account switch cannot update UI, invalidate new sessions or retry with new credentials.
- Empty optional facts stay unknown; invalid enum, currency, amount/period, oversized collections and null elements fail validation.
- Historical request replay after a newer edit must never reactivate an old revision.
- Rollback cannot remove beta data: retain all search/workflow histories and refuse unsupported future migrations.

## Review and execution record

- API/migration RED: absent profile routes returned 404 and latest migration remained V17; recovery V18 startup failed under the prior exact-V17 guard.
- Initial PostgreSQL GREEN: 17 tests, zero failures/errors/skips.
- Browser RED: absent questionnaire; focused GREEN: 12 tests, zero failures/skips/flaky.
- Independent security review reproduced Jackson numeric/scalar coercion; API regression returned 200 before fix and 400 afterward, leaving revision unchanged.
- Independent migration review found normal-mode disabled-Flyway bypass; regression failed before fix and passed after guard enforcement.
- Review-fix verification: 23 recovery configuration checks and 12 API/recovery lifecycle integration cases passed. Both search tables now participate in beta recovery fingerprints.
- V1–V17 migration input hashes and retained V17 artifact hashes independently matched the preflight inventory.
- Ruling: keep all source/evidence uncommitted and retain the plan rather than following skill commit/cleanup defaults, as explicitly required by the user.
- Full artifact verification and actual-image/browser rehearsal are recorded after source freeze in private durable evidence; this file intentionally does not change afterward.

- Final inspection found a conflict banner disappearing after step navigation/edits, leaving saving disabled without reload. Extended browser regression reproduced the hidden action; the banner now derives from persistent conflict state. The final artifact is refreshed from this corrected source, retaining the earlier build separately as superseded evidence.

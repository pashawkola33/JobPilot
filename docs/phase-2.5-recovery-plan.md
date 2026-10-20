# Phase 2.5 recovery implementation plan

The approved task is release safety only. Preserve all uncommitted Phase 1/2 work, V1–V17 and production. No commits, pushes, Azure access, deployment, public registration or Phase 3.

## Decision

Use a candidate-aware build from the verified Phase 2 code, not a schema bridge for the Phase 1 binary. Preserve all users' private rows. Existing `IDENTITY_REGISTRATION_ENABLED=false` already denies new and existing beta sessions while the explicit owner remains admitted. Add a dedicated `recovery` profile that keeps owner Mini App access enabled and turns off beta admission, scheduled work, Telegram sending/polling and maintenance. Reject overrides that would make that recovery mode unsafe.

Pin the recovery artifact to V17 and fail startup before serving/scheduling when schema history is beyond the supported boundary, migrations/checksums are incompatible, or unsafe recovery configuration is supplied. A migration that fails transactionally leaves a supported schema; a successfully committed future migration needs a forward corrective path or coordinated restore, never an old binary forced onto unknown data.

The recovery artifact is an immutable local image/archive plus JAR/source hashes and a manifest. No publication is authorized. The manifest records the actual uncommitted source state; a base Git SHA alone cannot identify this build. Production remains one app container, one poller and the existing managed PostgreSQL/document storage. A candidate-aware normal-mode instance can resume beta users after controlled recovery.

## Tasks and evidence

- [x] Inspect current worktree, Phase 1/2 docs, V14–V17 and prior rollback evidence.
- [x] TDD runtime recovery profile/configuration/schema guards; preserve ordinary Phase 2 behavior. Focused configuration suite: 22 passed.
- [x] Disposable PostgreSQL recovery integration: seed V13, migrate V17, owner plus two beta accounts create independent state/history/undo; fail a subsequent startup; recover unchanged DB with beta admission off; prove owner operations, exact beta row preservation, no notification replay, restored beta operations. Recovery boundary: 3 passed.
- [x] Test future V18 incompatibility and startup failure explicitly; retain V17 migrations unchanged. Additional PostgreSQL schema boundary suite: 8 passed.
- [ ] Produce a reproducible local candidate-aware recovery artifact and verify it starts against disposable PostgreSQL; no registry push.
- [x] Write deployment/recovery runbook: backup and document coordination, isolated restore, owner binding, maintenance/locks, image identity, replacement, poller ordering, Caddy restrictions, health, beta gates, forward repair/full restore and explicit RPO.
- [ ] Full backend/PostgreSQL/migration/frontend checks; independent focused recovery review; exact counts and preparation GO/NO-GO. Stop at Phase 2.5.

Production approval remains separate. Azure network/TLS, backup/PITR availability, capacity, document restore, proxy configuration and owner identity cannot be certified by local synthetic tests.

Independent review found two Medium issues: API mode inherited by mock frontend tests and an omitted startup rescore-preview setting. Both were reproduced and fixed with RED/GREEN tests; no Critical/High findings remain. Artifact script self-checks: 11 passed. The integration-report gate now requires both recovery PostgreSQL suites and rejects missing, failed or skipped reports.

The remaining artifact/full-suite tasks are recorded outside this frozen source snapshot in `/private/tmp/jobpilot-phase2.5/`: `artifact/verification.json`, `artifact/image-manifest.json`, `image-rehearsal.json` and the final verification record. This avoids changing the source identity after the artifact has been verified.

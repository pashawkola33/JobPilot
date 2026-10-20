# Phase 2.5: candidate-aware release and recovery runbook

Status: **production deployment NO-GO until a separately authorized production change passes every gate below**. This document prepares that change; it does not authorize Azure access, production configuration changes, publication, deployment, public beta or Phase 3. Local synthetic evidence cannot verify Azure networking/TLS, permissions, backup retention/PITR, capacity, document storage, real owner identity or the live Caddy configuration. All those conditions remain **UNVERIFIED**.

## Recovery boundary

Production currently has the older application, V13, one application container, managed Azure PostgreSQL and one Telegram poller. Use the existing managed database; do not start the repository Compose PostgreSQL service on production. The checked-in production Compose file describes a local PostgreSQL service, sets `SPRING_PROFILES_ACTIVE=prod`, and does not pass all identity settings: it is not a ready-to-run managed-Azure release definition for this change. Prepare and review the actual environment/volume/image/profile mapping as a separate authorized production change.

The known-good recovery artifact is this **candidate-aware Phase 2 build at V17**. The preserved Phase 1 binary was observed returning owner snapshot HTTP 500 after beta writes even when admission was disabled and sessions revoked. The older V13 production binary is also uncertified against V17. Neither is a recovery target after cutover. Do not delete beta rows, drop ownership columns, change applied migrations, clear histories, or run Flyway `clean`/`repair` to make old code start.

The image defaults to the `recovery` Spring profile: owner Mini App access, beta admission closed, scheduled jobs, Telegram polling/sends and maintenance disabled. Unsafe overrides fail startup. Recovery operationally requires the supported, validated **V17** schema; the candidate-aware artifact's schema guard runs after Flyway and fails closed for a future V18 schema in both normal and recovery modes. The profile's Flyway target does not itself forbid migration from an earlier version; establish V17 before using incident recovery. Keeping beta access closed preserves private rows and identities; it does not undo V17. The same verified candidate-aware binary may run normal mode through an explicit profile override after the normal-mode configuration and schema have been approved. Ordinary normal mode is not a promise of compatibility with future migrations.

## Produce and retain the local artifact

Use Java 21, Node 22 (at least 22.12), Python 3, the existing Maven wrapper and a working Docker daemon. Install the project's existing Playwright Chromium if missing; do not skip browser or PostgreSQL suites. Use the established local Maven/npm/Playwright caches through their normal environment variables. No new service is required. The build does not read production configuration or require production credentials.

Freeze the intended worktree files, including uncommitted/untracked Phase 1/2/2.5 source, before verification. Do not commit, reset, rebase or merge the newer main README into this worktree to obtain a build identity. `git HEAD` alone omits the implementation. The script hashes sorted tracked and nonignored untracked file paths, file bytes and executable modes, excluding deleted files; symlinks are rejected. Ignored output/cache files are excluded. The source inventory and deterministic source tar are retained with the artifact. Any subsequent source edit invalidates image creation from that proof.

From the isolated worktree:

```sh
python3 scripts/test-recovery-artifact.py
python3 scripts/build-recovery-artifact.py source-hash
python3 scripts/build-recovery-artifact.py verify \
  --output /private/tmp/jobpilot-phase2.5/artifact
```

The output directory must be new and outside the checkout. Verification copies an exact source snapshot there, runs `npm ci`, typecheck, lint and all Playwright tests with explicit mock mode for the base UI server (the API test server selects API mode itself), then an explicit `VITE_JOBPILOT_MODE=api` production frontend build. These values override any caller mode. It copies those assets into the staged application resources and runs `./mvnw -B --no-transfer-progress -Dapi.version=1.44 clean verify` plus the required PostgreSQL-report guard. No test-skip build is accepted. It checks that the Spring Boot fat JAR contains the candidate-aware workflow, V17 Java migration, recovery profile and the exact freshly built JS/CSS/HTML assets. Verification failure leaves diagnostic logs and no passing proof; correct the failure and use a new output directory.

Resolve a real digest from the target JRE image before building the runtime. Pulling a public base is allowed for this local build; pushing is not:

```sh
docker pull --platform linux/amd64 eclipse-temurin:21-jre-alpine
docker image inspect eclipse-temurin:21-jre-alpine \
  --format '{{json .RepoDigests}}'
# Copy the actual eclipse-temurin@sha256:... value printed above:
python3 scripts/build-recovery-artifact.py image \
  --verification /private/tmp/jobpilot-phase2.5/artifact/verification.json \
  --base-image eclipse-temurin@sha256:<actual-resolved-digest>
```

Replace the angle-bracket placeholder; it is deliberately invalid as written. The base must be the Alpine Java 21 JRE family because the small runtime Dockerfile uses Alpine user-management commands. `build --output <new-dir> --base-image <resolved-digest>` combines verification and image creation. Default/only target is `linux/amd64`, matching the intended x86-64 deployment; local Docker may emulate it under QEMU. Confirm the actual production architecture separately. No Maven or Node build runs inside Docker. The two-file image context contains only the JAR and runtime Dockerfile; the Docker build checks the supplied JAR SHA before accepting it.

Retain `image-manifest.json`, `verification.json`, `source-files.json`, `source.tar`, `jobpilot.jar`, verification logs and `jobpilot-recovery-image.tar`. The manifest records the dirty-source SHA, JAR SHA, pinned base digest, target architecture, local image ID and SHA-256 of the saved image archive. Verify the archive checksum before `docker image load`; compare the loaded ID with the manifest. Run/inspect by that exact image ID, not an easily moved tag. The source hash establishes the inputs and hashes establish the actual bytes; repeated builds may produce different image/archive bytes as tooling metadata changes, so each rebuilt artifact needs its own manifest and rehearsal. This procedure does not claim bit-for-bit repeatability of Docker layers.

**Identity distinction:** `sha256:...` from `docker image inspect .Id` is a local image/config identity. The SHA-256 of `docker save` is a file checksum. Neither is a registry `RepoDigest`. `registry_digest` remains null until a separately authorized push and registry inspection actually occurs. Do not invent a registry digest or claim a commit tag alone identifies uncommitted source. No publishing/deployment command is part of this procedure.

Before approving production preparation, root's disposable rehearsal must start the **actual saved image** by ID against synthetic V17, first normal mode, then default recovery mode, then normal mode again. Verify default profile, health and startup fences in addition to the Java integration suite. Static JAR checks alone do not establish successful image startup. Record that evidence and its image ID beside the manifest. Local artifact availability in `/private/tmp` is temporary; copy it only to an approved private durable artifact location under separate authorization before relying on it for an incident.

## Gate 1 — recovery points and rehearsal

1. Inventory production without changing it: running image ID/reference, deployed configuration revision, managed PostgreSQL server/database/version, actual V13 Flyway rows/checksums, document storage root/mount, UID/GID, Caddy routes, Telegram persisted polling offset and historical delivery ledger. Do not log secrets, signed launch data or bearer tokens.
2. Verify managed backup retention, most recent successful backup and PITR range, encryption/keys, restore operator permissions and expected recovery time. Choose and record an explicit recovery point and its RPO. A visible backup entry is insufficient: restore it to a new isolated managed database and prove it is readable. Never rehearse against the original database.
3. Coordinate private document recovery with the database. Inventory every stored document reference, storage key/content hash and corresponding file, including resume/cover-note versions. Take an access-controlled, checksummed private artifact snapshot with the DB checkpoint. Where no atomic cross-store snapshot exists, stop writers first and record the common quiesced time; copy/verify the documents before releasing maintenance. Retain encryption keys and permissions required to read those bytes. DB-only recovery can leave dangling references or expose the wrong document if storage keys are reused.
4. Restore DB plus private artifact snapshot to isolated storage with no route to the real bot, no poller/sends/scheduler/maintenance, synthetic or absent bot credentials, and a loopback/private application listener. Verify object counts, IDs, ownership, histories, timestamps and document content hashes, not only table counts. Review access/retention of private data used for rehearsal.
5. Rehearse the **actual V13 production data shape** through all V14–V17 migrations with the intended image and PostgreSQL version/settings. Verify checksums and startup, owner workflow reads/writes/undo, existing documents, beta isolation, recovery switch and normal resumption on that unchanged V17 database. Compare pre/post preservation fingerprints. Local synthetic V13 fixtures supplement this rehearsal; they do not replace it.
6. Measure migration wall time, lock waits, disk/connection headroom and restore time. Agree a maintenance window and abort deadline based on this data. Record exactly who may choose forward repair or restore if startup fails. All Azure portions remain UNVERIFIED until separately authorized and completed.

## Gate 2 — reviewed configuration and owner binding

Secrets stay in the existing protected configuration store/private environment file. Do not paste them into shell history, logs or this repository. Review the effective values delivered to the container; a Compose `environment:` entry can override an image default, including an empty value. Preserve baked `BUILD_COMMIT`, `JOBPILOT_VERSION` and source labels; the source SHA in the manifest identifies dirty code.

| Setting | Initial/recovery requirement |
|---|---|
| `SPRING_PROFILES_ACTIVE` | Recovery must explicitly contain `recovery`; do not let the old Compose `prod` override silently disable it. For initial V13 migration use the candidate-aware normal profile with every background/admission flag below off; recovery itself is for V17. |
| `DATABASE_URL`, `POSTGRES_USER`, `POSTGRES_PASSWORD` | Existing managed Azure database, approved TLS/CA settings, correct private network and least-privilege role with migration rights during the window; never the local Compose `postgres` service. |
| `LEGACY_TELEGRAM_USER_ID` | Independently verified positive numeric Telegram **user** ID of the human owner. |
| `MINI_APP_API_ENABLED`, `MINI_APP_ALLOWED_USER_IDS` | `true`, verified owner ID only. The v1 owner needs a valid signed Telegram launch. |
| `IDENTITY_API_ENABLED` | `true` for account/session routes and the public-runtime private-route boundary. |
| `IDENTITY_REGISTRATION_ENABLED` | `false` throughout migration, validation and recovery. Existing beta sessions are denied by current policy too. |
| `IDENTITY_BETA_ALLOWED_USER_IDS` | Empty until controlled invitation rollout; preserve previous list privately for later approved restoration. Never add beta IDs to v1 allowlists. |
| `IDENTITY_CREDENTIAL_EPOCH` | Approved recorded value. Increment only deliberately to invalidate all existing sessions; do not rewrite stored session hashes or replay markers. |
| `JOBPILOT_SCHEDULING_ENABLED` | `false`. |
| `TELEGRAM_BOT_ENABLED`, `TELEGRAM_COMMANDS_ENABLED` | `false`; no active poller. |
| `TELEGRAM_MATCH_NOTIFICATIONS_ENABLED`, `TELEGRAM_REVIEW_DIGEST_ENABLED` | `false`; no sends. |
| `TELEGRAM_CHANNEL_ID` | Empty in recovery; its guard rejects a configured channel/destination. Configure any later normal-mode delivery only after the private-owner destination review. |
| `TELEGRAM_ALLOWED_USER_ID`, `TELEGRAM_ALLOWED_CHAT_ID`, `TELEGRAM_ALLOWED_CHAT_IDS` | If later enabled, reviewed owner private-chat destinations only; group/channel destinations cannot carry legacy owner content. |
| `MAINTENANCE_ENABLED`, `LLM_ENABLED`, `SCRAPER_WORKER_ENABLED` | `false`. No incidental cleanup, external enrichment or background worker during cutover/recovery. |
| `JOBPILOT_SCORE_RESCORE_COMMAND_MODE`, `JOBPILOT_SOURCE_LOG_CLEANUP_MODE` | `OFF`; both associated `*_WRITE_ENABLED=false`; preview disabled. |
| `DOCUMENT_STORAGE_ROOT`, `DOCUMENTS_ENABLED` | Existing private mount with UID/GID 10001 access and verified backup. Preserve the normal document capability only after restore/ownership checks; do not point it at empty ephemeral storage. |

Verify the owner ID from trusted Telegram account evidence or a separately verified signed launch with the real bot, cross-checked with the owner's existing private bot interaction. A username, channel ID, HTTP request parameter, candidate row chosen by registration order or synthetic test ID is not sufficient. Read `telegram_identities` and the established `default` candidate relationship when present. The application refuses a conflicting persisted binding; do not edit that binding to bypass the refusal. Resolve the identity discrepancy before any interactive surface is opened. `MINI_APP_ALLOWED_USER_IDS` and legacy command user/private-chat settings must agree with that owner.

## Gate 3 — Caddy and maintenance

Prepare/review Caddy in advance; activate only in the approved window. Public traffic must have an explicit allowlist:

| Surface | Allowed public paths/methods |
|---|---|
| Frontend | `GET`/`HEAD` `/mini-app/` and its packaged static assets. |
| Legacy owner | `GET /api/mini-app/v1/snapshot`, `PUT /api/mini-app/v1/jobs/{id}/workflow`, `POST /api/mini-app/v1/undo`. |
| Identity | `POST /api/mini-app/v2/auth/telegram`, `GET /api/mini-app/v2/me`. |
| Candidate workflows | `GET /api/mini-app/v2/snapshot`; `PUT /api/mini-app/v2/jobs/{id}/workflow`; `GET /api/mini-app/v2/jobs/{id}/application`; `POST /api/mini-app/v2/undo`; `GET /api/mini-app/v2/applications/{id}` and `/{id}/history`; `PUT /api/mini-app/v2/applications/{id}/notes`, `/follow-up`, `/status`. |

Match numeric path IDs and exact suffixes. Reject all other routes by default, including `/internal/**`, source health, `/api/sources/**`, `/actuator/**`, health/admin/maintenance/document/internal application endpoints and broad `/api/**` prefixes. Keep `/health` reachable only on loopback/private operations access. Application denials supplement the proxy boundary. Forward the necessary authorization and signed-init-data headers without access-log capture; do not add credential query strings or cache private API responses. Keep beta workflow routes closed during migration/recovery if the reviewed Caddy rules permit per-surface closure; retain owner v1 and needed identity routes. The backend admission flag is required even when Caddy closes routes.

During maintenance reject public mutations (or serve a maintenance response), stop the **old V13 application**, and wait for graceful shutdown and in-flight DB/Telegram work to finish. Confirm its container/process cannot auto-restart; disable any external restart/controller for that old instance. Identify and stop any second workstation/VM poller using the same token. Do not run a second instance for a rolling deployment. Confirm no remaining application writers, fetch jobs, sends or schema migrators in PostgreSQL activity and local process/container inventory. This change permits downtime.

Take the final coordinated, checksummed database plus artifact checkpoint after quiescence; preserve the old image/configuration as evidence, not as an assumed V17 recovery target. Record delivery rows and bot offset. A previous backup plus continued writes is a different recovery point with additional potential loss.

V14/V15 rewrite/lock candidate profiles; V17 backfills ownership and replaces keys/constraints with exclusive schema locks. Flyway serialization does not drain application transactions or make those locks harmless. Start exactly one migration-capable candidate-aware process, with all background work disabled. Set reviewed finite PostgreSQL `lock_timeout` and `statement_timeout` for that migration connection (for example through the approved JDBC connection options), selected from rehearsal rather than copied blindly. Verify they actually apply before starting. Have a private operator inspect blocking sessions and Flyway history. Never start the old app in parallel, kill unrelated transactions blindly, disable Flyway validation, enable Hibernate DDL writes, or run a second migrator to hurry it up.

If an abort occurs, stop the process and inspect the actual history/schema before deciding the next action. PostgreSQL transactional migration failure can leave V13–V16 rather than V17 depending on the last committed migration. Do not infer the schema from the process exit code.

## Gate 4 — controlled replacement and validation

1. Load/verify the retained image archive or, after separate publication approval, pull the recorded immutable registry digest. Record the resolved image ID and effective configuration. Keep one instance and the existing private document mount, read-only root filesystem, private listener, temporary directory and UID/GID safeguards. Do not recreate/remove data volumes.
2. For the V13→V17 cutover, run the verified candidate-aware artifact in **normal mode** with beta and all background features disabled; it must actually perform/validate the migrations. Do not select the exact-V17 recovery profile as a substitute for the migration rehearsal. For a recovery on already-verified V17, use the default recovery profile. The runtime default profile is not enough when an orchestrator overrides it.
3. Wait for successful Flyway validation/migration, entity validation, owner binding and startup guards. Verify private `/health` reports schema readiness and the expected baked build identity. Check manifest/source/JAR identity via image labels as well; `/health` commit alone cannot distinguish dirty builds. Keep Caddy maintenance active until these pass.
4. Use the owner's fresh, correctly signed Telegram launch. Verify owner v1 snapshot, saved/dismissed/applied transitions and undo, existing application/history/notes and authorized documents. Use controlled non-destructive test records and record their IDs. Confirm anonymous/expired sessions, beta v1, foreign application IDs, foreign undo and private internal/source/document endpoints are denied. No synthetic token may be used in production.
5. Compare preserved owner/profile/workflow/application/history/mutation/document IDs, timestamps and ownership against the rehearsal/checkpoint plan. Confirm only the intended new schema/ownership fields changed during migration, and no historical delivery or polling offset was reset. Health alone does not establish this.
6. Open owner traffic only after checks pass. Observe errors, DB locks/connections, latency and private artifact access for the agreed window. Keep registration false. If any gate fails, close traffic, stop the bad process and follow the decision table below.
7. Enable ingestion/scheduler only after explicit approval and confirmation of one instance. Enable Telegram separately, after the previous poller is demonstrably stopped and persisted offset/delivery state are intact. Review `TELEGRAM_DISCARD_PENDING_ON_FIRST_START` deliberately: discarding pending updates loses commands; replaying them requires existing idempotency and offset checks. Do not reset offsets or delivery ledgers. Verify one controlled delivery/command and watch for duplicates before normal scheduling. Recovery profile intentionally cannot enable this work.

## Gate 5 — invited beta and normal restoration

Beta remains closed until owner checks, independent isolation/security evidence, backup/restore rehearsal and a proven candidate-aware recovery artifact all pass. After separate invitation approval, explicitly switch the same immutable image to reviewed normal mode (for example `SPRING_PROFILES_ACTIVE=prod` only if that is the approved deployment profile), preserving every other approved setting. Set only the intended invited numeric IDs and registration flag. Do not activate public registration or expand owner allowlists. Verify each invited account sees only its own saved/dismissed/applied/history/undo state and neutral public catalogue; check v1/private routes still deny them.

To leave an incident recovery state, first validate the unchanged V17 schema and recovery evidence, stop/drain the recovery instance, then replace it with the approved normal-mode instance by exact image identity. Restore the reviewed invitation list and registration flag last, use fresh launches, and verify both beta accounts resume their previous rows. Preserve IDs, ownership, histories, timestamps and mutation ledgers. Keep Telegram/scheduler off until the separate single-instance checks above complete. Never run recovery and normal instances together against the same bot token.

## Incident decisions and data loss

| Observed state after stopping the failed application | Safe next step |
|---|---|
| V17 intact/validated; application-only failure; no data corruption | Start retained candidate-aware V17 image in `recovery` profile against the **unchanged** DB/artifact store. Admission off; verify owner operations, denied beta, preserved beta fingerprints and no sends. This is the preferred data-preserving recovery. |
| Migration failed and rolled back transactionally; last validated schema is V13–V16 | Keep maintenance. Inspect exact committed history, locks and data. Use a compatible, reviewed forward migration-capable candidate-aware release to reach V17 or choose the coordinated pre-cutover restore. Recovery mode itself requires V17. Do not deploy the old production binary against an intermediate schema based on assumption. |
| Future V18 was committed, or a later schema contains extra objects/unknown history | Exact-V17 recovery startup must fail. Keep maintenance; prepare/test a candidate-aware forward corrective release with an explicitly supported schema boundary, preserving user data. A forward repair is a new higher-numbered migration; never edit V17/V18 or use a destructive downgrade. A V19 repair does not magically make the V17 recovery image compatible. |
| Data corruption, destructive migration, missing/inconsistent private artifacts, compromised credentials, or no compatible forward repair within the incident budget | Preserve forensic copies, stop every writer/poller, and use the approved coordinated DB/PITR plus private-artifact restore to **new isolated targets**. Validate the recovered checkpoint before switching application connections/mounts under approval. Do not overwrite the only remaining database or delete volumes. |
| Flyway checksum/history mismatch or unknown failed migration | Stop, inspect and resolve with a reviewed corrective plan. Do not bypass guards or run `repair` to conceal a changed applied migration. |

A database restore loses **all committed writes after the chosen recovery point**, including owner/beta actions, profiles/documents, sessions, replay markers, mutations and polling/delivery ledger updates. Document recovery may have an older checkpoint than PostgreSQL PITR; use the last verified common checkpoint or reconcile every referenced artifact before service opens. Record the actual recovery point, expected/observed loss and affected users before approval. Preserve newer data for possible controlled reconciliation, never silently merge ownership rows.

Telegram is an external side effect: a message delivered after the restored DB checkpoint cannot be unsent, while its durable delivery marker/offset may be absent after restore. Therefore a full restore cannot promise zero duplicate notifications. Keep poller/sends off, compare retained pre-restore delivery/offset evidence with Telegram/operator evidence, and approve a reconciliation plan before enabling delivery. The unchanged-DB candidate-aware recovery avoids this rewind and is the path covered by the no-duplicate local regression. A bot-token/epoch rotation is a deliberate security action, not a replacement for data reconciliation.

## Sign-off record

Record the approved source hash, JAR SHA, image ID/archive SHA (and actual registry digest only if published), effective profile/config revision, V13/V17 checksums, backup/PITR and document checkpoint identities, restore/migration duration, owner-ID verification, negative-route/isolation results, one-poller proof, delivery/offset evidence and rollback decision authority. Attach actual complete backend/PostgreSQL/frontend results and the actual-image normal→recovery→normal rehearsal. A missing or skipped required integration suite is a failed gate.

Local verification permits **GO for production release preparation only** once these artifact/test deliverables are green. Production execution remains **NO-GO** until the Azure-specific gates and human deployment approval are complete. Stop after Phase 2.5; no deployment, public beta or Phase 3 is authorized by this runbook.

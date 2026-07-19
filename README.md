# JobPilot

JobPilot is a human-in-the-loop internship discovery service for entry-level software roles. Phase 1 fetches public Greenhouse and Lever job boards, normalizes and deduplicates vacancies, deterministically extracts requirements, scores them against a configurable candidate profile, and sends strong matches to a Telegram channel. Phase 2 Stage 1 adds the versioned candidate truth model, Stage 2 safely processes manually submitted public vacancy URLs, Stage 3 adds human-maintained application tracking, Stage 4 adds optional structured job analysis, Stage 5 creates truthful application documents for private human review, and Stage 6 integrates the complete workflow with maintenance, readiness, safe operational counters, PostgreSQL end-to-end verification, and production-like Docker defaults.

JobPilot never submits applications, uploads documents to employers, answers screening questions, accepts agreements, or contacts recruiters. Stage 5 generates ATS-oriented DOCX/PDF résumés and optional cover notes, but attaching a completed version to an existing application remains a separate human-triggered internal operation. Protected-site browser automation remains out of scope.

## Phase 1 features

- Java 21 and Spring Boot 3.3
- PostgreSQL persistence with Flyway-managed `jobs`, `job_requirements`, `job_scores`, and `source_fetch_logs`
- Greenhouse Job Board API and Lever Postings API adapters
- Configurable search terms, locations, board tokens, and schedules
- Exponential retry for transient network/5xx failures with response time and size limits
- Per-source and per-vacancy failure isolation
- Canonical URL, source/external ID, company/title/location, and description-hash deduplication
- Deterministic skill, eligibility, experience, language, mentorship, and location extraction
- 0–100 scoring with explanations, penalties, and hard blockers
- Immediate Telegram notifications for excellent matches and a daily digest for good matches
- Scheduled refresh every six hours, daily digest at 09:00 Europe/Bucharest, and stale expiry
- Multi-stage non-root Docker image and PostgreSQL Docker Compose stack

## Architecture

The application uses a layered flow:

```text
Greenhouse / Lever -> JobSource adapters -> relevance filter -> normalizer
    -> deduplication -> deterministic extraction -> scoring -> PostgreSQL
    -> excellent notification / daily good-match digest -> Telegram

manual public URL -> URL/DNS/redirect safety policy -> known ATS API or bounded HTTP fetch
    -> JSON-LD / supported metadata parsing -> the same normalizer, deduplication,
       deterministic extraction, scoring, and PostgreSQL pipeline

Telegram getUpdates -> explicit chat + user authorization -> typed command dispatcher
    -> short application/history transaction -> best-effort confirmation -> persistent offset

persisted job + deterministic requirements + optional verified candidate facts
    -> cache lookup + committed database budget reservation
    -> optional provider call outside transactions -> strict structured/truth validation
    -> atomic analysis, usage, and reservation reconciliation OR deterministic fallback

persisted job + validated analysis + exact verified candidate facts
    -> short IN_PROGRESS claim transaction -> canonical truth validation
    -> private DOCX/PDF render, structural validation, hash, and atomic file move
    -> short COMPLETED transaction -> preview/metadata API -> human document selection

human Telegram/internal command -> save -> analyze -> generate -> inspect metadata/previews
    -> select completed compatible documents -> explicit APPLIED transition
    -> interview/follow-up/outcome -> ordered immutable application history

bounded scheduler -> expired reservation reconciliation + stale document recovery
    -> bounded symlink-safe partial/orphan cleanup -> safe ID/count-only logs and counters
```

Integrations implement `JobSource`; persistence is isolated behind Spring Data repositories. `JobProcessor` gives one vacancy a transaction, while `JobIngestionService` contains failures so one bad source or posting cannot abort the complete fetch. `JobSchedulingService` prevents overlapping fetches with an atomic guard.

### Phase 2 Stage 1 persistence

Flyway migration `V2__phase_2_persistence_and_candidate_profile.sql` adds normalized tables for:

- versioned candidate profiles, skills, languages, projects, and immutable verified project bullets;
- application records;
- resume versions and their selected candidate-fact references;
- cover notes;
- LLM usage accounting metadata;
- the Telegram long-polling offset singleton.

The verified profile source is `src/main/resources/candidate-profile.yml`. Typed configuration binding and Bean Validation validate the complete resource before the transactional bootstrap writes anything. Re-running the same `profile-version` with the same facts is idempotent. Changing facts without increasing the version is rejected; a higher version creates a new active row and preserves the previous version and its fact rows for audit.

The database enforces one active profile, stable-key uniqueness within each parent, one application per job, resume-to-fact foreign keys, and explicit cascade/restrict behavior. No personal contact details are stored in the profile tables.

See [Phase 2 architecture](docs/phase-2-architecture.md) and [resume truth source](docs/resume-truthfulness.md).

### Phase 2 Stage 2 manual vacancy URLs

`POST /internal/v1/jobs/manual-url` accepts one public `http` or `https` vacancy URL. Known Greenhouse and Lever job links are resolved through their existing public API adapters. Other public pages are fetched with strict time, redirect, content-type, and response-size bounds, then parsed in this order:

1. schema.org `JobPosting` JSON-LD, including object, array, `@graph`, multiple-script, and escaped forms;
2. supported job metadata plus readable page content;
3. otherwise a typed failure—there is no heuristic browser or LLM fallback.

Successful vacancies enter the existing normalization, deduplication, deterministic requirement extraction, scoring, and persistence pipeline. Tracking parameters are removed without reordering the remaining query parameters. Generic manual sources are scoped by canonical hostname so common external IDs cannot collide across sites. Duplicate protection uses the existing application checks and database uniqueness constraints, including concurrent submissions.

Response statuses are `CREATED`, `ALREADY_EXISTS`, `UNSUPPORTED_SOURCE`, `INVALID_URL`, `FETCH_FAILED`, `PARSE_FAILED`, or `BLOCKED_OR_PROTECTED`. Example:

```bash
curl --request POST http://localhost:8080/internal/v1/jobs/manual-url \
  --header 'Content-Type: application/json' \
  --data '{"url":"https://boards.greenhouse.io/example/jobs/123"}'
```

The endpoint returns the canonical URL, persisted job ID, score, strengths, and risks when processing succeeds. It does not log submitted URLs or response content.

This is an internal administrative endpoint. Keep it behind a trusted network boundary or an authentication layer; Docker Compose binds the application port to loopback by default.

### Phase 2 Stage 3 Telegram application tracker

Long polling is disabled by default. When enabled, JobPilot authorizes both the numeric chat ID and the numeric sender/user ID; `TELEGRAM_CHANNEL_ID` is only the notification destination and is never reused implicitly as command authorization. Enabled command polling fails startup validation unless a bot token, the bot username, and both explicit authorization IDs are present. `TELEGRAM_BOT_USERNAME` accepts the Telegram username with or without its leading `@`; commands explicitly addressed to a different bot are ignored as save/apply actions and resolve to help without calling `getMe`.

Supported commands are `/help`, `/add <public vacancy URL>`, `/save <jobId>`, `/applied <jobId>`, `/interview <jobId> <ISO-8601 datetime with offset>`, `/rejected <jobId> [reason]`, `/offer <jobId>`, `/withdraw <jobId>`, `/followup <jobId> <YYYY-MM-DD|clear>`, `/note <jobId> <text|clear>`, `/status <jobId>`, and `/applications [status]`. Telegram `@BotName` suffixes are accepted only when they case-insensitively match `TELEGRAM_BOT_USERNAME`; suffixless commands still work. `/add` delegates to the Stage 2 manual URL service and its existing safety/persistence pipeline. Excellent-match notifications always include Open vacancy and include Save and Applied only when commands are enabled; callback data contains only the action and numeric job ID.

The allowed status graph is: new to `SAVED` or `APPLIED`; `SAVED` to `APPLIED` or `WITHDRAWN`; `APPLIED` to `INTERVIEW`, `REJECTED`, `OFFER`, or `WITHDRAWN`; `INTERVIEW` to `INTERVIEW`, `REJECTED`, `OFFER`, or `WITHDRAWN`; and `OFFER` to `WITHDRAWN`. `REJECTED` and `WITHDRAWN` are terminal. Same-status requests are idempotent and create no history row, except changing an existing interview datetime is a real `INTERVIEW` to `INTERVIEW` reschedule with one history row. Every actual status mutation and its immutable application-level history row commit together. The application restricts repository operations and database cascade deletion, but a database principal with direct privileged SQL access could still alter history. Application and Telegram state rows use optimistic locking.

Telegram delivery is at-least-once, not exactly-once. Polling asks for `lastProcessedUpdateId + 1`, processes sorted update IDs, and advances past successful, invalid, unauthorized, and unsupported updates. Unauthorized callbacks fail closed: they are neither dispatched nor replied to or acknowledged, but their update offset advances without retry or dead-letter state. Unexpected internal failures retain the offset and increment persistent retry state; after the configured attempt limit the update is dead-lettered by advancing the offset. Application mutations commit before confirmations, and confirmation failures still advance the offset, so a failed reply cannot repeat a committed mutation. Replays after a crash are safe because same-state application operations are idempotent.

On the first start without a state row, `TELEGRAM_DISCARD_PENDING_ON_FIRST_START=true` drains and records the existing backlog without executing it; `false` processes it normally. The in-JVM atomic guard prevents overlapping local polls, but Stage 3 supports only one active polling application instance. There are no webhooks and no cross-replica poller lock.

### Phase 2 Stage 4 structured LLM job analysis

`POST /internal/v1/jobs/{jobId}/analysis` requests a candidate-specific analysis by default; pass `candidateSpecific=false` for a job-only analysis. The internal response uses typed statuses: `CREATED`, `CACHED`, `FALLBACK`, `DISABLED`, `BUDGET_EXCEEDED`, `JOB_NOT_FOUND`, `PROFILE_NOT_FOUND`, `PROVIDER_FAILED`, or `INVALID_PROVIDER_RESPONSE`. It exposes only the validated canonical analysis and sanitized category—never prompts, provider bodies, headers, secrets, full vacancy text, or candidate contact data. Keep this endpoint behind the same trusted administrative boundary as the manual-URL endpoint.

LLM execution is disabled by default. Enabled mode currently supports the official OpenAI [Responses API](https://developers.openai.com/api/reference/resources/responses/methods/create) and [Structured Outputs](https://developers.openai.com/api/docs/guides/structured-outputs) shape through the provider-neutral `LlmProvider` interface. For `provider=openai`, `LLM_BASE_URL` is restricted to the exact case-insensitive `api.openai.com` hostname and `/v1` base path over HTTPS, with no credentials, query, fragment, non-default port, IP literal, subdomain, or redirect. Immediately before constructing the authorization-bearing request, every DNS answer is checked with the shared public-address policy and any loopback, private, link-local, multicast, unspecified, reserved, benchmarking, documentation, carrier-grade NAT, or metadata address rejects the call. The JDK client cannot pin that validated address while retaining normal TLS hostname verification, so a connection-time DNS re-resolution race remains; redirects stay disabled and the exact host is revalidated before every physical attempt.

The adapter requests strict JSON Schema output, disables provider-side storage, bounds request and response bytes, and retries only bounded `429`/`5xx` failures outside database transactions. Only a root `status=completed` response with exactly one usable `output_text` block is accepted; incomplete/truncated, non-completed, refusal-only, missing, and multiple-output responses become sanitized fallback failures. Input estimation uses a deliberately conservative code-point upper bound, not an emulation of provider tokenization, and retains a hard configured maximum. Unicode format/bidirectional controls are removed from vacancy and candidate strings while normal international text is preserved. Evidence excerpts require at least eight normalized characters.

Before network I/O, one short transaction locks the `llm_budget_control` singleton, checks request/day/month caps, persists a unique reservation and an in-progress cache row, and commits. A reservation covers `maximum single-attempt cost × (LLM_MAX_RETRIES + 1)`. The provider call and retry sleep run with no active transaction or budget lock. Final accounting uses the reported final usage plus a conservative maximum for earlier attempts whose billing is unknown; missing usage and ambiguous delivered failures never become zero. This is deliberately conservative and does not claim exact provider billing knowledge.

A final short transaction stores the validated analysis, reconciles the locked reservation, and creates or updates the single sanitized usage event atomically. Expired reservations never marked provider-started are released at zero without a fake timeout event; started reservations remain conservatively `ABANDONED`. A valid late success locks the abandoned reservation and existing event, stores the structured result, transitions to `LATE_SETTLED`, and updates rather than duplicates accounting. Days and months are UTC buckets fixed at reservation time.

The canonical result is bounded typed JSON: role summary, requirement/responsibility lists, experience/education/language/location/authorization fields, candidate gaps and ambiguities, evidence references, confidence, and candidate strengths represented only as verified stable fact keys plus `MATCH`/`PARTIAL_MATCH`. Evidence excerpts must originate in the supplied vacancy or exact verified fact. Unsupported fact keys, strengthened language evidence, positive invented candidate assertions, unknown fields/enums, invalid bounds, and repeated prompt-injection instructions are rejected. These controls reduce unsupported output but are not a guarantee of perfect hallucination prevention.

The cache key hashes job content, candidate truth/profile version (or generic mode), operation, prompt version, normalized provider, and configured model. A completed valid provider analysis is returned as `CACHED` without a fake usage row. Changing any identity component invalidates the key. Provider/validation failures persist deterministic fallback with a five-minute cooldown; concurrent identical work returns fallback while the winning request is in progress and never starts a second provider call.

### Phase 2 Stage 5 truthful application documents

`POST /internal/v1/jobs/{jobId}/documents` explicitly requests DOCX, PDF, or both and whether to include a cover note. Generation is disabled by default. When enabled, the service loads the active profile and a validated Stage 4 analysis, creates renderer-neutral `ResumeDocumentModel` and `CoverNoteDocumentModel` values, validates every candidate selection against exact verified fact IDs/stable keys, then passes the same canonical wording to both renderers. The database stores the selected skill, project, bullet, language, and cover-note fact references. Contact values never enter those models' persisted truth fields, previews, hashes, change summaries, interview claims, logs, or provider requests; they are validated from runtime configuration and injected only into private artifacts.

Deterministic generation ranks verified facts using normalized vacancy/analysis terms, keywords, project technologies, and verified bullets. Optional `RESUME_DRAFT` and `COVER_NOTE_DRAFT` operations reuse Stage 4's provider, strict schema, reservation, accounting, and sanitized fallback infrastructure. Provider output may select only supplied stable keys; application validation reconstructs all prose. Disabled LLM, budget exhaustion, provider failure, malformed output, and unsupported selections fall back to conservative student-level documents and are marked as fallback metadata.

Apache POI renders macro-free, one-column Office Open XML without tables, headers, footers, hidden text, comments, external relationships, or embedded objects. Apache PDFBox renders selectable text with one or two deterministic pages and no forms, annotations, actions, JavaScript, encryption, or attachments. Both use the same headings: name/contact, target title, summary, technical skills, projects, education, and languages. This conservative structure improves portability but does not guarantee compatibility with every ATS.

Artifacts live below a validated private storage root using server-generated relative names. The lifecycle is a short `IN_PROGRESS` claim transaction, rendering and validation with no transaction, temporary files and atomic moves where supported, followed by a short `COMPLETED` transaction containing SHA-256 hashes, byte sizes, and PDF page count. Missing/tampered cached files are rejected; failed or stale claims may be retried; partial files are removed; and orphan cleanup is bounded. Cache identity includes job content, exact profile truth, analysis, operation, templates, renderer, requested formats, requested provider/model path, and only an opaque HMAC-SHA256 contact identity. Raw contact values and the HMAC secret are never persisted.

Metadata and fixed-name downloads are available from `GET /internal/v1/resumes/{id}`, `/docx`, `/pdf` and the corresponding `/internal/v1/cover-notes/{id}` routes. `PUT /internal/v1/applications/{jobId}/documents` selects a completed, structurally valid résumé and compatible cover note in a short locked transaction. It is idempotent and never changes application status. These remain internal administrative endpoints and must stay behind a trusted network or authentication boundary.

Truth validation rejects invented employment, commercial experience, metrics, employers, certifications, strengthened language levels, practical claims for theoretical knowledge, inactive/CV-disallowed facts, unrelated prose attached to a valid key, and senior/professional titles. Cover notes use a neutral salutation, vacancy-evidenced company/role statements, explicit project-level truth boundaries, and material-gap acknowledgment. These controls reduce hallucination risk but do not provide a perfect prevention guarantee; every document still requires human review.

### Phase 2 Stage 6 final integration and operations

The supported human lifecycle is: ingest or add a public vacancy; normalize, deduplicate, extract, and score; save it; explicitly analyze it; explicitly generate a résumé and optional cover note; inspect bounded previews/metadata; select a completed compatible version on an existing application; explicitly mark `APPLIED`; then record interview, follow-up, rejection, offer, or withdrawal and inspect ordered history. Analysis and generation do not create an application. Generation and selection do not edit vacancy content. Selection is idempotent, creates no status-history entry, and leaves the current status unchanged. `REJECTED` and `WITHDRAWN` remain terminal.

Stage 6 extends the existing authorized long-poll command path with `/analyze <jobId>`, `/documents <jobId> [resume|all] [docx|pdf|both]`, `/resumes <jobId>`, `/covernotes <jobId>`, `/selectdocs <jobId> <resumeVersionId> [coverNoteId|none]`, and `/history <jobId>`. Long operations receive a bounded acknowledgement, then reuse the Stage 4/5 cache and claim identities. Authorization, bot-name suffix rules, offset persistence, retry/dead-letter behavior, and commit-before-confirmation semantics are unchanged. Dynamic HTML is escaped and assembled only from complete bounded sections. Telegram returns numeric IDs and trusted internal metadata/download routes; it does not send file bytes and never exposes storage paths or artifact hashes.

The complete internal administrative surface is:

- `POST /internal/v1/jobs/manual-url` and `POST /internal/v1/jobs/{jobId}/analysis`;
- `POST /internal/v1/jobs/{jobId}/documents`;
- `GET /internal/v1/resumes/{id}` and `/docx` or `/pdf`;
- `GET /internal/v1/cover-notes/{id}` and `/docx` or `/pdf`;
- `PUT /internal/v1/applications/{jobId}/documents` for document selection;
- `PUT /internal/v1/applications/{jobId}/status`, `/follow-up`, and `/notes`;
- `GET /internal/v1/applications/{jobId}`, `/{jobId}/history`, and `GET /internal/v1/applications?status=...`;
- `GET /internal/v1/operations/metrics` for fixed-label runtime counters plus persisted status counts.

These endpoints have no authentication. This is intentionally still a single-user architecture: bind them to loopback or place the service behind a trusted private network boundary. Multi-user identity, ownership, authentication, billing, and per-user budgets are future work.

Maintenance is disabled by default. When enabled, one JVM uses a local atomic guard to prevent overlap and stops accepting new scheduled work during shutdown. Each run has one item budget and one wall-time budget. It reuses the existing Stage 4 reservation reconciliation and Stage 5 failure/storage methods, rechecks rows under database locks, then performs filesystem work outside those transactions. Cleanup never follows symlinks, scans a bounded depth/candidate count, isolates item failures, and database-checks artifact references before deleting old final files. The Stage 4 singleton budget lock and pessimistic document-row locks make duplicate recovery safe across instances, but there is no distributed schedule lease; multiple replicas may perform redundant scans. Run one maintenance scheduler where possible.

`GET /health` performs no provider or Telegram call. It reports only `READY`/`NOT_READY` or `ENABLED`/`DISABLED` for database, Flyway schema, Telegram commands, LLM, documents, artifact storage, and maintenance, plus configured build version/commit tokens. It never exposes credentials, paths, contacts, document hashes, candidate facts, vacancy text, prompts, or provider output. Readiness is `DOWN` when the database, schema, or enabled artifact storage is not ready.

Flyway remains forward-only and Stage 6 adds no migration: V1 is the initial vacancy/application schema, V2 adds candidate truth and workflow persistence, V3 adds authorized Telegram/application history hardening, V4 adds structured analysis and budget accounting, and V5 adds truthful document artifacts and fact references. Published V1–V5 files are unchanged.

## Requirements

- Java 21 or newer (the Maven compiler always targets release 21)
- Docker with Docker Compose for the recommended runtime
- A Telegram bot/channel only if notifications are wanted

No global Maven installation is required. `mvnw` downloads Maven 3.9.11 into a project-local wrapper directory, verifies its SHA-512 checksum, and keeps its artifact cache under `.mvn/repository`. Set `MAVEN_REPO_LOCAL` or `MAVEN_USER_HOME` to override those defaults.

## Configuration

Copy the example and edit the local file:

```bash
cp .env.example .env
```

Important variables:

| Variable | Required | Purpose |
|---|---:|---|
| `POSTGRES_DB` | Docker default provided | PostgreSQL database |
| `POSTGRES_USER` | Docker default provided | PostgreSQL user |
| `POSTGRES_PASSWORD` | Production: yes | PostgreSQL password |
| `DATABASE_URL` | Local JVM: yes | JDBC PostgreSQL URL |
| `JOBPILOT_VERSION` | No | Safe health build version token; default `unknown` |
| `BUILD_COMMIT` | No | Safe health commit token; default `unknown` |
| `GREENHOUSE_BOARD_TOKENS` | At least one source | Comma-separated Greenhouse board tokens |
| `LEVER_COMPANY_IDS` | At least one source | Comma-separated Lever company identifiers |
| `TELEGRAM_BOT_TOKEN` | Notifications only | BotFather token; never commit it |
| `TELEGRAM_CHANNEL_ID` | Notifications only | Target channel ID, usually beginning with `-100` |
| `TELEGRAM_BOT_USERNAME` | Commands: yes | Bot username, with or without leading `@`; used locally for command addressing |
| `TELEGRAM_COMMANDS_ENABLED` | No | Enables long polling; default `false` |
| `TELEGRAM_ALLOWED_CHAT_ID` | Commands: yes | Explicit authorized numeric chat ID |
| `TELEGRAM_ALLOWED_USER_ID` | Commands: yes | Explicit authorized numeric sender/user ID |
| `TELEGRAM_POLL_TIMEOUT` | No | Bounded long-poll timeout; default `25s` |
| `TELEGRAM_POLL_DELAY` | No | Delay between local polls; default `2s` |
| `TELEGRAM_POLL_LIMIT` | No | Updates per request, `1`–`100`; default `50` |
| `TELEGRAM_MAX_UPDATE_FAILURES` | No | Attempts before dead-lettering; default `3` |
| `TELEGRAM_DISCARD_PENDING_ON_FIRST_START` | No | Drain old backlog on first start; default `true` |
| `JOB_FETCH_CRON` | No | Default `0 0 */6 * * *` |
| `DAILY_DIGEST_CRON` | No | Default `0 0 9 * * *` |
| `STALE_DAYS` | No | Default `30` |
| `MANUAL_URL_CONNECT_TIMEOUT` | No | Manual fetch connection timeout; default `5s` |
| `MANUAL_URL_RESPONSE_TIMEOUT` | No | Manual fetch response timeout; default `15s` |
| `MANUAL_URL_MAX_REDIRECTS` | No | Validated redirect limit; default `3` |
| `MANUAL_URL_MAX_RESPONSE_BYTES` | No | Response body limit; default `1048576` |
| `MANUAL_URL_MAX_TITLE_LENGTH` | No | Parsed title limit; default `500` |
| `MANUAL_URL_MAX_DESCRIPTION_LENGTH` | No | Parsed description limit; default `100000` |
| `LLM_ENABLED` | No | Enables optional provider analysis; default `false` |
| `LLM_PROVIDER` | Enabled: yes | Supported provider identifier (`openai`) |
| `LLM_BASE_URL` | Enabled: yes | OpenAI only: exact `https://api.openai.com/v1` base; no default |
| `LLM_API_KEY` | Enabled: yes | Provider secret; never commit or log it |
| `LLM_MODEL` | Enabled: yes | Configured provider model; no default |
| `LLM_CONNECT_TIMEOUT` | No | Bounded provider connection timeout; default `5s` |
| `LLM_RESPONSE_TIMEOUT` | No | Bounded provider response timeout; default `60s` |
| `LLM_MAX_INPUT_TOKENS` | Enabled: yes | Maximum bounded input used for reservation/estimation |
| `LLM_MAX_OUTPUT_TOKENS` | Enabled: yes | Maximum requested output and conservative estimate |
| `LLM_MAX_RETRIES` | No | Adapter-level transient retries, `0`–`3`; default `1` |
| `LLM_REQUEST_BUDGET_USD` | Enabled: yes | Maximum reserved retry exposure for one logical request |
| `LLM_DAILY_BUDGET_USD` | Enabled: yes | UTC daily committed/reserved cap |
| `LLM_MONTHLY_BUDGET_USD` | Enabled: yes | UTC monthly committed/reserved cap |
| `LLM_INPUT_COST_PER_MILLION_TOKENS` | Enabled: yes | Explicit input price used for accounting |
| `LLM_OUTPUT_COST_PER_MILLION_TOKENS` | Enabled: yes | Explicit output price used for accounting |
| `DOCUMENTS_ENABLED` | No | Enables private document generation; default `false` |
| `DOCUMENT_STORAGE_ROOT` | Documents: yes | Private non-source/non-public root; default `./data/documents` |
| `DOCUMENT_MAX_DOCX_BYTES` | No | DOCX byte bound, 1 KiB–20 MiB; default `2097152` |
| `DOCUMENT_MAX_PDF_BYTES` | No | PDF byte bound, 1 KiB–20 MiB; default `2097152` |
| `DOCUMENT_RESUME_TEMPLATE_VERSION` | No | Résumé cache/template identity |
| `DOCUMENT_COVER_NOTE_TEMPLATE_VERSION` | No | Cover-note cache/template identity |
| `DOCUMENT_RENDERER_VERSION` | No | Renderer cache identity |
| `DOCUMENT_MAX_PREVIEW_CHARACTERS` | No | Contact-free preview bound; default `4000` |
| `DOCUMENT_STALE_AFTER` | No | Stale `IN_PROGRESS` retry threshold; default `10m` |
| `DOCUMENT_CONTACT_CACHE_HMAC_KEY` | Documents: yes | Runtime-only Base64 secret containing at least 32 decoded bytes; no default |
| `DOCUMENT_CONTACT_EMAIL` | Documents: yes | Runtime-only bounded syntactic email; never persisted |
| `DOCUMENT_CONTACT_PHONE` | No | Runtime-only optional bounded phone |
| `DOCUMENT_CONTACT_GITHUB_URL` | No | Runtime-only safe HTTPS link |
| `DOCUMENT_CONTACT_LINKEDIN_URL` | No | Runtime-only safe HTTPS link |
| `DOCUMENT_CONTACT_PORTFOLIO_URL` | No | Runtime-only safe HTTPS link |
| `MAINTENANCE_ENABLED` | No | Enables bounded Stage 6 recovery; default `false` |
| `MAINTENANCE_INTERVAL` | No | Fixed delay, `1m`–`1d`; default `15m` |
| `MAINTENANCE_MAX_ITEMS_PER_RUN` | No | Shared item limit, `1`–`1000`; default `100` |
| `MAINTENANCE_MAX_DURATION_PER_RUN` | No | Shared duration, `1s`–`5m`; default `30s` |
| `MAINTENANCE_ORPHAN_GRACE_PERIOD` | No | Minimum artifact age before cleanup, `10m`–`30d`; default `1h` |

Phase 1 matching facts remain under `jobpilot.candidate` in `application.yml`. The independently versioned Phase 2 truth source is `candidate-profile.yml`; increase `profile-version` whenever verified facts change. Candidate rows are not placed in Flyway migrations.

Generate `DOCUMENT_CONTACT_CACHE_HMAC_KEY` independently from all other credentials using at least 32 random bytes and Base64 encoding. Enabled document generation fails closed when it is absent, malformed, or too short; disabled mode requires no key. The key is runtime-only and must never be logged or committed. Keep it consistent and back it up securely if document-cache reuse across deployments is desired; rotation intentionally invalidates cache identity without exposing the underlying contacts.

### Greenhouse

For a board URL such as `https://boards.greenhouse.io/acme`, the token is `acme`:

```dotenv
GREENHOUSE_BOARD_TOKENS=acme,another-company
```

### Lever

For a postings URL such as `https://jobs.lever.co/acme`, the identifier is `acme`:

```dotenv
LEVER_COMPANY_IDS=acme,another-company
```

## Telegram setup

1. Create a bot with `@BotFather` and copy its token into `.env`.
2. Create a Telegram channel.
3. Add the bot to the channel as an administrator allowed to post messages.
4. Set `TELEGRAM_CHANNEL_ID` to the channel's numeric ID.
5. Start JobPilot. An excellent match is posted immediately; good matches are grouped in the 09:00 digest.

To enable commands, obtain the numeric chat and user IDs through a trusted setup step, set `TELEGRAM_COMMANDS_ENABLED=true`, `TELEGRAM_BOT_USERNAME`, `TELEGRAM_ALLOWED_CHAT_ID`, and `TELEGRAM_ALLOWED_USER_ID`, then run only one polling application replica. Do not expose these IDs or the token in logs or committed files.

If the token or channel ID is blank, Telegram delivery is safely disabled. Job ingestion and scoring continue.

## Run with Docker Compose

```bash
cp .env.example .env
docker compose up --build
```

Check the application:

```bash
curl http://localhost:8080/health
docker compose ps
```

Compose waits for a bounded PostgreSQL 16 health check before starting the app, binds HTTP to `127.0.0.1`, runs the app as UID/GID `10001`, drops Linux capabilities, uses a read-only root filesystem, mounts an explicit private temporary directory, and persists documents in `jobpilot-documents`. Optional Telegram, LLM, documents, and maintenance remain disabled unless explicitly enabled. Graceful shutdown stops new polling, ingestion, digest, and maintenance work; the scheduler and server have bounded termination windows.

Stop without deleting PostgreSQL data:

```bash
docker compose down
```

## Run locally

Start PostgreSQL, configure `.env`, export it, and run:

```bash
set -a
source .env
set +a
./mvnw spring-boot:run
```

Flyway applies the schema automatically. The application does not require source or Telegram credentials to start; it simply fetches zero configured boards and suppresses notifications.

## Build and test

```bash
./mvnw -DskipTests compile
./mvnw test
./mvnw verify
```

The suite covers normalization, canonical URLs, migration/repository behavior, deduplication, deterministic extraction, scoring/penalties/hard blockers, Greenhouse and Lever payloads, source failure isolation, Telegram messages, candidate-profile validation/versioning, Phase 2 persistence, manual URL SSRF/redirect policy, LLM destination/budget/provider failures, structured truth/evidence validation, prompt injection, contact HMAC isolation, path traversal/symlinks, DOCX/PDF structure, cache invalidation/idempotency, application/document compatibility, download integrity, transaction boundaries, typed APIs, and deterministic fallback. Stage 6 adds a real PostgreSQL 16 end-to-end lifecycle with synthetic external adapters, Telegram authorization/offset/replay/restart behavior, committed mutation despite confirmation failure, no automatic `APPLIED`, ordered history, artifact reuse, and bounded maintenance cleanup. H2 in PostgreSQL compatibility mode provides fast feedback only; `./mvnw verify` runs the `*IT` concurrency and full-flow evidence against PostgreSQL 16 Testcontainers. Tests make no live OpenAI, Telegram, vacancy, recruiter, or employer call.

### Backup, restore, and troubleshooting

Back up PostgreSQL and the private document volume together. Also preserve `DOCUMENT_CONTACT_CACHE_HMAC_KEY` in a secure secret backup; it is required to reproduce contact-dependent cache identity. A consistent restore must pair database artifact metadata with the same document-volume snapshot. After restore, keep documents disabled until storage is mounted and `/health` reports it ready; metadata without files is rejected, files without metadata remain inaccessible and become cleanup candidates only after the configured grace period. Restoring with a different HMAC key is safe but deliberately causes new document cache identities.

If startup fails, check PostgreSQL health, Flyway validation, and fail-closed configuration for whichever optional integration was enabled. If document generation reports an invalid artifact, verify private-volume ownership/writability and restore consistency; do not edit stored hashes or paths. If Telegram stops advancing, inspect sanitized update IDs, retry/dead-letter counters, and ensure exactly one active poller. If analysis falls back, inspect only the typed failure category and budget counters; prompts/provider bodies are intentionally unavailable.

## Security and ethical boundary

- Secrets and generated personal documents are ignored by Git.
- Tokens are read only from environment variables and are not logged.
- LLM prompts and raw provider responses are neither logged nor persisted; accounting stores sanitized metadata only.
- Runtime document contacts are injected only into private final artifacts; previews, audit content, hashes of canonical models, provider requests, and logs exclude them.
- Private document paths are server-generated, relative, symlink-checked, size-bounded, structurally validated, and Git-ignored.
- Remote calls have connection/read timeouts, bounded responses, and transient retries.
- Only documented public Greenhouse and Lever APIs are queried.
- Manual URL fetches allow only `http`/`https`, reject credentials, validate every original and redirected hostname through DNS, and block loopback, private, link-local, multicast, unspecified, reserved, benchmarking, and cloud-metadata destinations. IPv4 destinations embedded in 6to4, Teredo, NAT64, IPv4-compatible, or IPv4-mapped IPv6 addresses are decoded and checked by the same IPv4 policy.
- Manual fetches send only fixed `Accept` and `User-Agent` headers—never cookies, authorization, provider tokens, or user-supplied headers—and accept only bounded HTML, XHTML, text, or JSON responses.
- LinkedIn and protected portals are not scraped; CAPTCHAs, authentication, robots controls, and rate limits are never bypassed.
- JobPilot discovers and ranks vacancies only. Every application remains a deliberate manual action.
- Internal HTTP endpoints have no authentication and require loopback or a trusted network boundary.
- The architecture is single-user; LLM budgets and runtime document contact configuration are global.
- Back up PostgreSQL, private document storage, and the document contact HMAC key with restore consistency.

## Current limitations

- HTTP adapters currently use Spring `RestClient`; migration to the originally preferred reactive `WebClient` is pending dependency availability.
- Manual ingestion supports known ATS links, schema.org `JobPosting`, and confidently identified public job metadata; arbitrary company-page scraping, Jooble, and RSS adapters are not implemented.
- Telegram command polling is single-instance only; webhooks and distributed poller coordination are not implemented.
- Maintenance has safe database locking but no distributed scheduler lease; prefer one active maintenance replica.
- Optional Stage 4 LLM analysis supports one documented Responses-compatible provider adapter; it is disabled by default and deterministic analysis remains available.
- LLM delivery is at-most-one active caller per cache key under normal database operation, not exactly-once provider delivery; a crash after provider acceptance can leave only conservative abandoned-reservation accounting.
- Stage 5 supports the committed profile's verified student/project truth model; it does not model unverified employment history or arbitrary résumé section templates.
- ATS-friendly output is deliberately conservative, but no universal ATS parsing/format-compatibility guarantee is possible.
- Strict schemas and fact validation reduce unsupported LLM selections but cannot guarantee perfect hallucination prevention; human review remains mandatory.
- Stage 6 never submits applications, uploads documents to employers, contacts recruiters, or answers screening questions.
- No browser automation or fallback for JavaScript-only, authenticated, CAPTCHA, or otherwise protected vacancy pages.
- Crawlee and CloakBrowser are intentionally absent and may be considered only after Phase 2 is merged.
- Multi-user support, ownership, authentication, and per-user contact/budget configuration remain future work.
- The standard Java HTTP client performs its own connection-time DNS lookup after policy validation, leaving a narrow DNS-rebinding race; production deployments should also block private and metadata ranges at the network layer.
- PostgreSQL Testcontainers integration tests require a working Docker environment.
- Board-wide APIs are filtered after retrieval; configure only permitted boards and respect provider policies.

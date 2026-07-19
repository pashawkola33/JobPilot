# Phase 2 architecture — Stages 1 through 6

Stage 1 establishes the versioned candidate truth model and workflow persistence. Stage 2 adds manually submitted public vacancy URLs. Stage 3 adds authorized Telegram command polling and manual application tracking. Stage 4 adds optional structured LLM job analysis, database-backed budget reservations, usage accounting, strict candidate-truth validation, cache identity, and deterministic fallback. Stage 5 adds truthful résumé/cover-note models, private DOCX/PDF rendering, artifact lifecycle/versioning, preview, and human document selection. Stage 6 composes those boundaries into the final human workflow and adds bounded maintenance, safe readiness/operational counters, release hardening, and PostgreSQL end-to-end evidence. Recruiter contact, screening answers, employer uploads, protected-site browser automation, and application submission are not implemented.

## Scope

Phase 2 preserves the complete Phase 1 ingestion pipeline and extends the same modular monolith:

```text
candidate-profile.yml
    -> typed CandidateProfileProperties
    -> complete validation
    -> transactional, version-aware bootstrap
    -> candidate profile and normalized fact tables

jobs + candidate facts
    -> application / resume / cover-note persistence structures
    -> fact-reference and audit foreign keys

future LLM and Telegram workflows
    -> usage-event / long-polling-state persistence structures

POST /internal/v1/jobs/manual-url
    -> canonical URL and destination policy
    -> known Greenhouse/Lever API reuse OR bounded public-page fetch
    -> deterministic JobPosting/metadata parser
    -> existing JobProcessor pipeline and PostgreSQL constraints

POST /internal/v1/jobs/{jobId}/analysis
    -> deterministic job/profile hashes and completed-cache lookup
    -> short locked reservation transaction and commit
    -> optional provider call without a transaction
    -> strict schema/evidence/truth validation
    -> short atomic analysis + usage + budget reconciliation transaction
    -> deterministic fallback for every non-success provider path

POST /internal/v1/jobs/{jobId}/documents
    -> validated runtime contact + exact profile/job/analysis snapshots
    -> short IN_PROGRESS claim transaction and commit
    -> deterministic or optional validated stable-key draft
    -> canonical truth model -> DOCX/PDF render/validate/store without transaction
    -> short COMPLETED metadata/fact-reference transaction
    -> private preview/download API -> separate human selection transaction
```

Stage 1 components perform no external calls. The Stage 2 endpoint performs only the explicitly submitted, policy-approved public fetch or a known ATS public API call. Stage 3 Telegram calls and Stage 4/5 provider calls run outside database transactions. Stage 5 rendering and filesystem work also run without an open database transaction.

## Flyway migration

`V2__phase_2_persistence_and_candidate_profile.sql` is the schema source of truth. Hibernate remains configured with `ddl-auto: validate`.

The migration adds:

| Area | Tables |
|---|---|
| Candidate truth | `candidate_profiles`, `candidate_skills`, `candidate_languages`, `candidate_projects`, `candidate_project_bullets` |
| Application tracking foundation | `applications` |
| Resume audit foundation | `resume_versions`, `resume_version_skills`, `resume_version_projects`, `resume_version_project_bullets` |
| Cover-note foundation | `cover_notes` |
| LLM accounting foundation | `llm_usage_events` |
| Telegram polling foundation | `telegram_bot_state` |

Important database guarantees include:

- a positive, unique profile version;
- exactly zero or one active profile, enforced with a unique nullable `active_slot` plus a consistency check;
- stable-key uniqueness in each candidate fact scope;
- normalized skill, language, and project uniqueness within a profile version;
- one application per job;
- foreign keys from resume selection rows to verified candidate facts;
- `RESTRICT` for audit-bearing job/profile/fact references;
- `CASCADE` from an unreferenced candidate profile to its owned facts and from a resume version to its selection rows;
- `SET NULL` for optional resume/cover-note links and deleted-job links in LLM accounting.

## Candidate profile bootstrap

`candidate-profile.yml` contains the committed, verified profile. It does not contain contact values. Spring imports the resource and binds it to immutable, typed records.

Startup invokes a small runner which delegates to `CandidateProfileBootstrapService`. The service owns one transaction and follows this sequence:

1. Validate Bean Validation constraints and cross-record truth rules.
2. Compute a SHA-256 source fingerprint without logging the source.
3. Compare the configured version with the active database version.
4. Return the existing row when both version and fingerprint match.
5. Reject a lower version or changed facts using the same version.
6. Deactivate the current row and insert a complete higher version atomically.

Older version facts are never updated by the mapper. Only their active marker and update timestamp change when a higher version becomes active. Logs contain only the version and fact counts.

## Validation

Validation covers required text, bounded string and collection sizes, positive profile versions, reasonable education years, non-negative bounded commercial Java experience, stable-key syntax, typed categories and levels, duplicate stable keys, duplicate active facts, and duplicate normalized technology/keyword values.

Truth-specific configuration guards reject French enabled for CV use and Romanian configured for CV use at native, fluent, or professional-working level.

## Persistence boundaries

Spring Data repositories exist for each aggregate and normalized fact/reference table. Entities use constructor-based creation and typed enums. Stage 2 reuses the existing job aggregate and repositories; no manual-ingestion-specific tables or schema migration are needed.

`llm_usage_events` deliberately stores accounting metadata only: operation, provider, model, token counts, estimation marker, cost, status, fallback marker, sanitized failure category, job reference, and timestamp. It has no prompt, response, header, credential, or candidate-contact column.

`telegram_bot_state` permits only the stable `long-polling` singleton key and contains no bot token.

## Manual URL processing

The internal endpoint accepts a JSON body shaped as `{"url":"https://…"}` and returns one typed domain outcome. `CREATED` maps to HTTP 201, `ALREADY_EXISTS` to 200, `INVALID_URL` to 400, `BLOCKED_OR_PROTECTED` to 403, `UNSUPPORTED_SOURCE` and `PARSE_FAILED` to 422, and `FETCH_FAILED` to 502. Successful results include the persisted job ID, canonical URL, score, strengths, and risks.

The endpoint is not a public trust boundary: deployments must keep it behind a trusted network boundary or add authentication. The default Docker Compose mapping exposes it only on host loopback.

The application service intentionally owns no database transaction while network work is performed. Only `JobProcessor` opens the established per-vacancy transaction after a complete `RawJob` exists. `ManualJobPersistenceService` delegates to that pipeline and recovers a concurrent unique-constraint loser by looking up the winning row, yielding `ALREADY_EXISTS` rather than an internal error.

For non-ATS pages, the stored source key is namespaced by canonical hostname (with a bounded SHA-256 fallback for unusually long hostnames). This preserves a page-provided external ID without allowing common identifiers such as `123` to collide across unrelated sites.

### Destination security

`ManualUrlPolicy` is applied to the submitted URL and every redirect before a request is sent. It:

- accepts only `http` and `https`, rejects embedded credentials, fragments, malformed/oversized URLs, and local/internal hostnames;
- resolves the hostname and rejects any loopback, private, link-local, multicast, unspecified, reserved/documentation, carrier-grade NAT, unique-local IPv6, `2001:2::/48` benchmarking, or cloud-metadata address;
- recognizes 6to4 (`2002::/16`), Teredo (`2001:0000::/32`), NAT64 well-known (`64:ff9b::/96`), IPv4-compatible, and IPv4-mapped IPv6 forms, decodes their embedded IPv4 destinations, and applies the same prohibited-IPv4 policy;
- retains the approved resolved addresses with the validated URI and rejects a hostname when any returned address is prohibited;
- canonicalizes scheme/host/default ports/path and removes known tracking parameters while preserving the order of remaining query parameters.

The dedicated Java HTTP transport disables automatic redirects, uses fixed `GET`, `Accept`, and `User-Agent` values, and sends no cookies, authorization, secrets, or user-supplied headers. Connection time, response time, redirects, and streamed response bytes are bounded by `jobpilot.manual-url`. Only HTML, XHTML, plain text, and JSON media types are accepted.

Java's standard `HttpClient` does not expose a supported per-request DNS pinning hook that also preserves the original TLS hostname. The policy therefore resolves and validates immediately before each original/redirect request, but the client may perform its own DNS lookup while opening the connection. A very narrow DNS-rebinding race remains; deployments should also enforce outbound network rules that deny private and metadata ranges.

### ATS and deterministic parsing

Recognized Greenhouse and Lever job links are converted to their documented public API calls and parsed by the same adapter code used for scheduled ingestion. Other allowed pages follow this deterministic order:

1. parse JSON or every `application/ld+json` script and recursively inspect objects, arrays, and `@graph` nodes for schema.org `JobPosting`;
2. decode escaped JSON-LD and resolve a relative, same-origin canonical URL;
3. fall back only to supported job-specific metadata accompanied by meaningful readable content;
4. reject generic pages as `UNSUPPORTED_SOURCE`, malformed or incomplete vacancy data as `PARSE_FAILED`, and authentication/CAPTCHA/protection signals as `BLOCKED_OR_PROTECTED`.

Title, company, canonical URL, and a meaningful bounded description are mandatory. Extracted HTML is sanitized to text before persistence. No LLM, browser automation, authentication, CAPTCHA solving, or protected-site fallback exists in Stage 2. Logs contain only outcome/failure categories, never submitted URLs, fetched bodies, credentials, or request headers.

For schema.org and metadata pages, a meaningful description is at least 40 characters. Trusted Greenhouse and Lever API results may have a shorter description, but it must remain non-empty. Provider timestamps with an offset retain their supplied instant; a bare `LocalDate` has no timezone evidence and is normalized deterministically to UTC midnight.

Fetch failures are exhaustively categorized. Protected/authentication responses map to `BLOCKED_OR_PROTECTED`, unsupported media types map to `UNSUPPORTED_SOURCE`, and timeouts, connection failures, unsuccessful HTTP responses, empty or oversized responses, invalid redirects, and redirect limits map to `FETCH_FAILED`. Unexpected programming or persistence errors are not converted to fetch failures; they remain server errors handled by the application-wide sanitized HTTP 500 path, preserving their stack trace in server logs.

## Verification

Fast tests use H2 in PostgreSQL compatibility mode. They cover URL/DNS policy, redirect revalidation, size/time/content-type bounds, known ATS reuse, JSON-LD variants, metadata fallback, protected and unsupported pages, typed controller responses, persistence, and a two-thread duplicate race. The `*IT` suite uses PostgreSQL 16 through Testcontainers and verifies Flyway, Hibernate validation, manual-pipeline persistence/deduplication, round trips, uniqueness, foreign keys, and cascades. Run:

```bash
./mvnw -DskipTests compile
./mvnw test
./mvnw verify
```

Docker must be available for the PostgreSQL integration tests to execute.

## Stage 3: Telegram commands and application tracking

Stage 3 keeps the modular monolith and separates three boundaries: `TelegramApiClient` performs typed, sanitized Bot API operations; polling/processing owns delivery and authorization; `ApplicationTrackerService` owns short application/history transactions. No Telegram call, long poll, or Stage 2 manual vacancy fetch occurs inside a database transaction.

`V3__telegram_commands_and_application_tracking.sql` adds optimistic-lock versions to `applications` and `telegram_bot_state`, persistent failed-update ID/attempt fields, bounded notes/reason constraints, and `application_status_history`. History exposes insert/read repository operations only, is mapped immutable with non-updatable fields, and cannot be cascade-deleted with an application. These are application-level protections rather than a database trigger: a database principal with direct privileged SQL access could still alter history. Each actual transition inserts exactly one row in the same transaction as the application mutation. One application per job remains database-enforced; unique and optimistic conflicts are retried deterministically.

The transition policy permits creation as `SAVED` or `APPLIED`; `SAVED -> APPLIED|WITHDRAWN`; `APPLIED -> INTERVIEW|REJECTED|OFFER|WITHDRAWN`; `INTERVIEW -> INTERVIEW|REJECTED|OFFER|WITHDRAWN`; and `OFFER -> WITHDRAWN`. `REJECTED` and `WITHDRAWN` are terminal. Same-state delivery is an idempotent no-op with no history row, except that changing the datetime of an existing interview is a real `INTERVIEW -> INTERVIEW` reschedule with one history row. The first entry into `APPLIED` sets the application date once. Interview input requires an ISO-8601 offset datetime. Follow-up dates are local dates, notes/reasons are normalized and bounded, and clearing stores null rather than blank text.

Command polling is off by default and fails closed when enabled without the bot token, locally configured bot username, and explicit allowed chat and sender/user IDs. The leading `@` is normalized away and suffixed commands must case-insensitively match that configured name; suffixless commands remain valid and no `getMe` lookup is used. Authorization checks both IDs; the notification `channel-id` is not an authorization fallback. Supported updates are limited to messages and callback queries. Dynamic output is HTML-escaped and constructed from complete bounded sections so Telegram's 4096-character limit cannot split tags or entities. Excellent-match notifications always provide the URL and provide Save and Applied callbacks only when commands are enabled.

The persistent watermark is the last processed update ID; requests use watermark plus one and sorted processing. Successful commands, safe validation errors, unauthorized updates, and unsupported updates advance it. Unauthorized callbacks are deliberately not dispatched, replied to, or acknowledged; they still advance without retry or dead-letter state. Unexpected internal failures retain the watermark and increment retry state; reaching the configured maximum dead-letters the update by advancing the watermark and clearing failure state. A mutation commits before its best-effort confirmation, so a send or callback-answer failure cannot roll back or repeat it. This is at-least-once delivery, not exactly-once.

When no state row exists, the configured first-start policy either drains every pending page through an empty response without executing or processes updates normally. The discard loop has a finite safety-page cap to prevent a faulty Bot API client from creating an unbounded tight loop. An `AtomicBoolean` prevents overlapping polls in one JVM. Stage 3 deliberately supports one active polling application instance only: the local guard does not coordinate replicas. Webhooks, automatic application submission, browser automation, screening answers, recruiter contact, and historical Telegram message editing are not implemented.

The manual URL HTTP endpoint remains an internal administrative interface and must stay behind a trusted network boundary or gain authentication in deployment. Stage 3 `/add` reuses that service but does not turn the endpoint into a public trust boundary.

## Stage 4: structured LLM job analysis

### Provider-neutral boundary and configuration

The application layer depends only on `LlmProvider`, `LlmRequest`, and `LlmResponse`. `OpenAiResponsesLlmProvider` is the optional production adapter and follows the official [Responses create reference](https://developers.openai.com/api/reference/resources/responses/methods/create) and [Structured Outputs guide](https://developers.openai.com/api/docs/guides/structured-outputs): `POST /v1/responses`, `text.format` strict JSON Schema, `max_output_tokens`, and `store=false`. It accepts only root `status=completed` with exactly one message `output_text`; `incomplete` (including `max_output_tokens`), other final states, refusal-only, missing text, and multiple text blocks are explicit sanitized failures. Optional `usage.input_tokens` / `usage.output_tokens` is parsed only as bounded accounting metadata. No unofficial provider SDK is included.

`LLM_ENABLED=false` is the default. For `provider=openai`, enabled configuration accepts only the exact case-insensitive hostname `api.openai.com`, `/v1` base path, HTTPS, and the default HTTPS port; IP literals, subdomains, local/internal names, credentials, query, fragment, other paths, and other ports fail closed without echoing the URL or key. Immediately before each authorization-bearing request, every resolved address is classified by the shared Stage 2 public-address policy. Any loopback, private, link-local, multicast, unspecified, reserved, benchmarking, documentation, carrier-grade NAT, metadata, or empty resolution rejects the call. Disabled mode performs no DNS check. The JDK HTTP client cannot pin the validated address while preserving normal TLS hostname verification, leaving a residual DNS re-resolution race at connect time; exact endpoint validation is repeated and redirects remain disabled.

Enabled configuration also requires an API key, model, bounded token limits, ordered positive request/day/month budgets, and explicit positive token prices. Monetary configuration is `BigDecimal`, bounded to eight decimal places with ceiling rounding. Startup fails safely if the maximum physical-attempt exposure is excessive. Token counts, retries, and timeouts are bounded. Disabled mode never constructs an endpoint or starts a provider call.

The adapter sends only fixed headers plus the authorization secret, streams a bounded response, and retains no exception cause that could contain a request, response, URL, prompt, or header. Only `429` and `5xx` are retried, in one adapter layer, up to `LLM_MAX_RETRIES`; transport timeouts are not independently retried. Result/exception metadata contains only bounded physical/ambiguous attempt counts and a typed category. `Retry-After` is honored only inside a five-second cap. Tests replace the provider or transport and never contact a live service.

### Migration and persisted data

`V4__llm_analysis_and_budget.sql` adds:

| Table | Purpose |
|---|---|
| `llm_budget_control` | Seeded singleton row used as the cross-request pessimistic budget lock |
| `llm_budget_reservations` | Unique request attempts, UTC day/month buckets, maximum token/cost reservation, provider-start marker, and final reconciliation |
| `job_analyses` | Unique deterministic cache identity and bounded canonical analysis fields |

It also links `llm_usage_events` to an optional reservation and sanitized request key. There is one usage event per reconciled/abandoned reservation. The schema has no API-key, authorization, raw prompt, raw provider request/response, system-instruction, arbitrary provider metadata, Telegram, or candidate-contact column.

Analysis identity records job, optional candidate profile and version, operation, provider, model, prompt version, job content hash, candidate truth hash, cache key, attempt, result status, fallback marker, sanitized failure category, and timestamps. Canonical list/object fields are JSON text only after strict typed validation; unrestricted provider prose is never the canonical stored result.

### Reservation and accounting algorithm

The service uses explicit `TransactionTemplate` boundaries:

```text
transaction 1 (short)
  lock job/cache row and llm_budget_control
  return completed cache if present
  conservatively abandon expired reservations
  compute (maximum single-attempt cost × maximum physical attempts)
  enforce request, UTC-day, and UTC-month caps
  insert unique reservation and IN_PROGRESS analysis
commit

no transaction
  mark provider-started in a separate short transaction
  issue one bounded provider request
  decode and validate canonical result

transaction 2 (short)
  lock analysis, reservation, and budget control
  reconcile actual final usage plus conservative ambiguous prior attempts
  store SUCCEEDED or deterministic FALLBACK analysis
  store one sanitized usage event
commit
```

The singleton `SELECT FOR UPDATE` lock serializes read/check/insert across jobs, persistence contexts, and application instances; PostgreSQL 16 concurrency tests hold that row while genuinely overlapping reservations prove the transaction boundary. The same deterministic request-attempt key cannot reserve twice. Exact budget boundaries are allowed; any positive amount over is `BUDGET_EXCEEDED`. Each reservation covers `maxSingleAttemptCost × (maxRetries + 1)`, so request/day/month decisions include all allowed physical attempts. Provider I/O and retry sleep occur after commit with no budget lock.

Final success accounting combines reported final usage with one maximum single-attempt amount for each earlier `429`/`5xx` attempt whose billing is unknown. Missing final usage and ambiguous delivered failures use non-zero conservative counts; a connect failure known not to deliver can release zero. This deliberately avoids claiming exact provider billing knowledge. The reservation retains its UTC bucket, and reconciliation below the reserved exposure releases capacity.

Expired reservations with no `provider_started_at` are `RELEASED` at zero and create no fake timeout usage. A started expired reservation becomes `ABANDONED` with maximum conservative accounting. If valid structured success later arrives, finalization locks the analysis, budget control, reservation, and existing usage event; it stores the result, transitions to `LATE_SETTLED`, and updates the one event without reducing existing or known usage or double charging. Repeated finalization is idempotent. The system does not claim exactly-once delivery.

### Structured truth and prompt boundary

Candidate contact values and generated resume content are never input. Candidate input is a compact snapshot of active verified facts identified by stable keys, profile version, and source hash. Vacancy title/company/location/description and deterministic requirements are separately encoded as untrusted JSON. C0 controls, every Unicode `Cf` format character, and bidirectional controls are removed from vacancy and candidate strings while normal international text is preserved. Field sizes are bounded. The input estimate is a conservative four-units-per-code-point upper bound with fixed overhead—not provider tokenization—and must fit `LLM_MAX_INPUT_TOKENS`.

The provider result must match a strict schema with no unknown fields: concise role summary; must-have/preferred/responsibility lists; bounded experience, education, language, location, and authorization fields; candidate gaps and ambiguities; evidence; confidence; and a false provider-side fallback flag. Candidate strengths contain only a supplied stable fact key and `MATCH` or `PARTIAL_MATCH`, leaving no free-form candidate-claim field.

Application validation runs even after provider schema enforcement. It rejects null/missing/unknown/oversized values, unknown enums, duplicate or unsupported fact keys, evidence shorter than eight normalized characters, non-originating vacancy evidence, candidate evidence that is not an exact excerpt of the referenced verified fact, strengthened language evidence, positive invented candidate assertions, secret/header patterns, HTML/script output, and repeated prompt-injection phrases. Trivial excerpts such as `a`, `IT`, or `Java` cannot validate a larger claim.

### Cache, fallback, and API

The SHA-256 cache key includes job content, candidate truth hash/profile version (or generic mode), operation, prompt version, normalized provider, and model. A valid completed provider result returns `CACHED` without a usage event. Any changed component creates another identity. Database uniqueness plus locked preparation prevents concurrent identical requests from both calling the provider.

Invalid and failed provider results are not stored as successful cache entries. They store a deterministic fallback and a five-minute retry cooldown; after it expires the same row advances its attempt and uses a new deterministic request-attempt key. Disabled, configuration/input bounds, budget, authentication, connection, timeout, rate-limit, provider, oversized/malformed, schema, and truth failures are typed and never break the Phase 1/Stage 2/Stage 3 paths. Fallback data is explicitly marked and comes from existing deterministic requirements plus verified fact-key matching.

`POST /internal/v1/jobs/{jobId}/analysis` is the minimal internal analysis surface. Candidate-specific analysis is the default; `candidateSpecific=false` omits candidate facts. Responses expose only typed status, IDs/profile version, validated analysis, and sanitized category. The endpoint must remain behind a trusted network or authentication boundary.

Stage 4 does not generate or persist a resume, cover note, DOCX, PDF, recruiter message, screening answer, or application action. It adds no Telegram command and no browser automation.

## Stage 5: truthful private application documents

### Canonical model and fact boundary

`CandidateDocumentFacts` and `JobDocumentFacts` are detached, bounded snapshots assembled in a short transaction. They carry the exact profile/source version, verified candidate IDs and stable keys, job content hash, and the validated Stage 4 analysis identity. `ResumeDraftPlan` and `CoverNoteDraftPlan` contain stable-key selections only. The deterministic builder or optional provider may propose a plan, but `ResumeTruthValidator` and `CoverNoteTruthValidator` resolve every selection against those snapshots and reconstruct the canonical wording.

`ResumeDocumentModel` and `CoverNoteDocumentModel` are the only renderer inputs. Both DOCX and PDF therefore contain the same title, summary, skills, languages, projects, verified bullets, and cover-note paragraphs. The résumé uses 8–16 unique active skills, 1–3 active projects, 2–4 verified bullets per selected project, and 2–5 CV-allowed languages with stable tie ordering. With no verified employment, it emits no work-experience section and no professional/senior claim. The cover note uses a neutral salutation, 180–350 words, job title/company evidence, selected candidate fact references, and conservative gap wording.

`DocumentContactPolicy` validates a required bounded syntactic email, optional bounded phone, and optional HTTPS URLs. URL values reject credentials, query, fragment, non-default ports, unsafe schemes, local/internal hosts, and private or special-purpose IP literals. The renderers create no network relationships or remote fetches; safe links are emitted as visible plain text. Contact values are runtime-only and are excluded from prompts, persisted content, canonical hashes, previews, change summaries, interview claims, and logs.

`DocumentContactCacheIdentity` canonicalizes email, phone whitespace, and each safe link once, then uses JDK `HmacSHA256` with the explicit `JobPilot\0document-contact-cache\0v1\0` domain and length-delimited fields. The helper accepts an optional future owner scope, but Stage 5 supplies none and adds no multi-user model. Only this keyed opaque identity influences the persisted document cache key; neither raw contact data nor the key is stored. The dedicated Base64 secret must decode to at least 32 bytes, has no default, is required only when documents are enabled, and is unrelated to provider, Telegram, or database credentials. Decoded key bytes are short-lived and cleared. Deployments that require cache reuse must preserve the secret consistently; rotation deliberately invalidates contact-dependent cache identities.

### Optional drafting and fallback

`RESUME_DRAFT` and `COVER_NOTE_DRAFT` use the existing `LlmProvider`, `LlmBudgetService`, `LlmCostCalculator`, `LlmUsageRecorder`, and Stage 4 transaction conventions. Each operation has a separate strict schema and request identity. The provider receives sanitized untrusted vacancy data plus stable candidate keys, never contact values, and cannot supply canonical prose. Calls occur after reservation commit with no active transaction.

Disabled LLM, budget/call-limit rejection, provider failure, malformed JSON, schema failure, and truth failure all use deterministic generation. Fallback metadata is explicit and is never labelled provider-generated. Résumé ranking uses normalized vacancy/analysis terms, verified skill names, project technologies, bullet keywords, strengths, and gaps. Cover-note fallback uses only the persisted role/company, selected résumé facts, verified project bullets, and validated analysis requirements/gaps.

### V5 persistence and cache identity

Forward-only `V5__resume_cover_note_documents.sql` leaves V1–V4 unchanged. It extends `resume_versions` and `cover_notes` with analysis/profile identity, unique SHA-256 cache key, template/renderer/provider/model identity, deterministic/provider method, fallback marker, requested formats, render status/failure, attempt and optimistic-lock versions, structured content hash, relative artifact paths, byte hashes/sizes, PDF page count, and lifecycle timestamps. Checks prevent a completed row without canonical content and every requested artifact's path/hash/positive bounded size; requested PDFs require one or two pages.

`resume_version_languages` completes the exact résumé-to-fact audit. `cover_note_fact_references` records an ordered typed reference with exactly one foreign key to a profile, skill, language, project, or project bullet. Existing application résumé/cover-note foreign keys remain the selection mechanism. Candidate audit targets use restrictive foreign keys; document-owned selection rows cascade only with their owning document.

The résumé cache key hashes the operation, job description hash, profile source hash/version, analysis cache identity, résumé template, renderer, requested formats, requested LLM path/provider/model, and keyed opaque contact identity. The cover-note identity additionally includes the completed résumé content hash and its own template/operation. A completed valid hit is reused; changed identity creates another row. A database unique constraint resolves concurrent claims to at most one row; a bounded loser waits for and adopts the completed winner. Completed audit rows are immutable; only failed or stale `IN_PROGRESS` rows with the same identity are cleared and retried with an incremented attempt.

### Crash-consistent storage and rendering

The generation lifecycle is:

1. Claim or create `IN_PROGRESS` in a short locked transaction.
2. Commit before model construction, provider I/O, rendering, or filesystem access.
3. Build and validate the canonical truth model.
4. Render requested bytes with Apache POI 5.5.1 and PDFBox 3.0.8.
5. Write restrictive temporary files, structurally validate them, and atomically move where supported to server-generated relative paths.
6. Reopen the final file, revalidate content/structure/size, hash final bytes with SHA-256, and retain the PDF page count.
7. Mark `COMPLETED` and persist exact fact mappings in a final short transaction.
8. Select documents only through the separate human-triggered operation.

The root is disabled by default and rejected when it is a symlink or lies within source, resources, static/public, or build output directories. Client path/title/company input never enters a filename. Resolution rejects absolute paths, separators, traversal, colon/Unicode-confusion forms, unexpected directory/name shapes, and target-path symlinks. A render/storage/validation failure removes partial and newly finalized files. If final database completion fails, generated files are removed unless another completion won the race. Reads repeat structural/hash/size validation. Orphan cleanup scans only bounded generated depth, accepts an explicit reference set/cutoff, and removes at most 1,000 old unreferenced regular files.

POI emits standard Arial paragraphs, headings, and bullets in a simple one-column `.docx` with no tables, headers, footers, macros, OLE/embeddings, comments, tracked/hidden text, remote templates, external relationships, images, or local paths. PDFBox emits selectable single-column text, deterministic wrapping/pagination, at most two pages, and no encryption, forms, annotations, actions, JavaScript, attachments, launch actions, or remote content. Generated artifacts are reopened and expected canonical text is extracted before completion. The layout is ATS-oriented, not a promise of universal ATS compatibility.

### Internal API and human selection

The internal surface is:

- `POST /internal/v1/jobs/{jobId}/documents` for explicit résumé/cover-note and DOCX/PDF choices;
- `GET /internal/v1/resumes/{id}` plus `/docx` and `/pdf`;
- `GET /internal/v1/cover-notes/{id}` plus `/docx` and `/pdf`;
- `PUT /internal/v1/applications/{jobId}/documents` for explicit selection.

Responses expose typed state, bounded canonical preview/audit metadata, hashes/sizes/page count, and numeric IDs, never contact configuration, absolute paths, prompt/provider bodies, SQL errors, stack traces, or full vacancy text. Downloads use fixed media types, `no-store`, `nosniff`, and server-generated numeric attachment names.

Selection first snapshots document metadata, validates stored artifacts outside a transaction, then opens a short transaction that locks the application and documents and revalidates job, profile version, completion state, and cover-note-to-résumé linkage. It is idempotent and does not change application status. Passing `coverNoteId: null` explicitly clears a previously selected cover note while retaining the selected résumé. Document selection remains a human action. There is no employer upload, automatic submission, recruiter contact, screening response, Telegram command, or browser automation in Stage 5.

The PostgreSQL 16 Testcontainers suite forces two production generation transactions past the same initial cache miss, proves the unique-key race, blocks the real renderer to demonstrate overlap, and verifies winner adoption, exact fact ownership, artifact integrity, stale-claim recovery, active-claim non-stealing, and cache-hit revalidation. Separate worker threads receive separate transaction-bound persistence contexts; no H2 result is used as the concurrency guarantee.

## Stage 6: final integration, recovery, and release boundary

### Component responsibilities

| Component | Stage 6 responsibility |
|---|---|
| `TelegramCommandParser` / `TelegramCommandDispatcher` | Bounded typed human commands that reuse Stage 2–5 services |
| `ApplicationTrackerService` | Current application state plus ordered immutable status history |
| `JobAnalysisService` | Explicit, cache-aware structured analysis without creating an application |
| `ResumeGenerationService` | Explicit cache-aware generation and safe metadata listing without creating an application |
| `ApplicationDocumentSelectionService` | Locked compatibility/integrity check on an existing application, with no status transition |
| `Stage6MaintenanceCoordinator` | One-JVM non-overlap and shared item/time budgets |
| `LlmBudgetService` | Existing conservative reservation expiration under the global budget lock |
| `DocumentMaintenanceService` / `DocumentArtifactStorage` | Locked stale-row recovery and bounded symlink-safe partial/orphan cleanup |
| `HealthController` | Safe database/schema/config/storage readiness with no external call |
| `OperationalMetricsController` | Fixed-label runtime counters and persisted enum-status counts |

No Stage 6 component implements provider calls, rendering, artifact persistence, vacancy parsing, Telegram authorization, or application transition policy a second time.

### End-to-end human workflow and state invariants

```text
public vacancy
  -> normalize / deduplicate / deterministic extract / score / persist
  -> SAVE application (Telegram or trusted internal API)
  -> ANALYZE explicitly (no application creation or vacancy mutation)
  -> GENERATE explicitly (no application creation/status change or vacancy mutation)
  -> inspect contact-free preview + metadata + internal route IDs
  -> SELECT completed same-job/same-profile résumé and optional linked cover note
       requires existing application; status and status-history stay unchanged
  -> APPLIED explicitly
  -> INTERVIEW / FOLLOW-UP / REJECTED / OFFER / WITHDRAWN explicitly
  -> ordered immutable status history
```

The cache/unique identities make replayed analyze/generate work reuse completed state. Save/status and selection operations return idempotent no-ops when state is identical. Selection snapshots and validates artifacts outside a transaction, then locks application/documents and repeats compatibility checks. The application must exist; résumé and cover note must belong to the job and the exact compatible candidate profile/version; the cover note must link to the selected résumé; every requested artifact must still pass structural/hash/size validation. `REJECTED` and `WITHDRAWN` are terminal and cannot be silently reopened. Only an explicit `APPLIED` transition sets the first application timestamp.

### Telegram integration and delivery decision

Stage 6 retains the one existing `getUpdates` poller, chat-plus-sender authorization policy, bot-username suffix parser, persistent offset, retry counter, and dead-letter behavior. New commands are `/analyze`, `/documents`, `/resumes`, `/covernotes`, `/selectdocs`, and `/history`. IDs accept only positive bounded `long` syntax; scope/format arguments are closed enums. Analysis and generation receive a static bounded progress acknowledgement before long work. Provider/rendering work holds no database transaction or Telegram offset-row lock. The final reply begins only after service transactions have committed. Confirmation failure is swallowed as best effort so it cannot repeat a completed mutation.

The delivery choice is metadata plus numeric internal routes. The current Telegram abstraction supports bounded HTML messages, not safe streaming uploads. Stage 6 therefore does not add multipart/file delivery and never hands Telegram a filesystem path or artifact bytes. Résumé/cover-note lists expose only IDs, completion state, available formats, and `/internal/v1/...` metadata/download route strings. They omit contacts, storage paths, hashes, prompts, provider output, cache keys, and vacancy bodies. All dynamic strings are truncated before HTML escaping and messages are assembled from complete tags/entities within 4,096 characters.

At-least-once delivery remains explicit. A crash before offset persistence can replay a command; Stage 2 deduplication, Stage 3 same-state mutations, Stage 4 analysis cache/reservation identity, Stage 5 document cache/claim identity, and selection equality prevent repeat completed work under their documented guarantees. One active Telegram poller is required; there is no distributed Telegram lease or webhook path.

### Internal administrative API

Stage 6 keeps all HTTP workflow endpoints below `/internal/v1` except the safe `/health` readiness route. Application status/follow-up/notes mutations and current/list/history reads join the existing manual URL, analysis, generation, metadata/download, and selection routes. The operational counter snapshot is `/internal/v1/operations/metrics`. There is no authentication or ownership model: deployments must use loopback or a trusted network boundary. The architecture is single-user; LLM budgets and runtime document contact configuration are global.

### Maintenance and multi-instance behavior

Maintenance is disabled by default and configured by fixed delay, maximum items, maximum wall duration, and orphan grace period. One `AtomicBoolean` prevents overlap in one JVM. The coordinator stops accepting new scheduled work once shutdown begins and shares one item/time budget across:

1. expired `RESERVED` LLM reservations;
2. stale résumé/cover-note `IN_PROGRESS` rows;
3. old generated partial files;
4. old unreferenced generated final files.

Expired reservation IDs are bounded before processing. Each item locks the existing `llm_budget_control` singleton and reservation, rechecks expiry/status, and invokes the existing release/abandon and conservative usage-event logic. No provider call is made. Stage 4 analysis rows already recover through the locked retry/cooldown path on the next explicit request; Stage 6 does not invent a second analysis-cache lifecycle or delete canonical analysis data.

Stale document IDs are bounded, locked individually, and rechecked with the Stage 5 stale threshold. A genuine stale row becomes typed `FAILED/STALE_GENERATION` in a short transaction; its generated bundle is removed afterward with no row lock held. Partial/orphan walking has bounded depth, candidate scan count, removal count, and duration, never follows or removes symlinks, requires server-generated path shapes, applies an age grace period, and database-checks both résumé and cover-note references immediately before deleting a final file. Item failures log only numeric IDs and fixed categories.

Across instances, the budget singleton and pessimistic document locks keep each mutation safe and idempotent, but no distributed scheduler lease prevents redundant scans. A deployment should designate one active maintenance replica. Maintenance never changes application status and performs no Telegram, provider, or vacancy external call.

### Health, observability, and shutdown

`/health` checks database connectivity, Flyway validation/current schema, and enabled artifact-storage readiness. It reports only safe enablement/readiness strings plus configured build version/commit tokens. Telegram, LLM, and maintenance health checks do not send or bill anything. Configuration records fail startup closed when an enabled integration lacks mandatory secrets/IDs/limits.

`OperationalCounters` uses a fixed enum—never arbitrary company, title, URL, candidate, prompt, provider-body, contact, or path labels. It counts Telegram processed/retried/dead-lettered updates, analysis/document outcomes, and maintenance recovery/removal. The internal snapshot also queries counts by fixed application, analysis, and document status enums. Existing budget tables remain the authoritative cost accounting.

Graceful shutdown flips a shared lifecycle gate before scheduled polling, source ingestion, digest, or maintenance can start new work. Spring's scheduler and web server use bounded graceful termination. Already-running provider/rendering/fetch operations remain subject to their own configured timeouts and may finish within the termination window.

### Release and persistence layout

No V6 migration is required. Published migrations remain: V1 initial schema; V2 Phase 2 truth/workflow persistence; V3 Telegram/application history; V4 analysis/budget; and V5 application documents. Docker Compose runs PostgreSQL 16 with a bounded health check and waits for it before app startup, exposes application HTTP only on host loopback, uses a private named document volume, and passes secrets only at runtime. The application image contains source-built code but no `.env`, generated documents, local data, or secrets. Runtime uses fixed UID/GID `10001`, no Linux capabilities, `no-new-privileges`, a read-only root filesystem, and an explicit writable private temp mount.

Backups must include PostgreSQL, the document volume, and the runtime contact HMAC key. Restore database metadata and storage from a consistent point. A missing/corrupt file is rejected; an unreferenced restored file is inaccessible and later eligible for bounded cleanup. HMAC rotation safely invalidates cache identity but prevents exact reuse of prior contact-dependent keys.

### Final verification and accepted limits

The Stage 6 PostgreSQL 16 Testcontainers flow uses only synthetic external adapters. It processes and deduplicates a manual public vacancy, analyzes, generates and structurally reopens DOCX/PDF, saves, selects, explicitly applies, records interview/follow-up/offer, and reads ordered history. A fake Telegram client proves authorization, progress/final replies, persistent offsets, replay filtering, reconstruction from persisted offset, cached analysis/render reuse, no automatic `APPLIED`, and confirmation failure after commit. Existing Stage 4/5 PostgreSQL tests remain the production-concurrency evidence for budget locking and document claim uniqueness; application optimistic locking and manual deduplication also remain enabled.

There is no multi-user support, HTTP authentication, automatic submission, employer upload, screening-answer automation, recruiter contact, protected-page automation, CAPTCHA/authentication bypass, universal ATS guarantee, or perfect hallucination-prevention guarantee. Crawlee and CloakBrowser are not dependencies; their consideration begins only after Phase 2 is merged.

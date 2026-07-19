# Phase 2 architecture — Stages 1 and 2

Stage 1 establishes the versioned candidate truth model and future workflow persistence. Stage 2 adds manually submitted public vacancy URLs. Telegram command polling, application transitions, LLM calls, resume tailoring, cover-note generation, DOCX/PDF rendering, and delivery are not implemented yet.

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
```

Stage 1 components perform no external calls. The Stage 2 endpoint performs only the explicitly submitted, policy-approved public fetch or a known ATS public API call.

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

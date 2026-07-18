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

The application service intentionally owns no database transaction while network work is performed. Only `JobProcessor` opens the established per-vacancy transaction after a complete `RawJob` exists. `ManualJobPersistenceService` delegates to that pipeline and recovers a concurrent unique-constraint loser by looking up the winning row, yielding `ALREADY_EXISTS` rather than an internal error.

For non-ATS pages, the stored source key is namespaced by canonical hostname (with a bounded SHA-256 fallback for unusually long hostnames). This preserves a page-provided external ID without allowing common identifiers such as `123` to collide across unrelated sites.

### Destination security

`ManualUrlPolicy` is applied to the submitted URL and every redirect before a request is sent. It:

- accepts only `http` and `https`, rejects embedded credentials, fragments, malformed/oversized URLs, and local/internal hostnames;
- resolves the hostname and rejects any loopback, private, link-local, multicast, unspecified, reserved/documentation, carrier-grade NAT, unique-local IPv6, or cloud-metadata address;
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

## Verification

Fast tests use H2 in PostgreSQL compatibility mode. They cover URL/DNS policy, redirect revalidation, size/time/content-type bounds, known ATS reuse, JSON-LD variants, metadata fallback, protected and unsupported pages, typed controller responses, persistence, and a two-thread duplicate race. The `*IT` suite uses PostgreSQL 16 through Testcontainers and verifies Flyway, Hibernate validation, manual-pipeline persistence/deduplication, round trips, uniqueness, foreign keys, and cascades. Run:

```bash
./mvnw -DskipTests compile
./mvnw test
./mvnw verify
```

Docker must be available for the PostgreSQL integration tests to execute.

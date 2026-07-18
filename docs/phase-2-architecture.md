# Phase 2 architecture — Stage 1

This document describes only Phase 2 Stage 1. Manual vacancy URLs, Telegram command polling, application transitions, LLM calls, resume tailoring, cover-note generation, DOCX/PDF rendering, and delivery are not implemented yet.

## Scope

Stage 1 preserves the complete Phase 1 ingestion pipeline and adds persistence foundations in the same modular monolith:

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
```

No Stage 1 component performs an external call.

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

Spring Data repositories exist for each aggregate and normalized fact/reference table. Entities use constructor-based creation and typed enums. External DTOs and network behavior are not part of Stage 1.

`llm_usage_events` deliberately stores accounting metadata only: operation, provider, model, token counts, estimation marker, cost, status, fallback marker, sanitized failure category, job reference, and timestamp. It has no prompt, response, header, credential, or candidate-contact column.

`telegram_bot_state` permits only the stable `long-polling` singleton key and contains no bot token.

## Verification

Fast tests use H2 in PostgreSQL compatibility mode. The `*IT` suite uses PostgreSQL 16 through Testcontainers and verifies Flyway, Hibernate validation, round trips, uniqueness, foreign keys, and cascades. Run:

```bash
./mvnw -DskipTests compile
./mvnw test
./mvnw verify
```

Docker must be available for the PostgreSQL integration tests to execute.

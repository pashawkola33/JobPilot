# JobPilot

![CI](https://github.com/pashawkola33/JobPilot/actions/workflows/ci.yml/badge.svg) ![Java](https://img.shields.io/badge/Java-21-orange) ![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.3-brightgreen) ![React](https://img.shields.io/badge/React-19-61DAFB) ![PostgreSQL](https://img.shields.io/badge/PostgreSQL-16-336791) ![Docker](https://img.shields.io/badge/Docker-ready-2496ED) ![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)

JobPilot is a human-in-the-loop vacancy discovery and review system for early-career software roles in Bucharest and remote-from-Romania positions.

It ingests public ATS vacancies, applies deterministic location, career-level, and role-relevance screening, deduplicates and stores jobs in PostgreSQL, scores suitable vacancies against a versioned candidate profile, and exposes the review workflow through Telegram and an authenticated Telegram Mini App.

JobPilot does **not** submit applications, answer employer screening questions, accept agreements, contact recruiters, or silently invent candidate facts. Optional job analysis and document generation remain human-controlled.

## Current status

- **Phase 1 — ingestion and persistence:** complete.
- **Phase 2 — candidate workflow, Telegram, analysis and documents:** complete.
- **Phase 2.5 — eligibility and relevance screening:** complete.
- **Phase 3 — source reliability and coverage:** complete.
- **Phase 4A — review workflow:** complete.
- **Phase 4B — ranking calibration:** in progress.
- Optional browser rendering remains **disabled by default** and is not considered production-ready.

The final Phase 3 source-health validation completed **56/56 tenant attempts successfully**, including bounded Lever pagination for the previously oversized Veeva board. The run fetched **6,805 unique raw vacancies** with zero raw duplicates and reconciled them to **3 MATCH, 104 REVIEW and 6,698 REJECT**. See [`docs/lever-pagination-live-validation.md`](docs/lever-pagination-live-validation.md) and [`docs/roadmap.md`](docs/roadmap.md).

## Core capabilities

### Public ATS ingestion

JobPilot has bounded adapters for:

- Greenhouse
- Lever
- Ashby
- Recruitee
- SmartRecruiters
- Workday

The audited development registry contains 53 Greenhouse/Lever/Ashby/Recruitee/SmartRecruiters tenants. Workday uses explicit `tenant:shard:careerSite` configuration and is empty by default.

Each source is isolated so one failing tenant or vacancy cannot abort the complete fetch. External responses are time-bounded and size-bounded; the shared response cap defaults to 10 MiB and oversized responses fail closed.

### Deterministic screening

Every vacancy passes three independent screening concerns before it becomes useful to the review workflow:

1. **Location eligibility** — Bucharest or an explicit Romania-compatible remote scope.
2. **Early-career eligibility** — internship, trainee, graduate, entry-level or junior evidence without incompatible mandatory experience/seniority.
3. **Role relevance** — Java/JVM/software-development evidence and explicit rejection of clearly irrelevant roles.

The final disposition is persisted as:

- `MATCH` — strong deterministic relevance evidence;
- `REVIEW` — potentially useful but needs human judgment;
- `REJECT` — fails a hard screening requirement.

Rejected vacancies are retained for reconciliation/diagnostics rather than disappearing silently.

### Scoring

Suitable jobs receive a deterministic 0–100 score with component explanations, penalties and hard blockers. The current scorer includes formal eligibility, Java/backend fit, trainee/mentorship quality, supporting technologies, location, experience compatibility and freshness.

The numeric scoring model is currently under **Phase 4B calibration**. Screening disposition and score are separate concepts: screening answers whether a vacancy belongs in MATCH/REVIEW/REJECT; scoring ranks suitable vacancies within the human review workflow.

### Human review workflow

`job_workflow_state` stores the human decision for each vacancy:

- `SAVED`
- `APPLIED`
- `DISMISSED`
- absence = `UNREVIEWED`

Telegram supports private review commands and inline actions for MATCH/REVIEW/Saved/Applied flows. The React + TypeScript Telegram Mini App provides the same durable workflow as a touch-oriented interface.

The Mini App API is intentionally narrow:

- `GET /api/mini-app/v1/snapshot` — authoritative global read;
- `PUT /api/mini-app/v1/jobs/{jobId}/workflow` — idempotent per-job mutation;
- `POST /api/mini-app/v1/undo` — server-owned reversal using an opaque capability.

Telegram `initData` is verified server-side, access is restricted to an explicit numeric user allow-list, and the API is disabled by default.

Mini App mutations use a PostgreSQL-backed mutation ledger and deterministic recovery semantics. A mutation result is authoritative only for the job it changed; global UI state is reconciled from a fresh snapshot.

### Candidate truth, analysis and documents

The verified candidate source lives in `src/main/resources/candidate-profile.yml` and is versioned in PostgreSQL. JobPilot can optionally perform budgeted structured job analysis and generate truthful ATS-oriented DOCX/PDF application documents for private review.

LLM use is optional and disabled by default. Generated material must remain grounded in verified candidate facts; document selection and application submission remain explicit human actions.

### Source health and operations

Each configured tenant has immutable fetch-attempt history plus a current health roll-up. Failures are classified into structured categories such as timeout, network error, rate limit, parse error, response too large and invalid tenant.

Useful endpoints include:

- `GET /health`
- `GET /api/sources/health`

The scheduler is bounded and guarded against overlapping fetches. Production is designed for a single application instance; running a second scheduler/Telegram poller would duplicate work.

## Architecture

```text
Public ATS providers
        |
        v
bounded provider adapters
        |
        v
RawJob normalization
        |
        v
location -> early-career -> relevance screening
        |
        +---------------------------> REJECT diagnostics
        |
        v
canonicalization + deduplication
        |
        v
deterministic requirement extraction + scoring
        |
        v
PostgreSQL
        |
        +--> Telegram bot
        |
        +--> Telegram Mini App
        |
        +--> optional analysis / truthful document generation
```

The backend is Java 21 / Spring Boot with PostgreSQL and Flyway. The Mini App is React 19 + TypeScript + Vite with Playwright coverage. Production artifacts are multi-stage Docker images.

## CI/CD

GitHub Actions is defined in [`.github/workflows/ci.yml`](.github/workflows/ci.yml).

It activates on:

- pull requests targeting `main`;
- pushes to `main`.

The workflow is split into four stages.

### 1. Backend verification

The `Verify` job runs on Ubuntu with Java 21 and performs:

```bash
git diff --check ...
./mvnw -B --no-transfer-progress test
./mvnw -B --no-transfer-progress verify
docker compose config --quiet
docker compose --env-file .env.prod.example -f docker-compose.prod.yml config --quiet
```

`verify` includes PostgreSQL integration tests backed by Testcontainers.

### 2. Mini App verification

The `Verify Mini App` job uses Node 22 and runs:

```bash
npm ci
npm run lint
npm run typecheck
npm run build
npm test
```

`npm test` runs the Playwright Chromium suite.

### 3. Container verification

After backend and Mini App checks pass, CI builds the real `linux/amd64` production image and statically verifies that:

- the Mini App shell, JS bundle and CSS are packaged into the application jar;
- Node, npm, `node_modules` and TypeScript sources do not leak into the runtime image.

This image verification also runs on pull requests, but the PR image is **not pushed anywhere**.

### 4. Release image

Only a successful **push to `main`** reaches the release job. It publishes an immutable image to GHCR using the commit SHA as the tag:

```text
ghcr.io/pashawkola33/jobpilot:<commit-sha>
```

GitHub Actions does **not** deploy the image to the production VM. Deployment remains a separate controlled operator action, so a merge cannot silently restart production.

## Local development

### Requirements

- Java 21
- Docker / Docker Compose
- Node 22 for Mini App work

### Environment

Copy the example configuration and keep real credentials only in your local `.env`:

```bash
cp .env.example .env
```

For a passive local instance, disable scheduled ingestion before starting the stack:

```bash
JOBPILOT_SCHEDULING_ENABLED=false docker compose up --build
```

Then check:

```bash
curl http://127.0.0.1:8080/health
```

Optional integrations such as Telegram, the Mini App API, LLM analysis, document generation and the browser worker are disabled unless explicitly configured.

### Backend tests

```bash
./mvnw test
./mvnw verify
```

Use focused tests during development when possible; the complete CI pipeline provides the final integration gate.

### Mini App

```bash
cd mini-app
npm ci
npm run lint
npm run typecheck
npm run build
npm test
```

### Audited source registry

The tracked development registry is opt-in:

```bash
./mvnw spring-boot:run -Dspring-boot.run.profiles=development
```

Do not run live ingestion merely to verify unrelated application code; source validation is intentionally controlled and documented separately.

## Safety boundaries

JobPilot intentionally avoids several classes of automation:

- no automatic job applications;
- no recruiter outreach;
- no automated agreement acceptance;
- no fabricated résumé facts;
- no CAPTCHA/challenge bypass;
- no authenticated scraping of protected job portals;
- no automatic tenant removal based on transient source failures;
- no automatic production deployment from GitHub Actions.

The optional `scraper-worker` is disabled by default and remains on hold for additional production hardening.

## Repository layout

```text
src/main/java/                 Spring Boot backend
src/main/resources/            configuration, candidate truth, Flyway migrations
src/test/                      backend and PostgreSQL integration tests
mini-app/                      React / TypeScript Telegram Mini App
scraper-worker/                optional browser-rendering worker
docs/                          architecture, audits, live-validation evidence, roadmap
.github/workflows/ci.yml        CI and verified image release pipeline
docker-compose.yml              local/development Compose stack
docker-compose.prod.yml         production Compose definition
```

## Documentation

Start with:

- [`docs/roadmap.md`](docs/roadmap.md) — current phase status;
- [`docs/phase-2-architecture.md`](docs/phase-2-architecture.md) — candidate/application architecture;
- [`docs/source-expansion-audit.md`](docs/source-expansion-audit.md) — source registry evidence;
- [`docs/lever-pagination-live-validation.md`](docs/lever-pagination-live-validation.md) — final source-health closure;
- [`docs/workday-provider-design.md`](docs/workday-provider-design.md) — bounded Workday adapter design;
- [`docs/scoring-zero-diagnosis.md`](docs/scoring-zero-diagnosis.md) — scoring diagnostics;
- [`docs/scoring-calibration-analysis.md`](docs/scoring-calibration-analysis.md) — ranking calibration work;
- [`scraper-worker/README.md`](scraper-worker/README.md) — optional browser worker.

## License

MIT.

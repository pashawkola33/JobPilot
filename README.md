# JobPilot

JobPilot is a human-in-the-loop internship discovery service for entry-level software roles. Phase 1 fetches public Greenhouse and Lever job boards, normalizes and deduplicates vacancies, deterministically extracts requirements, scores them against a configurable candidate profile, and sends strong matches to a Telegram channel. Phase 2 Stage 1 adds the versioned candidate truth model and persistence foundations used by later application-tracking and document-generation stages. Phase 2 Stage 2 adds safe, deterministic processing of manually submitted public vacancy URLs.

JobPilot never submits applications, answers screening questions, accepts agreements, or contacts recruiters. Phase 2 Stage 3 adds an authorized Telegram command interface and a human-maintained application tracker; it does not automate applications. LLM calls, resume tailoring, document generation, and protected-site browser automation remain out of scope.

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

Phase 1 matching facts remain under `jobpilot.candidate` in `application.yml`. The independently versioned Phase 2 truth source is `candidate-profile.yml`; increase `profile-version` whenever verified facts change. Candidate rows are not placed in Flyway migrations.

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

The suite covers normalization, canonical URLs, migration/repository behavior, deduplication, deterministic extraction, scoring/penalties/hard blockers, Greenhouse and Lever payloads, source failure isolation, Telegram messages, candidate-profile validation/versioning, Phase 2 persistence, manual URL validation/redirect safety, bounded fetching, deterministic JSON-LD/metadata parsing, typed API responses, and concurrent duplicate submissions. H2 in PostgreSQL compatibility mode provides fast repository feedback. `./mvnw verify` also runs the `*IT` suite against real PostgreSQL 16 with Testcontainers when Docker is available.

## Security and ethical boundary

- Secrets and generated personal documents are ignored by Git.
- Tokens are read only from environment variables and are not logged.
- Remote calls have connection/read timeouts, bounded responses, and transient retries.
- Only documented public Greenhouse and Lever APIs are queried.
- Manual URL fetches allow only `http`/`https`, reject credentials, validate every original and redirected hostname through DNS, and block loopback, private, link-local, multicast, unspecified, reserved, benchmarking, and cloud-metadata destinations. IPv4 destinations embedded in 6to4, Teredo, NAT64, IPv4-compatible, or IPv4-mapped IPv6 addresses are decoded and checked by the same IPv4 policy.
- Manual fetches send only fixed `Accept` and `User-Agent` headers—never cookies, authorization, provider tokens, or user-supplied headers—and accept only bounded HTML, XHTML, text, or JSON responses.
- LinkedIn and protected portals are not scraped; CAPTCHAs, authentication, robots controls, and rate limits are never bypassed.
- JobPilot discovers and ranks vacancies only. Every application remains a deliberate manual action.

## Current limitations

- HTTP adapters currently use Spring `RestClient`; migration to the originally preferred reactive `WebClient` is pending dependency availability.
- Manual ingestion supports known ATS links, schema.org `JobPosting`, and confidently identified public job metadata; arbitrary company-page scraping, Jooble, and RSS adapters are not implemented.
- Telegram command polling is single-instance only; webhooks and distributed poller coordination are not implemented.
- No LLM enrichment, resume tailoring, DOCX/PDF, or cover notes.
- No browser automation or fallback for JavaScript-only, authenticated, CAPTCHA, or otherwise protected vacancy pages.
- The standard Java HTTP client performs its own connection-time DNS lookup after policy validation, leaving a narrow DNS-rebinding race; production deployments should also block private and metadata ranges at the network layer.
- Resume, cover-note, and LLM-usage models remain persistence foundations for later stages; Stage 3 now implements the application and Telegram-state workflows.
- PostgreSQL Testcontainers integration tests require a working Docker environment.
- Board-wide APIs are filtered after retrieval; configure only permitted boards and respect provider policies.

# JobPilot

JobPilot is a human-in-the-loop internship discovery service for entry-level software roles. Phase 1 fetches public Greenhouse and Lever job boards, normalizes and deduplicates vacancies, deterministically extracts requirements, scores them against a configurable candidate profile, and sends strong matches to a Telegram channel.

JobPilot never submits applications, answers screening questions, accepts agreements, or contacts recruiters. Phase 1 has no application commands, manual URL intake, LLM integration, or resume generation.

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
```

Integrations implement `JobSource`; persistence is isolated behind Spring Data repositories. `JobProcessor` gives one vacancy a transaction, while `JobIngestionService` contains failures so one bad source or posting cannot abort the complete fetch. `JobSchedulingService` prevents overlapping fetches with an atomic guard.

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
| `JOB_FETCH_CRON` | No | Default `0 0 */6 * * *` |
| `DAILY_DIGEST_CRON` | No | Default `0 0 9 * * *` |
| `STALE_DAYS` | No | Default `30` |

Candidate matching facts are configuration under `jobpilot.candidate` in `application.yml`, not production migration seed data. Edit or externalize that block before using JobPilot for another candidate.

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

The suite covers normalization, canonical URLs, migration/repository behavior, deduplication, deterministic extraction, scoring/penalties/hard blockers, Greenhouse and Lever payloads, source failure isolation, and Telegram messages. The repository test uses H2 in PostgreSQL compatibility mode for local feedback; a real PostgreSQL/Testcontainers test is deferred until the Docker-capable verification environment is available.

## Security and ethical boundary

- Secrets and generated personal documents are ignored by Git.
- Tokens are read only from environment variables and are not logged.
- Remote calls have connection/read timeouts, bounded responses, and transient retries.
- Only documented public Greenhouse and Lever APIs are queried.
- LinkedIn and protected portals are not scraped; CAPTCHAs, authentication, robots controls, and rate limits are never bypassed.
- JobPilot discovers and ranks vacancies only. Every application remains a deliberate manual action.

## Phase 1 limitations

- HTTP adapters currently use Spring `RestClient`; migration to the originally preferred reactive `WebClient` is pending dependency availability.
- No manual vacancy URL ingestion, company-page scraping, Jooble, or RSS adapters.
- No Telegram command polling or application tracker.
- No LLM enrichment, resume tailoring, DOCX/PDF, or cover notes.
- No PostgreSQL Testcontainers execution in an environment without Docker.
- Board-wide APIs are filtered after retrieval; configure only permitted boards and respect provider policies.

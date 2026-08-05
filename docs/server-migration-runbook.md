# Server migration runbook — MacBook to Azure Linux VM

Phase 5A prepares this repository for a reproducible deployment. **No Azure resource is created
and no production data is moved by this document alone.** Every command below is executed by a
human operator, in order, and each phase has an explicit stop condition.

Two invariants govern the whole migration:

1. **Exactly one scheduler and exactly one Telegram poller may exist at any moment.** Two pollers
   consuming the same `getUpdates` offset lose messages and double-deliver notifications.
2. **The PostgreSQL volume is never deleted.** `docker compose down -v` is forbidden on both
   hosts, in every phase, including rollback.

| | Mac (current production) | Azure VM (target) |
|---|---|---|
| Image | built locally from source | pulled from `ghcr.io/pashawkola33/jobpilot` by digest/SHA tag |
| Compose file | `docker-compose.yml` | `docker-compose.prod.yml` |
| Config | local `.env` (never edited by tooling) | `/opt/jobpilot/.env`, mode `600` |
| App port | `127.0.0.1:8080` | `127.0.0.1:8080` (loopback only, reached over an SSH tunnel) |
| PostgreSQL | named volume, no host port | named volume, no host port |

---

## 1. Server preparation

Run once, before any data is copied.

### 1.1 Operating system

Ubuntu Server **24.04 LTS** (or the current LTS), x86-64. The release workflow publishes
`linux/amd64` only, so the VM size must be an Intel/AMD SKU, not an Arm (`Dpsv5`/`Epsv5`) one.

```bash
sudo apt-get update && sudo apt-get -y upgrade
sudo apt-get -y install ca-certificates curl gnupg ufw
sudo timedatectl set-timezone UTC
```

### 1.2 Non-root user

```bash
sudo adduser --disabled-password --gecos "" jobpilot
sudo usermod -aG docker jobpilot          # after Docker is installed (1.4)
```

`jobpilot` owns the deployment and is the only account that runs Compose. Membership of the
`docker` group is root-equivalent — do not grant it to any other account.

### 1.3 SSH key authentication

The key pair was created in Phase 5-prep: `~/.ssh/jobpilot_azure` (private, stays on the Mac) and
`~/.ssh/jobpilot_azure.pub`. Install the public key for both the provisioning admin user and
`jobpilot`:

```bash
sudo install -d -m 700 -o jobpilot -g jobpilot /home/jobpilot/.ssh
sudo install -m 600 -o jobpilot -g jobpilot /dev/stdin /home/jobpilot/.ssh/authorized_keys <<'KEY'
ssh-ed25519 AAAA... jobpilot_azure
KEY
```

Then harden `/etc/ssh/sshd_config`:

```
PasswordAuthentication no
PermitRootLogin no
KbdInteractiveAuthentication no
```

```bash
sudo sshd -t && sudo systemctl reload ssh
```

Verify from Termius **in a second session** before closing the first one.

### 1.4 Firewall

```bash
sudo ufw default deny incoming
sudo ufw default allow outgoing
sudo ufw allow OpenSSH
sudo ufw enable
sudo ufw status verbose
```

Nothing else is opened. The app listens on `127.0.0.1:8080` and is reached with an SSH tunnel:

```bash
ssh -i ~/.ssh/jobpilot_azure -N -L 8080:127.0.0.1:8080 jobpilot@<vm-ip>
```

Also restrict the Azure Network Security Group to port 22 from the operator's address. Docker
publishes ports by writing `DOCKER-USER` iptables rules that bypass `ufw`; the `127.0.0.1:` prefix
in `docker-compose.prod.yml` is what actually keeps the app off the public interface, so never
remove it.

### 1.5 Docker Engine and Compose plugin

Official repository only — the `docker.io` Ubuntu package ships an old Compose v1.

```bash
sudo install -m 0755 -d /etc/apt/keyrings
curl -fsSL https://download.docker.com/linux/ubuntu/gpg \
  | sudo gpg --dearmor -o /etc/apt/keyrings/docker.gpg
sudo chmod a+r /etc/apt/keyrings/docker.gpg
echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.gpg] \
https://download.docker.com/linux/ubuntu $(. /etc/os-release && echo $VERSION_CODENAME) stable" \
  | sudo tee /etc/apt/sources.list.d/docker.list > /dev/null
sudo apt-get update
sudo apt-get -y install docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
sudo systemctl enable --now docker
docker compose version
```

**The production image is never built on the VM.** The B-series VM has neither the RAM nor the
credit budget for a Maven build, and a locally built image would not be reproducible. The VM only
ever runs `docker compose pull`.

### 1.6 Deployment directory

```bash
sudo install -d -o jobpilot -g jobpilot -m 750 /opt/jobpilot
sudo install -d -o jobpilot -g jobpilot -m 750 /opt/jobpilot/backups
```

| Path | Owner | Mode | Contents |
|---|---|---|---|
| `/opt/jobpilot` | `jobpilot:jobpilot` | `750` | `docker-compose.prod.yml`, `scripts/` |
| `/opt/jobpilot/.env` | `jobpilot:jobpilot` | `600` | secrets and tenant configuration |
| `/opt/jobpilot/backups` | `jobpilot:jobpilot` | `750` | dumps, checksums, metadata |

Copy the deployment files from the repository (not the whole repo):

```bash
scp -i ~/.ssh/jobpilot_azure docker-compose.prod.yml jobpilot@<vm-ip>:/opt/jobpilot/
scp -i ~/.ssh/jobpilot_azure -r scripts jobpilot@<vm-ip>:/opt/jobpilot/
scp -i ~/.ssh/jobpilot_azure .env.prod.example jobpilot@<vm-ip>:/opt/jobpilot/
```

### 1.7 Configuration file

```bash
cd /opt/jobpilot
cp .env.prod.example .env
chmod 600 .env
nano .env      # fill every MANDATORY value; leave the one-shot block untouched
```

`.env.prod.example` ships with `JOBPILOT_SCHEDULING_ENABLED=false`, `TELEGRAM_BOT_ENABLED=false`,
`TELEGRAM_COMMANDS_ENABLED=false`, `LLM_ENABLED=false`, `SCRAPER_WORKER_ENABLED=false` and both
guarded command modes `OFF`. **Keep all of them off until the cutover checklist says otherwise.**

### 1.8 Registry access

Images are public-readable if the GHCR package is set to public; otherwise authenticate once with
a read-only classic PAT that has `read:packages`:

```bash
echo "<token>" | docker login ghcr.io -u pashawkola33 --password-stdin
```

The token is never written into `.env`, `docker-compose.prod.yml`, or any file in this repository.

**Stop condition:** `docker compose -f docker-compose.prod.yml config --quiet` exits 0 on the VM
and `docker compose -f docker-compose.prod.yml pull` succeeds. No container is started yet.

---

## 2. Rehearsal

The rehearsal proves the restore path. **The Mac production stack keeps running untouched
throughout, including its scheduler and Telegram poller.** The Azure app is started with
scheduling and Telegram disabled, so only one poller exists at all times.

### 2.1 Create a dump on the Mac

```bash
cd ~/Developer/Job-Bot
POSTGRES_DB=jobpilot POSTGRES_USER=jobpilot ./scripts/backup-db.sh
```

This produces three files under `backups/`:

```
jobpilot_<UTC timestamp>.dump          # pg_dump custom format (-Fc)
jobpilot_<UTC timestamp>.dump.sha256   # checksum
jobpilot_<UTC timestamp>.dump.meta     # timestamp, schema version, app commit, row counts
```

`pg_dump` is a read-only consistent snapshot; it does not interrupt the running app.

### 2.2 Record the source fingerprints

Take them from the same session, immediately after the dump:

```bash
docker compose exec -T postgres psql -U jobpilot -d jobpilot -At -c "
  select 'jobs', count(*), md5(string_agg(t::text, '|' order by t.id)) from jobs t
  union all select 'job_scores', count(*), md5(string_agg(t::text,'|' order by t.id)) from job_scores t
  union all select 'job_requirements', count(*), md5(string_agg(t::text,'|' order by t.id)) from job_requirements t
  union all select 'source_fetch_logs', count(*), md5(string_agg(t::text,'|' order by t.id)) from source_fetch_logs t
  union all select 'source_tenant_fetch_logs', count(*), md5(string_agg(t::text,'|' order by t.id)) from source_tenant_fetch_logs t;
" | tee /tmp/source-fingerprints.txt
```

Expected at the time of writing: `jobs=129`, `job_scores=105`, `job_requirements=129`.

### 2.3 Copy the dump to Azure

```bash
scp -i ~/.ssh/jobpilot_azure \
  backups/jobpilot_<ts>.dump backups/jobpilot_<ts>.dump.sha256 backups/jobpilot_<ts>.dump.meta \
  jobpilot@<vm-ip>:/opt/jobpilot/backups/
```

`scp` over the existing SSH key is the transport; the dump is never uploaded to any third-party
storage, and never committed (`backups/` is git-ignored).

### 2.4 Start PostgreSQL only, then restore

```bash
cd /opt/jobpilot
docker compose -f docker-compose.prod.yml up -d postgres
docker compose -f docker-compose.prod.yml ps          # wait for healthy

RESTORE_CONFIRM=jobpilot ./scripts/restore-db.sh backups/jobpilot_<ts>.dump
```

`restore-db.sh` verifies the SHA-256 checksum, refuses to run unless the target database has zero
tables in the `public` schema, and requires `RESTORE_CONFIRM` to equal the target database name.
It never drops a database and never touches a volume.

### 2.5 Verify the restored database

The script prints the post-restore counts and the Flyway schema version. Compare them against
`/tmp/source-fingerprints.txt` from step 2.2 — counts **and** md5 fingerprints must match exactly,
and `flyway_schema_history` must report **V12 (`job review workflow`)** as current.

```bash
docker compose -f docker-compose.prod.yml exec -T postgres \
  psql -U jobpilot -d jobpilot -At -c \
  "select version, description, success from flyway_schema_history order by installed_rank desc limit 1;"
```

### 2.6 Start the Azure app with everything off

`.env` still has `JOBPILOT_SCHEDULING_ENABLED=false`, `TELEGRAM_BOT_ENABLED=false` and
`TELEGRAM_COMMANDS_ENABLED=false`.

```bash
docker compose -f docker-compose.prod.yml up -d
curl -s http://127.0.0.1:8080/health | jq
```

Required response: `status: UP`, `components.database=READY`, `components.schema=READY`,
`components.telegram=DISABLED`, `components.llm=DISABLED`, and a `commit` field equal to the Git
SHA the image was built from — **not** `unknown`. Flyway runs `validate` on startup, so a healthy
`schema` component is itself the second Flyway validation.

### 2.7 Verify outbound ATS access

Azure egress is open by default, but confirm before the cutover that the ATS endpoints answer from
the VM:

```bash
for u in \
  "https://boards-api.greenhouse.io/v1/boards/gitlab/jobs?content=false" \
  "https://api.lever.co/v0/postings/swissborg?mode=json" \
  "https://api.ashbyhq.com/posting-api/job-board/linear" ; do
  printf '%s -> ' "$u"; curl -s -o /dev/null -w '%{http_code}\n' --max-time 15 "$u"
done
```

All must return `200`. This is a read-only probe of public endpoints; it triggers no ingestion,
because the scheduler is disabled and nothing writes to the database.

### 2.8 Stop the rehearsal

```bash
docker compose -f docker-compose.prod.yml stop app
```

Leave PostgreSQL running or stop it — **do not remove the volume**. The rehearsal data will be
replaced by the final dump during cutover.

**Stop condition:** counts and fingerprints matched, schema is V12, `/health` reported the real
commit, ATS endpoints returned 200, and the Mac stack is still serving with its own scheduler and
poller. The Mac remains production until step 3.

---

## 3. Final cutover

Schedule it in a quiet window — the fetch cron is `0 0 */6 * * *` (UTC), so start at least 30
minutes after a fetch boundary.

### 3.1 Confirm no ingestion is active

On the Mac:

```bash
docker compose exec -T postgres psql -U jobpilot -d jobpilot -At -c \
  "select count(*) from source_fetch_logs where status = 'RUNNING';"
```

Must be `0`. If it is not, wait for the run to finish. Never cut over mid-fetch.

### 3.2 Stop only the Mac app

```bash
docker compose stop app
```

`docker compose stop` — never `down`, never `down -v`. This ends the Mac scheduler and Telegram
poller; PostgreSQL stays up so the final dump can be taken. From this moment there is **no** active
poller anywhere, which is the safe state to be in.

### 3.3 Final dump

```bash
POSTGRES_DB=jobpilot POSTGRES_USER=jobpilot ./scripts/backup-db.sh
```

Record the counts and fingerprints again (step 2.2 command) — with the app stopped this is a
quiesced snapshot, so nothing can change between the fingerprint and the dump.

### 3.4 Restore into a clean Azure database

The rehearsal data must be gone first. Recreate **only the Azure** database, from inside the
running PostgreSQL container — this drops a database, never a volume:

```bash
cd /opt/jobpilot
docker compose -f docker-compose.prod.yml stop app
docker compose -f docker-compose.prod.yml exec -T postgres \
  psql -U jobpilot -d postgres -c 'drop database jobpilot;' -c 'create database jobpilot owner jobpilot;'
```

Then copy the final dump over (step 2.3) and restore it (step 2.4). Verify as in step 2.5:
counts and fingerprints identical to the Mac's final snapshot, Flyway current = V12.

### 3.5 Start the Azure app, still disabled

```bash
docker compose -f docker-compose.prod.yml up -d
curl -s http://127.0.0.1:8080/health | jq
```

Health checks required before enabling anything:

- `status: UP`, `database=READY`, `schema=READY`
- `telegram=DISABLED`, `llm=DISABLED`
- `commit` equals the deployed image's Git SHA
- `docker compose -f docker-compose.prod.yml ps` shows both containers `healthy`
- `docker compose -f docker-compose.prod.yml logs app | grep -i 'error\|exception'` is empty

### 3.6 Enable scheduler and Telegram — Azure only

Confirm the Mac app is still stopped (`docker compose ps` on the Mac shows `app` not running),
then edit `/opt/jobpilot/.env`:

```
JOBPILOT_SCHEDULING_ENABLED=true
TELEGRAM_BOT_ENABLED=true
TELEGRAM_COMMANDS_ENABLED=true
```

```bash
docker compose -f docker-compose.prod.yml up -d app
curl -s http://127.0.0.1:8080/health | jq '.components.telegram'   # ENABLED
```

Send one Telegram command from the allowed chat and confirm a single reply — a duplicated reply
means two pollers are live; if so, immediately stop the Azure app and re-check the Mac.

### 3.7 Keep the Mac stopped

The Mac app must **not** be restarted. Prevent an accidental `docker compose up`:

```bash
docker update --restart=no jobpilot-app-1     # container name from `docker compose ps`
```

The Mac PostgreSQL container and its volume are preserved untouched as the rollback target.

**Stop condition:** Azure is serving with exactly one scheduler and one poller, the Mac app is
stopped with its data intact, and the first scheduled fetch on Azure has completed with
`source_fetch_logs` showing a `SUCCESS` row.

---

## 4. Rollback

Trigger if Azure health fails, ingestion breaks, or Telegram misbehaves. Rollback is safe as long
as it is done in this order — the overlap of two pollers is the only thing that can corrupt state.

1. **Stop the Azure app first.**
   ```bash
   docker compose -f docker-compose.prod.yml stop app
   ```
2. **Confirm nothing remains active on Azure.**
   ```bash
   docker compose -f docker-compose.prod.yml ps                 # app must not be running
   curl -s --max-time 3 http://127.0.0.1:8080/health || echo "app down (expected)"
   ```
   No scheduler thread and no `getUpdates` poller may survive. Only after this is confirmed:
3. **Restart the preserved Mac app.**
   ```bash
   cd ~/Developer/Job-Bot
   docker compose up -d app
   curl -s http://127.0.0.1:8080/health | jq
   ```
   The Mac volume was never touched, so it resumes from the pre-cutover state.
4. **Data written on Azure after cutover is not merged back.** If the Azure window contained a
   fetch, take an Azure dump first (`./scripts/backup-db.sh` on the VM) and keep it for a manual
   reconciliation decision. Do not restore it over the Mac database.
5. **Never** run `docker compose down -v` on either host, and never delete either PostgreSQL
   volume, at any point in the rollback.

---

## 5. Backups on Azure

### 5.1 Daily dump

As `jobpilot`, `crontab -e`:

```cron
17 2 * * * cd /opt/jobpilot && POSTGRES_DB=jobpilot POSTGRES_USER=jobpilot BACKUP_DIR=/opt/jobpilot/backups ./scripts/backup-db.sh >> /opt/jobpilot/backups/backup.log 2>&1
```

Each run writes the dump, its `.sha256`, and a `.meta` file recording UTC timestamp, Flyway schema
version, app commit and row counts.

### 5.2 Off-server copy

A backup that lives only on the VM does not survive the VM. Pull daily from the Mac (pull, not
push — the VM then needs no credentials for the Mac):

```cron
40 2 * * * rsync -az -e "ssh -i ~/.ssh/jobpilot_azure" jobpilot@<vm-ip>:/opt/jobpilot/backups/ ~/Developer/JobPilot-backups/
```

### 5.3 Retention

| Copy | Keep |
|---|---|
| On the VM | 14 daily dumps (~3 MB total at the current corpus size) |
| On the Mac | 60 daily dumps, plus every pre-migration dump indefinitely |

Prune on the VM with a size-bounded find, never a blanket delete:

```cron
50 2 * * * find /opt/jobpilot/backups -name 'jobpilot_*.dump*' -mtime +14 -delete
```

### 5.4 Restore tests

An untested backup is not a backup. **Monthly**, restore the newest dump into a scratch database
and compare counts — this never touches `jobpilot`:

```bash
docker compose -f docker-compose.prod.yml exec -T postgres \
  psql -U jobpilot -d postgres -c 'create database restore_test owner jobpilot;'
POSTGRES_DB=restore_test RESTORE_CONFIRM=restore_test ./scripts/restore-db.sh backups/<newest>.dump
docker compose -f docker-compose.prod.yml exec -T postgres \
  psql -U jobpilot -d postgres -c 'drop database restore_test;'
```

### 5.5 Disk monitoring

A full disk stops PostgreSQL and corrupts nothing but blocks everything. The B1s OS disk is 30 GB;
Docker images plus the database plus 14 dumps stay well under 10 GB, so a rising trend means a log
or image leak.

```cron
0 6 * * * df -h / | awk 'NR==2 && int($5) > 80 {print "jobpilot: disk " $5}' 
```

Route the output to mail or a Telegram message. Also prune unused images after each deployment:

```bash
docker image prune -f          # dangling only; never `-a` while a rollback image may be needed
```

Keep the Azure cost alert at **$8/month**; a runaway container is usually visible on the budget
alert before it is visible on disk.

---

## 6. Secrets: server `.env` now, Doppler later

**This phase deliberately does not use Doppler.** The rehearsal and the first cutover run from
`/opt/jobpilot/.env` with mode `600`, owned by `jobpilot`. That file is the only place production
secrets exist on the VM. It is not in the repository, not in the image, and not in any Compose
file.

`jobpilot/prd` already exists in Doppler and currently holds only safe disabled defaults
(`scheduling=false`, `Telegram=false`, `LLM=false`, cleanup and rescore `OFF`). Future integration,
**not** part of Phase 5A:

1. Populate `jobpilot/prd` with the real values from the server `.env`.
2. Install the Doppler CLI on the VM and authenticate with a **service token scoped read-only to
   `jobpilot/prd`**, stored in `/etc/doppler/token` mode `600`, root-owned.
3. Replace the Compose invocation with
   `doppler run --project jobpilot --config prd -- docker compose -f docker-compose.prod.yml up -d`.
4. Only after a successful start via Doppler, remove `/opt/jobpilot/.env` — and keep an encrypted
   offline copy first, because a Doppler outage would otherwise make the stack unstartable.

No service token, no Doppler secret, and no Doppler reference is added to this repository in
Phase 5A.

---

## 7. AI boundary

The LLM architecture is unchanged and stays **disabled** (`LLM_ENABLED=false` in the image
defaults, in `docker-compose.prod.yml`, and in `.env.prod.example`). No LLM provider, base URL,
API key or budget value is configured for the migration.

Enabling analysis is a separate, later phase with its own budget guards
(`LLM_REQUEST_BUDGET_USD`, `LLM_DAILY_BUDGET_USD`, `LLM_MONTHLY_BUDGET_USD`) and must not be
combined with the cutover — a migration and a first paid-API activation should never share a
blast radius.

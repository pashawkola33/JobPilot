# Phase 3.3A source expansion audit

Expansion of the tracked ATS tenant registry using only the four existing adapters
(Greenhouse, Lever, Ashby, Recruitee). No new provider was implemented and no generic
job-board scraper was added.

Baseline registry: **45 tenants**. Candidates researched: **14**. Accepted: **3**.
Final registry: **48 tenants**.

The accepted count is well below this phase's 12–25 target. See
[Coverage shortfall](#coverage-shortfall) — the gap is a research-completeness gap, not a
relaxation of the evidence rules. No identifier was guessed or enumerated.

## Evidence and verification method

For each candidate the tenant identifier was taken only from an official careers page that
linked to a supported ATS, or from a public ATS-domain URL for that employer. Each derived
identifier was then confirmed with **one** unauthenticated request to the exact public
endpoint the existing adapter uses:

| Provider | Endpoint verified |
|---|---|
| Greenhouse | `https://boards-api.greenhouse.io/v1/boards/<tenant>/jobs?content=true` |
| Lever | `https://api.lever.co/v0/postings/<tenant>?mode=json` |
| Ashby | `https://api.ashbyhq.com/posting-api/job-board/<tenant>` |
| Recruitee | `https://<tenant>.recruitee.com/api/offers/` |

Only HTTP status, content type, byte size, job count, and title/location fields were
inspected. No response body, description, cookie, token, header, or query string was stored
or logged.

## Audit table

Geography columns record what the board contained at verification time.

| Company | Careers domain | Provider | Tenant | Evidence type | HTTP | Board size | Size status | Bucharest | Romania | EU/EMEA remote | Early-career | Duplicate | Decision | Reason |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| UiPath | uipath.com | Ashby | `uipath` | Official careers page links `jobs.ashbyhq.com/uipath` | 200 | 115 jobs, 1.44 MiB | within limit | yes | yes | yes | yes | no | **ADD_SUPPORTED_TENANT** | Bucharest-founded, large Romanian engineering base; criteria A, B, E |
| Bitdefender | bitdefender.com | Greenhouse | `scbitdefendersrl` | Official jobs page links `greenhouse.io?job_board=scbitdefendersrl` | 200 | 36 jobs, 0.43 MiB | within limit | yes | yes | — | yes | no | **ADD_SUPPORTED_TENANT** | Bucharest HQ cybersecurity employer; directly matches the security/SOC/GRC profile; criteria A, B, E |
| Showpad | showpad.com | Greenhouse | `showpad` | Public ATS job URLs on `job-boards.greenhouse.io/showpad` | 200 | 30 jobs, 0.23 MiB | within limit | 5 roles | yes | — | Software Engineering Intern (Bucharest) observed during research | no | **ADD_SUPPORTED_TENANT** | Active Bucharest engineering team; criteria A, E |
| Alchemy | alchemy.com | Greenhouse | `alchemy` | Public ATS job URLs (archived postings) | **404** | — | — | — | — | — | — | — | **REJECT_INVALID** | Board token no longer resolves; the surfaced URLs were stale postings |
| FintechOS | fintechos.com | self-hosted | — | Careers page serves jobs on own domain | — | — | — | yes | yes | — | — | — | **DEFER_UNSUPPORTED_ATS** | No supported ATS; Bucharest-relevant, revisit in 3.3B |
| Paysafe | paysafe.com | `jobs.paysafe.com` | — | Careers page links to own jobs host | — | — | — | yes | yes | — | — | — | **DEFER_UNSUPPORTED_ATS** | Large Bucharest office but unsupported ATS; strong 3.3B candidate |
| MultiversX | multiversx.com | unknown | — | No careers page reachable at the researched URL | — | — | — | — | yes | — | — | — | **HOLD_AMBIGUOUS** | Romanian employer, ATS unconfirmed; needs a correct careers URL |
| Snyk | snyk.io | unknown | — | Job board is client-rendered; no ATS URL in markup | — | — | — | — | — | yes | — | — | **HOLD_AMBIGUOUS** | Security employer worth re-checking; ATS not confirmed |
| Sysdig | sysdig.com | unknown | — | No ATS URL in careers markup | — | — | — | — | — | yes | — | — | **HOLD_AMBIGUOUS** | ATS not confirmed |
| Personio | personio.com | unknown | — | Careers page returned HTTP 429 | — | — | — | — | — | yes | — | — | **HOLD_AMBIGUOUS** | Rate limited; not retried, per the one-request rule |
| CrowdStrike | crowdstrike.com | unsupported | — | Bucharest internships surfaced via aggregator only | — | — | — | yes | yes | — | yes | — | **DEFER_UNSUPPORTED_ATS** | Bucharest internships exist but not on a supported adapter |
| Anyscale | anyscale.com | Ashby | — | Lever board redirects to `jobs.ashbyhq.com/anyscale` | not requested | — | — | no | no | — | — | — | **REJECT_GEOGRAPHY** | US-centric; no Romania or Romania-eligible European evidence |
| Deutsche Bank | db.com | unsupported | — | Junior Bucharest roles via aggregator | — | — | — | yes | yes | — | yes | — | **DEFER_UNSUPPORTED_ATS** | Enterprise ATS outside the four adapters |
| GE Vernova / AECOM / HARMAN / Schneider Electric | various | unsupported | — | Bucharest roles via aggregator | — | — | — | yes | yes | — | yes | — | **DEFER_UNSUPPORTED_ATS** | Enterprise ATS outside the four adapters; several are also outside the software profile |

## Decision summary

| Decision | Count |
|---|---|
| ADD_SUPPORTED_TENANT | 3 |
| REJECT_INVALID | 1 |
| REJECT_GEOGRAPHY | 1 |
| DEFER_UNSUPPORTED_ATS | 5 |
| HOLD_AMBIGUOUS | 4 |
| REJECT_DUPLICATE / REJECT_RELEVANCE / REJECT_TOO_LARGE | 0 |

## Accepted tenants

| Provider | Tenant | Company | Board size | Why it improves this search |
|---|---|---|---|---|
| Ashby | `uipath` | UiPath | 115 | Bucharest-founded with a large Romanian engineering organisation; recurring graduate and junior openings in the exact Java/backend and automation areas targeted |
| Greenhouse | `scbitdefendersrl` | Bitdefender | 36 | Bucharest-headquartered cybersecurity employer; the closest match to the cybersecurity internship, SOC, and QA-automation entry points in the profile |
| Greenhouse | `showpad` | Showpad | 30 | Active Bucharest engineering site; a Software Engineering Intern role in Bucharest was observed during source research |

Provider balance: two Greenhouse, one Ashby. No Lever or Recruitee candidate reached the
evidence bar in this round; neither provider was starved deliberately.

## Expanded live-run result

Run `d507d7c2-ca0d-413e-b694-644748db8822` attempted all 48 distinct tracked
tenants. Forty-seven succeeded; the only failure was the retained
`lever/veeva` board with `RESPONSE_TOO_LARGE`. The run fetched 4,960 unique raw
jobs with no raw duplicates and reconciled them to 1 MATCH, 71 REVIEW, and
4,888 REJECT. It persisted 6 new jobs, updated 3, left 86 existing jobs
unchanged, retained no score on a rejected job, and did not attempt
`xebiapoland`.

The three added tenants behaved as follows:

- `ashby/uipath` produced the run's first MATCH: Software Engineer, Bucharest;
- `greenhouse/scbitdefendersrl` produced five Bucharest REVIEW candidates: QA
  Engineer, QA Engineer Mobile, Node.js Developer, iOS Developer, and ERP
  Developer;
- `greenhouse/showpad` was healthy and fetched 30 jobs but produced no MATCH or
  REVIEW in this run. Its public-board evidence remains valid, so it is retained
  as `KEEP_VALID_LOW_CURRENT_YIELD` rather than being judged from one run's
  current yield.

## Coverage shortfall

This round researched 14 employers against a ceiling of 60 and accepted 3 against a target
of 12–25. The limiting factor was evidence acquisition, not the acceptance criteria:

- most modern careers pages render the job board client-side, so the ATS identifier is not
  present in the served markup and one page fetch yields nothing;
- several strong Bucharest employers (Paysafe, FintechOS, CrowdStrike, Deutsche Bank) run
  ATS platforms outside the four supported adapters;
- search engines began rate-limiting and returning aggregator pages rather than ATS URLs
  partway through the round.

Guessing identifiers would have closed the numeric gap and is explicitly prohibited, so it
was not done. The four `HOLD_AMBIGUOUS` employers are the cheapest next wins: each needs
only a correct careers URL or one un-rate-limited fetch.

Recommended follow-up, in order:

1. re-attempt the four `HOLD_AMBIGUOUS` employers;
2. continue the researched-employer list toward the 60 ceiling, prioritising Bucharest
   product companies, Romanian fintech, and EU remote-first employers;
3. take the five `DEFER_UNSUPPORTED_ATS` employers into Phase 3.3B as provider-selection
   input — Paysafe and CrowdStrike in particular have real Bucharest entry-level volume
   that no current adapter can reach.

# Zero-score MATCH and REVIEW jobs — diagnosis (Phase 4B.3A)

Why realistic Bucharest vacancies that pass every screening gate are persisted with a score
of exactly 0, and what to do about it.

**Diagnosis and design only.** No source code, scoring weight, job row, or score row was
changed. All production queries were read-only.

- Repository at `5e137705ade83d5af37ff2fce9af1347c064cd0c`, schema V12, 126 jobs, 102 scored.
- Trigger observation: `db/DBWebsite` "Code First Girls Programme – Junior Java Developer"
  is screened MATCH on all three gates yet scores 0.

---

## 1. The scoring formula and execution order

`JobMatchingService.score(Job, ExtractedRequirements)` is the single entry point. It is called
once per persisted vacancy and its output is stored in `job_scores`.

Execution order:

| # | Step | Range | Notes |
|---|---|---|---|
| 1 | `text = (title + " " + description + " " + location).toLowerCase()` | — | one flat haystack; no field weighting |
| 2 | `formal` — formal eligibility | 0–25 | student/graduate signal 8, degree field 5 (or 4 if no education requirement), final-year 6, Romania-eligible 6 |
| 3 | `backend` — Java/backend match | 0–25 | `round(25 × matches / backendSkills.size())`, 8 skills configured → **3.125 pts per match** |
| 4 | `trainee` — trainee quality | 0–15 | internship/trainee 9, mentorship signals 6, else graduate/training text 3 |
| 5 | `supporting` — supporting tech | 0–10 | `round(10 × matches / 5)` — divisor is the literal `5`, not `size()` |
| 6 | `location` — location and format | 0–10 | see §1.1 |
| 7 | `experience` — experience compatibility | 0–10 | `null` years → 10 when trainee or candidate has 0 commercial years |
| 8 | `freshness` | 0–5 | ≤7 d 5, ≤14 d 4, ≤30 d 2, else 1; 0 when expired |
| 9 | `penalties` | subtractive | 2–3 y experience 35, final-year mandatory 30, mandatory French 25, mandatory Romanian 20, unpaid full-time without mentorship 25 |
| 10 | `blockers` | boolean | ≥3 y experience · seniority MIDDLE/SENIOR · "not eligible" remote · expired/closed |
| 11 | `raw = formal + backend + trainee + supporting + location + experience + freshness − penalties` | −100…100 | maximum attainable 100 |
| 12 | **`total = blockers.isEmpty() ? clamp(raw, 0, 100) : 0`** | 0–100 | **the whole of step 11 is discarded when any blocker fires** |
| 13 | `band` | enum | `!suitable → UNSUITABLE`; else ≥85 EXCELLENT, ≥70 GOOD, ≥55 POSSIBLE, else LOW |

`suitable = blockers.isEmpty()`.

### 1.1 `locationScore`

```
REMOTE_ROMANIA_ELIGIBLE                → 10
BUCHAREST_LOCAL + REMOTE/HYBRID/ONSITE → 9 / 8 / 7
BUCHAREST_LOCAL + UNKNOWN workplace    → 0      ← latent trap, see §7
otherwise: text heuristic              → 10 / 10 / 8 / 6 / 2
```

### 1.2 Seniority extraction (`DeterministicRequirementExtractor.seniority`)

Precedence-ordered, plain `String.contains`, **no word boundaries**, applied to
title + description + location:

```
1. SENIOR     ← "senior", "staff engineer", "lead developer", "principal"
2. MIDDLE     ← "mid-level", "middle developer", "medior"
3. INTERNSHIP ← regex
4. JUNIOR     ← "junior", "entry-level", "entry level", "graduate"
5. UNKNOWN
```

This is a **second, independent** seniority determination. `jobs.seniority_level` is produced
separately by `EarlyCareerEligibilityService` and is what the screening gates use. The two
can and do disagree.

---

## 2. Every branch that can produce exactly zero

| # | Branch | Reachable? | Observed in production |
|---|---|---|---|
| Z1 | Blocker: `requiredExperienceYears >= 3` | yes | **3 jobs** |
| Z2 | Blocker: extracted seniority is `MIDDLE` or `SENIOR` | yes | **21 jobs** |
| Z3 | Blocker: `remoteEligibility` contains "not eligible" | yes | 0 |
| Z4 | Blocker: deadline passed, or text has "position closed" / "no longer accepting applications" | yes | 0 |
| Z5 | Arithmetic: `raw <= 0` with no blocker, clamped to 0 | yes | **0** |

```
zero scores with no hard blocker (arithmetic zeros) = 0
```

**Every single zero in production is blocker-induced.** The clamp has never produced a zero.
Exactly one row has a negative pre-clamp total (job 98, raw = −1) and it also carries a
blocker, so the clamp is not load-bearing anywhere today.

### Does zero have a documented meaning?

No. Nothing in the codebase or documentation defines 0 as a sentinel. It is an ordinary
value in the same `0..100` column used for ranking, and it is what `Math.clamp` returns for
"very poor but scored". The blocker path therefore makes **"disqualified" indistinguishable
from "scored zero"** for every downstream consumer. The only distinguishing signals are
`band = UNSUITABLE` and `suitable = false`, and no queue, sort, or notification reads either.

---

## 3. Production zero-score inventory

24 of 102 scored jobs (23.5 %) — **1 MATCH, 23 REVIEW**.

| Blocker | Jobs |
|---|---|
| Middle or senior seniority | 21 |
| Mandatory 3+ years of experience | 3 |

Score distribution:

| Disposition | 0 | 1–24 | 25–39 | 40–54 | 55–69 | ≥70 | total |
|---|---|---|---|---|---|---|---|
| MATCH | **1** | 0 | 0 | 2 | 0 | 0 | 3 |
| REVIEW | **23** | 2 | 19 | 45 | 10 | 0 | 99 |

Mean MATCH score **33** vs mean REVIEW score **34** — the MATCH bucket scores *lower on
average than* the REVIEW bucket, which is an inversion of the intended ranking.

**18 of the 24 would have scored ≥ 40 without the blocker:**

| id | Title | Disp | raw would be | Blocker |
|---|---|---|---|---|
| 50 | Graduate Talent Scientist | REVIEW | **62** | seniority |
| 54 | Kernel Build Automation Engineer | REVIEW | 59 | seniority |
| 89 | Ubuntu Security Engineer | REVIEW | 57 | seniority |
| 78 | Software Engineer – Python – Container Images | REVIEW | 57 | seniority |
| **119** | **Code First Girls Programme – Junior Java Developer** | **MATCH** | **56** | seniority |
| 70, 71 | Software Engineer – Data Infrastructure | REVIEW | 53 | seniority |
| 48, 56, 60 | Golang / Linux / Python Software Engineer | REVIEW | 53 | seniority |
| 118 | Code First Girls Programme – Junior Mobile Developer | REVIEW | 51 | seniority |
| 87 | Talent Scientist | REVIEW | 48 | seniority |
| 109, 112, 113 | QA Automation Engineer (f/m/x) | REVIEW | 43 | seniority |
| 10 | Postgres Deployment Engineer (Nix) | REVIEW | 43 | 3+ years |
| 123 | Content Specialist | REVIEW | 41 | seniority |
| 8 | Postgres Engineer | REVIEW | 40 | seniority |

Job 50 would have scored **62**, tying the highest score in the entire dataset.

**All 24 blocked jobs still record populated `strengths`.** The stored explanation asserts
positive findings ("Formal eligibility is compatible with a current student in Romania",
"Matches N confirmed Java/backend technologies") while the stored score says 0. This is the
explanation/score inconsistency the audit asked for — present in 24 of 24 blocked rows.

For unblocked rows the arithmetic is faithful: **0 rows** where
`score ≠ clamp(sum of components − penalties)`.

---

## 4. Reconstruction of the four named jobs

| Component | 110 Java Developer | 122 Jr Full Stack | 126 Jr SWE Reg News | **119 Jr Java Dev** |
|---|---|---|---|---|
| formal eligibility | 25 | 25 | 25 | **25** |
| java/backend | 6 | 0 | 9 | **9** |
| trainee quality | 6 | 0 | 0 | **3** |
| supporting tech | 6 | 10 | 2 | **0** |
| location/format | 8 | 7 | 7 | **8** |
| experience | 10 | 10 | 10 | **10** |
| freshness | 1 | 5 | 1 | **1** |
| penalties | 0 | 0 | 0 | **0** |
| **raw** | **62** | **57** | **54** | **56** |
| hard blockers | — | — | — | **Middle or senior seniority** |
| **stored score** | **62** | **57** | **54** | **0** |
| extracted seniority | JUNIOR | JUNIOR | JUNIOR | **MIDDLE** |
| `jobs.seniority_level` | UNKNOWN | JUNIOR | JUNIOR | **JUNIOR** |

Job 119 is not a weak candidate that scored poorly. On components it out-scores two of the
three comparators, and at 56 it would cross the `POSSIBLE_MATCH` threshold (≥55) that none
of the other three reach. It loses 56 points to a single boolean.

---

## 5. Root cause for the Junior Java Developer

A six-step chain:

1. The stored description contains, verbatim:
   > "…the code first girls **mid-level** accelerator programme is designed for women with
   > 1.5+ years of technical experience…"
2. `seniority()` tests MIDDLE (`"mid-level"`) at precedence 2, **before** JUNIOR at
   precedence 4. The literal word "Junior" in the job title never gets a chance to win —
   body text outranks the title because both live in one flattened haystack.
3. Extraction returns `MIDDLE`, disagreeing with `jobs.seniority_level = JUNIOR`.
4. `JobMatchingService` line 83: `"MIDDLE".equals(r.seniority())` → hard blocker.
5. Line 107: `total = 0`, `band = UNSUITABLE`, `suitable = false`.
6. Screening is computed independently and says MATCH on location, career and relevance, so
   the vacancy is persisted as a **MATCH with score 0**.

Note the `1.5+ years` in that same sentence was **not** captured —
`required_experience_years` is `NULL`. Had it been extracted, 1.5 years would have produced
neither a blocker (≥3) nor a penalty (≥2); it would have set `experience = 4` instead of 10,
giving raw 50. So even on the strictest honest reading of this vacancy, the correct outcome
is roughly 50, not 0.

### Three separable defects

**D1 — context-blind substring matching.** `contains("senior")` has no word boundary and no
notion of *whose* seniority. Observed false positives:

| id | Title | Matched context |
|---|---|---|
| 50 | **Graduate** Talent Scientist | "working closely with **senior** stakeholders" |
| 109/112/113 | QA Automation Engineer | "under the guidance of **senior** colleagues" |
| 124 | Solutions Engineer | "…to both engineering teams and **senior** leadership" |
| 47 | Go Software Engineer | "building a full team including senior, **junior and entry-level** roles" |

Four of these describe *colleagues or stakeholders*, and one explicitly advertises junior and
entry-level openings. A Graduate-titled role and three roles that say "under the guidance of
senior colleagues" — the clearest possible entry-level signal — are all disqualified. The
substring also matches the word "seniority" itself (4 jobs contain it).

Correctly blocked by contrast: id 8 ("we're looking for a **senior** postgres engineer"),
id 123 ("career stage: **senior** associate").

**D2 — precedence inverts title and body.** SENIOR/MIDDLE are tested before JUNIOR, and the
title carries no extra weight, so one incidental phrase anywhere in a 4 000-character
description overrides an explicit "Junior" in the title.

**D3 — severity mismatch.** Detected seniority is a *blocker* (score → exactly 0), while an
explicit, unambiguous "2+ years of commercial experience" requirement is only a **35-point
penalty**. Inferred seniority is thus punished far harder than a stated requirement. The
blocker also silently contradicts the screening pipeline, which independently graded the
same vacancy MATCH.

---

## 6. Impact on Telegram review ordering

`JobReviewQueryRepository` orders every queue by:

```
workflow status (UNREVIEWED, SAVED, other) → score DESC → recency DESC → job id DESC
```

Nothing reads `band` or `suitable`. A blocked job is therefore not hidden — it is **sorted to
the very bottom** of its queue, indistinguishable from a genuinely weak match.

Current `/matches` queue, exactly as Telegram renders it:

| # | Job | Score |
|---|---|---|
| 1 | Junior Software Engineer, Regulatory News | 54 |
| 2 | Software Engineer | 44 |
| 3 | **Code First Girls Programme – Junior Java Developer** | **0** |

The single most on-target vacancy in the system — Junior, Java, Bucharest, MATCH on all
three gates — is shown **last**, behind a generically titled role. In `/review`, 23 of 99
jobs share score 0 and are ordered only by recency, so the four strongest of them (raw 62,
59, 57, 57) are scattered among the weakest.

Ordering for ties is deterministic (recency then id), so this is stable, not flaky.

---

## 7. Two latent issues found while tracing (not causing any current zero)

- **`locationScore` returns 0 for `BUCHAREST_LOCAL` + `UNKNOWN` workplace type.** A Bucharest
  vacancy with an unstated work format scores worse on location than a vacancy that merely
  mentions "remote" (6). Currently **0 of 35** Bucharest-local jobs have an unknown workplace
  type, so nothing is affected — but the branch is a missing-field-defaults-to-penalty trap.
- **`supporting` uses a hardcoded divisor of 5** while 8 supporting skills are configured, so
  the component saturates at 5 matches. `backend` correctly uses `backendSkills.size()`. Only
  a mild inconsistency, but the two components are meant to be parallel.

## What is *not* happening

Checked explicitly because the trigger job is a women-in-tech programme:

- **No penalty or blocker exists anywhere for** "programme", "training", "academy",
  "graduate", "women", "Code First Girls", "fixed-term", or "internship". Query confirms
  **0 jobs** containing any of those words carry a non-zero penalty.
- Several of those words are *positive*: "graduate" adds up to 8 (formal) + 3 (trainee), and
  "academy" is a mentorship signal worth 6.
- **Skill matching is sound.** Extraction uses case-insensitive, word-boundary-anchored
  regexes (`(?<![\p{L}\p{N}])Java(?![\p{L}\p{N}])`) over title + description; both sides are
  lowercased before comparison. Not title-only, not case-sensitive, not punctuation-fragile.
- **Missing optional fields default to neutral or positive**, not to a penalty:
  `requiredExperienceYears = null` → experience 10 (candidate has 0 commercial years);
  `requiredEducation = null` → formal +4. The one exception is the location trap above.
- **No score is inconsistent with its own components** for any unblocked job.
- **No malformed production data.** Descriptions on the blocked Workday jobs are 3.7–7.3 KB;
  the extraction that produced `MIDDLE` was reading real text.

---

## 8. Intended policy vs implementation defect

| Aspect | Verdict |
|---|---|
| Excluding genuinely mid/senior roles | **Intended policy.** The candidate is an early-career student. |
| Detecting seniority from free text | **Intended**, but the implementation is context-blind (**D1**). |
| Body text overriding an explicit title (**D2**) | **Defect.** Not a stated policy anywhere. |
| Blocker → exactly 0 rather than a penalty (**D3**) | **Defect in calibration.** Inconsistent with the 35-point penalty for a stated 2-year requirement, and it discards a fully computed score. |
| Reusing the ranking column to mean "disqualified" | **Defect.** `band`/`suitable` already encode it; overloading `score` breaks ordering. |
| Score 0 as a sentinel | **Undocumented.** No definition exists. |
| Job 119 being *ranked below* strong matches | **Defect** (consequence of D1–D3). |
| Job 119 being *deprioritised at all* | **Defensible.** The programme really does target 1.5+ years. Correct outcome is ≈50, not 0. |

---

## 9. Minimal safe remediation options

### Option A — make seniority a penalty instead of a blocker
Replace `blockers.add("Middle or senior seniority")` with `penalties += 40` (or similar).
- Restores real ordering for all 21 affected jobs; job 119 → ~16, job 50 → ~22.
- Keeps mid/senior roles below entry-level ones without erasing them.
- **Does not fix D1** — false positives still get penalised, just not zeroed.
- Weight change ⇒ belongs in a calibration phase, not a defect fix.

### Option B — fix detection: title precedence + context exclusion *(recommended first)*
1. Test the **title** for JUNIOR/INTERNSHIP signals before consulting body text.
2. Add word boundaries and exclude possessive/contextual phrases: `senior colleagues`,
   `senior stakeholders`, `senior leadership`, `senior management`, `seniority`,
   `senior and junior`, `including senior, junior`.
- Fixes jobs 47, 50, 109, 112, 113, 124 outright; job 119 becomes JUNIOR (title wins) → 56.
- **No weight change**, no blocker-semantics change. Smallest correct diff.
- Risk: a genuinely senior role whose title omits "Senior" could slip through to a low score —
  acceptable, since it would still rank low on components.

### Option C — separate disqualification from ranking
Stop overloading `score`. Keep the computed `raw` in `score` and let `suitable = false` drive
exclusion, with the Telegram queue filtering or sectioning on `suitable`.
- Cleanest semantically; removes the "0 means two different things" ambiguity permanently.
- Largest change: touches persistence meaning, the queue query, and the review UI ordering.

### Option D — leave scoring alone, fix only ordering
Order queues by `(suitable DESC, score DESC, …)` so blocked jobs sink explicitly rather than
by accident.
- Cosmetic; leaves D1's false positives disqualified. Not sufficient alone.

### Tests required per option

| Option | Tests |
|---|---|
| A | seniority penalty applied not blocked; explicit senior role still ranks below junior; score never negative; 3+ years remains a blocker |
| **B** | title "Junior X" + body "mid-level" → JUNIOR; each of the 6 false-positive phrases → not senior; "senior postgres engineer" → still SENIOR; "seniority" alone → not SENIOR; word-boundary cases ("seniority", "seniors"); regression on all 21 currently blocked jobs |
| C | `suitable=false` excluded/sectioned in every queue; score retains raw; Telegram ordering test; migration-free assertion |
| D | queue ordering with mixed `suitable` values; ties deterministic |

Every option additionally needs: no rescoring of historical rows (scores are recomputed on
the next ingestion of each vacancy), and confirmation that `job_scores` stays consistent with
its components.

---

## 10. Recommendation

### **FIX** — Option B, as its own commit.

D1 and D2 are unambiguous implementation defects: a Graduate-titled role and three roles
advertising "under the guidance of senior colleagues" are being disqualified because a
colleague's seniority appears in the prose. That is not policy, and it is fixable without
touching a single weight.

Then **CALIBRATE** — Option A — as a separate, clearly-labelled phase, because changing a
blocker into a penalty is a deliberate ranking-policy decision, not a bug fix, and the two
must not be mixed.

Option C is the right long-term shape and should be recorded as a follow-up, but it changes
what a stored score *means* and is too broad to bundle with a defect fix.

---

## 11. Implemented in Phase 4B.3B — Option B

Option B shipped. Weights, bands, blocker semantics, eligibility gates, relevance gates and
Telegram ordering were **not** touched.

### Extraction rule now in force

`DeterministicRequirementExtractor.seniority(title, body)`:

1. **Title first.** Classify the role title alone. An unambiguous title wins outright.
2. **Ambiguous posting.** If the body advertises several levels at once
   (`senior … junior/entry-level/graduate` within one sentence, either order), return
   `UNKNOWN` rather than guess — a false senior call costs the whole score.
3. **Body, cleaned.** Otherwise classify the body after blanking:
   - *other people's* seniority — `senior colleagues|stakeholders|leadership|management|
     leaders|managers|executives|sponsors|team members`, `report(s|ing) to a senior`,
     `guidance|mentorship|supervision|support|direction|oversight of/from/by a senior`,
     `work(ing)/collaborate/liaise/partner/engage with a senior`;
   - *programme audience* levels — a level word qualifying an
     `accelerator|programme|program|bootcamp|academy|course|track|cohort|curriculum|pathway`.
4. **No signal** anywhere → `UNKNOWN`.

All four level patterns are now **word-bounded**, so `seniority`, `seniors`, `principality`
and `amid` no longer match. Matching stays case-insensitive on both title and body. Only the
seniority field changed; every other extracted field still reads the combined text.

### Confirmed healing behaviour — a rescore phase IS required

Traced in `JobProcessor.process` and pinned by two tests:

| Path | Score row |
|---|---|
| New vacancy | computed |
| Existing, description hash changed | **rebuilt** |
| Existing, hash equal, `refreshScreening` reports a meaningful change | **rebuilt** |
| Existing, hash equal, no meaningful change | **reused as-is — not recomputed** |

`refreshScreening` compares only provider-supplied and screening-derived `jobs` columns.
Extracted seniority lives in `job_requirements` and is not among them, so a re-ingest of
identical provider content takes the last row and leaves the stale score untouched.

**The earlier assumption in §10 that re-ingestion heals these rows was wrong.** The 24
affected jobs will keep their stored 0 until their provider content changes. A separate,
explicitly-approved one-time rescore phase is therefore required; none was performed here and
no production row was edited.

Smallest safe mechanism, for that later phase — not implemented:

- A read-only preview listing the jobs whose stored score disagrees with a freshly computed
  one, so the blast radius is known before anything is written.
- A bounded, idempotent, opt-in re-score that reuses the existing `extractScoreAndSave` path
  per job inside its own transaction, touching only `job_scores` and `job_requirements`, never
  `jobs` identity, screening dispositions, or workflow state.
- Guarded by an explicit flag, default off, with the same "rejected jobs never receive a
  score" invariant asserted before and after.

### Still deliberately out of scope

Blocker-to-penalty calibration (Option A) remains a **future option**, unimplemented — it is a
ranking-policy decision, not a defect fix. The `locationScore` UNKNOWN trap and the
supporting-skills divisor from §7 were likewise left untouched.

---

## 12. Phase 4B.3C-A — read-only stale-score preview

Phase 4B.3C-A adds the blast-radius preview required by §11. It is an opt-in diagnostic,
not a rescore or backfill operation. The default remains:

```dotenv
JOBPILOT_SCORE_RESCORE_PREVIEW_ENABLED=false
JOBPILOT_SCORE_RESCORE_PREVIEW_MAX_JOBS=250
```

The default cap is above the current scored dataset. Configuration accepts `1`–`1000`;
`1000` is a non-configurable hard ceiling. The preview counts its candidates first and, if
the configured cap would be exceeded, stops before loading any job details and emits the safe
category `CAP_EXCEEDED`.

### Architecture

`JobScoreCalculator` is the pure calculation boundary shared by both paths:

```text
new/changed vacancy -> JobProcessor -> JobScoreCalculator -> requirements + ScoreCard
                                                    |
                                                    +-> existing persistence path

startup runner (flag on) -> repeatable-read preview queries
                         -> JobScoreCalculator -> immutable preview report -> bounded logs
```

The calculator owns the existing `DeterministicRequirementExtractor` and
`JobMatchingService`; it owns no repository and does not mutate `Job`. `JobProcessor` keeps
the same create/update persistence sequence and merely receives the calculator's immutable
`ScoreCalculation` instead of calling extraction and matching inline. Weights, blockers,
bands, component arithmetic, screening and Telegram ordering are unchanged.

The preview selects only rows that satisfy both conditions:

- `jobs.screening_disposition IN ('MATCH', 'REVIEW')`;
- an existing `job_scores` row joins that job.

The repository query excludes `REJECT` in SQL, and the service repeats the invariant check
before calculation. The stored `job_requirements` row supplies the old inferred seniority;
current requirements are reconstructed from the persisted job title and description only.
No provider is queried. Missing job identity/scoring fields or a missing requirements row
stops the complete preview with `MISSING_REQUIRED_DATA`; it never falls back to a provider,
ingestion, screening refresh or newly fetched content.

The immutable report contains changed rows, score/band/blocker/penalty/seniority differences,
raw component total, seniority-fix attribution, delta distribution, boundary crossings, and
the exact before/after `/matches` and `/review` ordering. Queue projections use the production
ordering rules: workflow state, score, recency, then job ID. Rows and log fields are ordered
deterministically. Titles, sources and tenant keys are control-character stripped and bounded;
long external IDs are emitted only as a short prefix plus a SHA-256 correlation suffix.
Descriptions, URLs, candidate facts, Telegram authorization values and secrets never enter
the report model.

### Proof of the read-only boundary

The startup runner checks `enabled` before calling the preview service, so disabled mode makes
no preview repository call. Enabled mode runs inside one Spring `readOnly=true`, PostgreSQL
repeatable-read transaction. Its dependency graph contains exactly three read repositories
(`job_scores`, `job_requirements`, `job_workflow_state`) and the pure calculator. It does not
depend on `JobRepository`, ingestion, source adapters, screening services, workflow mutation
services, Telegram clients or any HTTP client.

There is no invocation of `save`, `saveAndFlush`, `flush`, `delete`, update SQL, or a locking
query in the preview path. Tests assert zero repository write-method invocations, cap-before-
detail-read behavior, SQL-level REJECT exclusion, fail-closed missing data, deterministic
repeat runs and bounded sanitized output. Operational validation compares table counts,
checksums and maximum update timestamps before and after the enabled run.

### Exact guarded Docker run

First recreate only the application with the default-off setting, wait for health, and take
the database baseline. Do not edit `.env`, stop PostgreSQL, or remove volumes. Then enable the
flag only for the one Compose invocation:

```bash
JOBPILOT_SCORE_RESCORE_PREVIEW_ENABLED=true \
JOBPILOT_SCORE_RESCORE_PREVIEW_MAX_JOBS=250 \
docker compose up -d --build --force-recreate --no-deps app

docker compose logs --no-color app | rg 'SCORE_RESCORE_PREVIEW'
```

The runner executes exactly once for that application start. After capturing the bounded
report, remove the temporary shell override by recreating only the application normally:

```bash
docker compose up -d --force-recreate --no-deps app
```

Confirm `/health`, PostgreSQL health, schema V12, Telegram polling, and unchanged database
fingerprints after the restore. Compare ingestion-run and tenant-attempt counts and inspect
the bounded application logs to establish that no ingestion or source fetch began during the
preview window.

**Write-back is not implemented in Phase 4B.3C-A.** There is no production rescore-enabled
flag, no rescore command, no migration and no code path that persists the calculated preview.

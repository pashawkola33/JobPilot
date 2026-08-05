# Scoring calibration analysis — Phase 4B.5A

Read-only impact preview for three open calibration questions carried over from
`scoring-zero-diagnosis.md` §7 and §11.

**Analysis only.** No scoring code, weight, job row, score row or requirement row was changed.
Every production query was a plain `SELECT`. No rescore, no ingestion, no Telegram command.

- Repository at `b72cbfa79aa9305732c73e8352139dfc775f905d`, schema V12.
- **129 jobs, 105 scored**, dataset is post-4B.3B (seniority extractor fix) and post-4B.3C-B
  (one-time rescore write-back).
- The scheduled six-hour ingestion ran at 12:02–12:09 UTC on 2026-08-05, part-way through this
  analysis, adding jobs 127, 128 and 129. All figures below were re-derived against the
  post-ingestion dataset (129/105); an earlier draft of this document quoted the pre-ingestion
  baseline of 126/102.

### Method

Every score component is persisted in `job_scores`, and `job_requirements.technologies` is a
pipe-delimited list. Each candidate change is therefore reproducible as arithmetic over stored
columns — no preview runner, no flag, no new code was needed for this phase. The reconstruction
was validated first: recomputing `supporting_technology` as `min(10, round(10 × matches / 5))`
from stored technologies reproduces the stored value for **all 105 scored rows**, so the
recount is faithful and the deltas below are exact rather than modelled.

---

## Current baseline

| Band | Jobs | Score range |
|---|---|---|
| POSSIBLE_MATCH | 14 | 55–62 |
| LOW_MATCH | 80 | 5–54 |
| UNSUITABLE | 11 | 0 |
| GOOD_MATCH | **0** | ≥70 never reached |
| EXCELLENT_MATCH | **0** | ≥85 never reached |

Maximum score ever awarded: **62**. Mean: **40.7**.

Remaining hard blockers: 8 seniority, 3 experience (`≥3 years`). Down from 24 pre-4B.3B.

---

## Question 1 — should the seniority hard blocker become a penalty?

### Recommendation: **NO.** The case for this change was consumed by the 4B.3B extractor fix.

Option A in `scoring-zero-diagnosis.md` §9 was written when **21** jobs were seniority-blocked
and most were false positives. That population is now **8**. Reading the matched context out of
each stored description:

| id | Title | Matched text | Verdict |
|---|---|---|---|
| 8 | Postgres Engineer | "we're looking for a **senior** postgres engineer" | correct |
| 54 | Kernel Build Automation Engineer | "is seeking a **senior** software engineer to lead an initiative" | correct |
| 123 | Content Specialist | "career stage: **senior** associate" | correct |
| 87 | Talent Scientist | "a number of **mid-senior** level openings in our team" | correct |
| 5 | Software Engineer (Connect Platform) | "as a **mid level** backend engineer on the platform connect team" | correct |
| 129 | AI Engineer | "as a **senior** ai engineer at gitlab, you'll help build…" | correct |
| 86 | System Software Engineer — GCC/LLVM | "mentor less **senior** engineers" | arguable |
| 98 | Data Engineering Specialist III | "escalates … beyond individual knowledge and scope to **senior** level employees" | **false positive** |

**Six of eight are unambiguously correct.** The blocker is now doing the job it was designed
to do. The two imperfect cases cost almost nothing:

- **Job 98** is a genuine false positive, but its component sum is **−1** (it also carries the
  35-point 2-year penalty). Unblocking it produces a score of 0 anyway. **Cost: zero.**
- **Job 86** is grammatically a false positive — the phrase describes the people being mentored,
  not the role. But a role that mentors less-senior engineers is itself plausibly senior. Its
  component sum is 39. **Cost: at most one job ranking mid-pack instead of last.**

So the entire residual damage from keeping the blocker is **one job, id 86**.

### What converting to a penalty would actually buy

Component sums for the eight, with the resulting score at four candidate penalty weights:

| id | raw | P=30 | P=35 | P=40 | P=50 |
|---|---|---|---|---|---|
| 54 | 59 | 29 | 24 | 19 | 9 |
| 129 | 51 | 21 | 16 | 11 | 1 |
| 87 | 48 | 18 | 13 | 8 | 0 |
| 123 | 41 | 11 | 6 | 1 | 0 |
| 8 | 40 | 10 | 5 | 0 | 0 |
| 86 | 39 | 9 | 4 | 0 | 0 |
| 5 | 38 | 8 | 3 | 0 | 0 |
| 98 | −1 | 0 | 0 | 0 | 0 |

Only two unblocked jobs in the entire dataset score below 25 (scores 5 and 11). At every weight
tested, all eight stay at the bottom of the queue. **No blocked job overtakes any job currently
above 29**, and no band changes — all eight land in LOW_MATCH regardless of weight.

The only real gain is resolving the eleven-way tie at score 0 into an ordering. That is worth
little here: the queue already contains ties of **12 jobs at score 53** and **11 at score 50**.
Coarse ties are pervasive in this scoring system; the tie at 0 is not exceptional.

### What it would cost

`suitable` and `band = UNSUITABLE` are the only fields distinguishing "disqualified" from
"scored low". Converting the blocker to a penalty erases that distinction permanently and
forecloses Option C from §9 (filtering or sectioning the queue on `suitable`), which is the
better long-term shape. Nothing reads `suitable` today, so the loss is latent — but it is a
one-way door taken in exchange for reordering eight bottom-ranked jobs.

At P=30 the change is also actively wrong in one case: job 54, a vacancy that explicitly seeks
"a senior software engineer to lead an initiative", would score **29** and rank above two
legitimately screened-in jobs.

### If it is done anyway

Use **P=40**. It keeps every correctly-blocked job at or below 19, below all but two unblocked
jobs, and it exceeds the 35-point penalty for a stated 2-year requirement — which is the right
ordering, since an explicit "senior" in the role title is a stronger disqualifier than a
2-year floor. Fix job 86's pattern (`mentor(?:ing)? less senior`) in `OTHER_PEOPLE_SENIORITY`
first, as a separate defect fix, so the penalty is not applied to a known false positive.

---

## Question 2 — `BUCHAREST_LOCAL` with `UNKNOWN` workplace

### Recommendation: **delete the branch.** It is provably unreachable, not merely empty.

`JobMatchingService.locationScore` line 137:

```java
case UNKNOWN -> 0;
```

§7 of the earlier diagnosis recorded this as a latent trap affecting 0 of 35 Bucharest jobs.
It is stronger than that — the combination cannot occur. Two independent proofs:

**1. The only producer of `BUCHAREST_LOCAL` rejects `UNKNOWN`.**
`LocationEligibilityService.localDecision` is the sole assignment site
(`LocationEligibilityService.java:349`). It is gated by:

```java
boolean accepted = switch (workplace) {
    case ONSITE  -> settings.acceptBucharestOnsite();
    case HYBRID  -> settings.acceptBucharestHybrid();
    case REMOTE  -> settings.acceptBucharestRemote();
    case UNKNOWN -> false;          // hardcoded, not configurable
};
```

When `accepted` is false and the workplace is `UNKNOWN`, the method returns
`REMOTE_ELIGIBILITY_UNKNOWN` (line 343), never `BUCHAREST_LOCAL`. No configuration flag can
change this — the `UNKNOWN` arm is a literal `false`.

**2. `remote_type` and `location_eligibility` come from the same decision object.**
`JobNormalizer.remoteType()`:

```java
if (eligibility != null) return RemoteType.valueOf(eligibility.workplaceType().name());
```

and `Job.applyEligibility` sets `locationEligibility` from that same decision. `Job.applyUpdate`
copies both together. They cannot diverge. On the legacy path where `eligibility == null`,
`locationEligibility` defaults to `REMOTE_ELIGIBILITY_UNKNOWN` — still not `BUCHAREST_LOCAL`.

Production confirms it, and also confirms the coupling is tight:

| location_eligibility | remote_type | jobs |
|---|---|---|
| BUCHAREST_LOCAL | HYBRID | 16 |
| BUCHAREST_LOCAL | ONSITE | 19 |
| REJECTED_LOCATION | HYBRID / REMOTE | 4 / 15 |
| REMOTE_ELIGIBILITY_UNKNOWN | REMOTE | 14 |
| REMOTE_ROMANIA_ELIGIBLE | REMOTE | 58 |

`BUCHAREST_LOCAL + UNKNOWN`: **0 rows, and unreachable by construction.**

### Impact

Changing `0` to any other value has **zero effect on any score**, now or in future, unless
`localDecision`'s `UNKNOWN` arm changes first. The correct action is not to recalibrate the
value but to remove the dead arm — or, if the exhaustive `switch` requires it, to make it
`throw new IllegalStateException(...)` so the invariant is asserted rather than silently
papered over with a 0 that looks like a deliberate penalty.

This is the one change of the three that is safe to make and carries no calibration risk,
because it cannot alter a single stored or future score.

---

## Question 3 — supporting-skills divisor hardcoded as 5 while 8 skills are configured

### Recommendation: **do not change the divisor to 8.** It is a strict downgrade.

Configured in `application.yml:104-105`, both lists have exactly 8 entries:

```yaml
backend-skills:    [Java, Spring Boot, REST, SQL, PostgreSQL, JPA, Maven, JUnit]
supporting-skills: [React, TypeScript, JavaScript, HTML, CSS, Git, CI/CD, GitHub Actions]
```

```java
int backend    = Math.min(25, Math.round(25f * backendMatches / backendSkills.size()));  // /8
int supporting = Math.min(10, Math.round(10f * supportingMatches / 5f));                 // /5 literal
```

The inconsistency is real. But "making them parallel" by using `supportingSkills.size()`
moves the value per match from 2.0 down to 1.25, and **every affected job loses points. None
gains.**

| supporting matches | jobs | current | divisor 8 | delta |
|---|---|---|---|---|
| 0 | 60 | 0 | 0 | 0 |
| 1 | 25 | 2 | 1 | −1 |
| 2 | 10 | 4 | 3 | −1 |
| 3 | 6 | 6 | 4 | −2 |
| 4 | 2 | 8 | 5 | −3 |
| 6 | 2 | 10 | 8 | −2 |

Score impact across the 105 scored rows: **41 jobs drop** (35 by −1, 5 by −2, 1 by −3), 64
unchanged, **0 improve**. Three jobs demote out of POSSIBLE_MATCH:

| id | Title | 55 → |
|---|---|---|
| 68 | Software Engineer, Cloud — Sustaining Engineering | 54 |
| 82 | Software Engineer, Sustaining Engineering | 54 |
| 85 | Sustaining Operations Engineer | 54 |

The saturation the divisor-5 supposedly causes barely bites: no job in the corpus matches 5, 7
or 8 supporting skills, and only **2 jobs** reach 6 matches. The information lost to the
`min(10, …)` clamp is two jobs' worth of one extra match.

### The real defect is in the other component

While recounting, the backend side turned out to be the serious calibration problem. Component
realization across the 94 unblocked scored jobs:

| Component | Mean | Max | Weight |
|---|---|---|---|
| formal eligibility | 20.3 | 25 | 25 |
| **java/backend** | **2.2** | **9** | **25** |
| trainee quality | 1.9 | 12 | 15 |
| supporting technology | 1.4 | 10 | 10 |
| location/format | 8.4 | 10 | 10 |
| experience compatibility | 9.8 | 10 | 10 |
| freshness | 2.2 | 5 | 5 |

The largest single component averages **9% of its weight** and has never exceeded 9/25. The
reason is in the divisor it already uses correctly:

| backend skill | jobs extracted |
|---|---|
| SQL | 30 |
| Java | 25 |
| REST | 18 |
| PostgreSQL | 15 |
| Spring Boot | 3 |
| **JPA** | **0** |
| **Maven** | **0** |
| **JUnit** | **0** |

Three of the eight divisor terms **never match anything**, across all 129 jobs. Backend is
mathematically capped at `round(25 × 5/8) = 16` and in practice reaches 9. Meanwhile
`Hibernate` is extracted from 3 jobs but is absent from `backend-skills`, so it earns nothing.

This is what holds the ceiling at 62 and it is why the `GOOD_MATCH` (≥70) and
`EXCELLENT_MATCH` (≥85) bands have **never once fired**. Those bands are not cosmetic — they
gate real delivery:

- `JobSchedulingService:72` — the daily Telegram digest queries `findDigest(GOOD_MATCH, …)`.
- `JobIngestionService:259` — the instant "excellent match" alert requires `EXCELLENT_MATCH`.

**Both Telegram notification channels are dead**, and have been for the life of the dataset.
The top-scoring vacancy in the system, id 110 "Java Developer (f/m/x)", scores 6/25 on the
Java/backend component and totals 62 — eight points short of ever triggering a digest.

The supporting divisor is a cosmetic inconsistency worth 1–3 points. The backend divisor,
used "correctly", is worth about 40 points of missing headroom.

---

## Summary

| # | Question | Verdict | Live impact if changed |
|---|---|---|---|
| 1 | Seniority blocker → penalty | **No.** 6/8 blocks are correct post-4B.3B; residual cost is 1 job | 8 jobs reordered, all still bottom, no band change. Permanently loses `suitable` semantics |
| 2 | `BUCHAREST_LOCAL` + `UNKNOWN` → 0 | **Delete the branch.** Unreachable by construction, both proofs above | **Zero.** Cannot affect any score |
| 3 | Supporting divisor 5 → 8 | **No.** Strict downgrade | 41 jobs lose points, 0 gain, 3 demote out of POSSIBLE_MATCH |

### What should be done instead

Ranked by impact, none of it performed here:

1. **Backend skill list coverage.** `JPA`, `Maven` and `JUnit` never match; they consume 3/8 of
   the largest component's divisor for nothing. `Hibernate` is extracted but not credited. This
   caps every score at 62 and keeps both Telegram channels permanently silent. Highest value,
   and it is a data/config fix before it is a weight change.
2. **Band thresholds vs. reachable range.** 70 and 85 are unreachable under the current
   component realization. Either the components must be able to reach them or the thresholds
   must move; today the bands encode an intent the arithmetic cannot express.
3. **Question 2's dead branch**, as a trivial standalone cleanup.
4. Job 86's `mentor less senior` false positive in `OTHER_PEOPLE_SENIORITY` — a defect fix, not
   calibration, and the only genuine seniority-detection residue left.

Questions 1 and 3 are best closed as **investigated and declined**, with this document as the
record of why.

---
---

# Addendum — Phase 4B.5A

Read-only follow-up requested before any implementation is proposed. **No code, configuration,
migration or production row was changed.** Every figure is derived from `SELECT`-only queries
against the live database at 129 jobs / 105 scored.

This addendum is deliberately **separate from the rejected blocker-to-penalty policy change**
in Question 1. Nothing here reopens that decision: the seniority blocker stays a blocker. What
follows treats the two misclassifications as ordinary extraction defects, which they are.

---

## A1. The two remaining false seniority classifications

### Method note — how these were verified

The proposed rules were simulated against all 129 stored descriptions by reimplementing
`DeterministicRequirementExtractor.seniority()` in SQL (title-first, mixed-level check, strip,
classify) and diffing current rules against proposed rules. The simulation reproduces the
stored `job_requirements.seniority` for **101 of 129** rows.

The 28 exceptions are **not** simulation errors — see [A1.4](#a14-a-side-finding-28-stale-seniority-rows).

### A1.1 Job 86

| Field | Value |
|---|---|
| Title | System Software Engineer - GCC/LLVM compiler, tooling, and ecosystem |
| Source / provider tenant | `greenhouse` / `canonical` |
| Inferred seniority (`job_requirements`) | **SENIOR** |
| Independent gate (`jobs.seniority_level`) | UNKNOWN |
| Early-career eligibility | UNKNOWN |
| Required experience years | *null* |

**Exact triggering fragment** (the only seniority token anywhere in the body; the title carries
no level word):

> "…you will be discussing design with other team members , **mentor less senior engineers**,
> and participate in code reviews and design reviews."

**Why the match is contextually wrong.** "less senior" qualifies *the engineers being mentored*
— people explicitly positioned **below** the role holder. The existing
`OTHER_PEOPLE_SENIORITY` pattern already blanks `senior colleagues`, `mentorship of a senior`
and `developers who mentor`, but it does not cover the comparative form, so the one phrase in
the body that proves the role is *above* junior engineers is read as proof the role *is* senior.

**Current stored state**

| raw component sum | stored score | band | suitable | penalties |
|---|---|---|---|---|
| **39** | **0** | UNSUITABLE | `false` | 0 |

**Corrected counterfactual.** With seniority correctly `UNKNOWN`, no blocker fires and no other
blocker condition applies (`required_experience_years` is null, remote eligibility is not
"not eligible", the vacancy is not expired):

| new score | band | suitable |
|---|---|---|
| **39** | LOW_MATCH | `true` |

**Minimal extractor rule.** One new alternative in `OTHER_PEOPLE_SENIORITY`:

```java
| "\\bless\\s+senior\\b"
```

### A1.2 Job 98

| Field | Value |
|---|---|
| Title | Data Engineering Specialist III |
| Source / provider tenant | `smartrecruiters` / `AECOM2` |
| Inferred seniority (`job_requirements`) | **SENIOR** |
| Independent gate (`jobs.seniority_level`) | **ENTRY_LEVEL** |
| Early-career eligibility | **ELIGIBLE** |
| Required experience years | 2 |

**Exact triggering fragment** (again the only seniority token in the body):

> "…escalates unsolvable problems beyond individual knowledge and scope **to senior level
> employees** proficient in data programming languages…"

**Why the match is contextually wrong.** The phrase names the **escalation target** — the people
this role escalates *up to*. It is the same "somebody else's seniority" category the 4B.3B fix
was built for, in a form the pattern list does not yet cover (`report(s|ing) to a senior` is
covered; `escalates … to senior level employees` is not). The independent screening pipeline,
which never consults this extractor, graded the same vacancy **ENTRY_LEVEL / ELIGIBLE** — a
direct corroboration that the SENIOR call is wrong.

**Current stored state**

| raw component sum | stored score | band | suitable | penalties |
|---|---|---|---|---|
| **−1** | **0** | UNSUITABLE | `false` | 35 (2-year requirement) |

**Corrected counterfactual.** With seniority `UNKNOWN` the blocker disappears, but the 35-point
2-year penalty remains and the component sum is negative, so the clamp returns 0:

| new score | band | suitable |
|---|---|---|
| **0** (unchanged) | LOW_MATCH | `true` |

Only the *semantics* change for this job — score-neutral, but `band` and `suitable` stop
asserting a disqualification that is not real.

**Minimal extractor rule.** Extend the existing possessive alternation in
`OTHER_PEOPLE_SENIORITY` with an optional `level` qualifier and three people-collective nouns:

```java
"\\bsenior\\b(?:\\s+level)?\\s+(?:colleagues?|stakeholders?|leadership|management|leaders?|"
  + "managers?|executives?|sponsors?|employees?|staff|personnel|team\\s+members?|"
  + "developers?\\s+who\\s+mentor)"
```

The nouns are restricted to plurals/collectives that cannot denote a single advertised vacancy.
`senior level engineer` (singular, a real role title) is deliberately **not** matched.

### A1.3 Would any genuine senior vacancy regress?

**No.** Simulated across all 129 stored descriptions:

| Check | Result |
|---|---|
| Jobs whose classification changes under the proposed rules | **exactly 2** — jobs 86 and 98, both SENIOR → UNKNOWN |
| Jobs still classified SENIOR/MIDDLE afterwards | 5, 8, 25, 54, 87, 123, 129 |
| Jobs in the whole corpus containing `less senior` | **1** (job 86) |
| Jobs containing `senior level (employees\|staff\|personnel)` | **1** (job 98) |

Every correctly-blocked vacancy from Question 1 survives: job 8 ("looking for a senior postgres
engineer"), 54 ("seeking a senior software engineer"), 87 ("mid-senior level openings"),
123 ("career stage: senior associate"), 5 ("as a mid level backend engineer"), 129 ("as a
senior ai engineer at gitlab"). Job 25 (`Applied ML Engineer`, REJECT, unscored) also stays
SENIOR.

The two new fragments are rare and unambiguous — each appears in exactly one job out of 129 —
so the blast radius is precisely the two intended targets.

### A1.4 A side finding: 28 stale seniority rows

The simulation's 28 disagreements with stored values are all in one direction — stored
`JUNIOR`, correctly `UNKNOWN` — and all 28 are REVIEW jobs whose scores date from 2026-08-02/03,
i.e. **before** the 4B.3C-B rescore.

Root cause: they are pre-4B.3B values that the rescore deliberately skipped. The 4B.3C-B plan
contains only jobs whose **`ScoreCard`** changed. `JUNIOR → UNKNOWN` changes no component and no
blocker, so these rows were never in the plan and their `job_requirements.seniority` was never
refreshed. Spot-checked example: job 45 stores `JUNIOR`, but its only `graduat*` occurrence is
"…more often for **graduates** and associates…", which the word-bounded `\bgraduate\b` pattern
correctly does not match.

This is **harmless to scoring** — no score, band, blocker or queue position is affected — but it
means `job_requirements.seniority` is not a reliable field to read directly for diagnostics. It
should be treated as advisory until a rescore touches the row. Worth recording; not worth a
dedicated write phase on its own.

### A1.5 Required tests

The existing `DeterministicRequirementExtractorTest` already has a `seniorityOf(title, body)`
helper and a "genuine detections kept" block, so these drop straight in:

| # | Test | Assertion |
|---|---|---|
| 1 | `mentoringLessSeniorEngineersIsNotASeniorRole` | `seniorityOf("System Software Engineer", "…mentor less senior engineers, and participate in code reviews.")` → not `SENIOR` |
| 2 | `escalatingToSeniorEmployeesIsNotASeniorRole` | `seniorityOf("Data Engineering Specialist III", "Escalates unsolvable problems beyond individual scope to senior level employees.")` → not `SENIOR` |
| 3 | `seniorLevelSingularRoleIsStillSenior` | `seniorityOf("Engineer", "This is a senior level engineer position.")` → `SENIOR` (guards against over-stripping) |
| 4 | `keepsGenuineSeniorRoleRequirements` | **extend existing test** with job 129's phrasing: `seniorityOf("AI Engineer", "As a senior AI engineer at GitLab, you'll help build…")` → `SENIOR` |
| 5 | `moreSeniorIsNotStripped` | `seniorityOf("Engineer", "You will become a more senior engineer over time.")` → documents chosen behaviour for the other comparative form |

Test 3 is the important one: it pins the boundary between the collective nouns being stripped
and a singular role title that must still classify as senior.

### A1.6 Is a guarded rescore required?

**Yes, for both jobs — and it cannot be avoided by re-ingestion.**

Confirmed against `JobProcessor.process` and documented in `scoring-zero-diagnosis.md` §11: a
score row is rebuilt only when the description hash changes, or when `refreshScreening` reports
a meaningful change in a provider-supplied or screening-derived `jobs` column. Extracted
seniority lives in `job_requirements` and is not among the compared columns, so re-ingesting
identical provider content leaves the stale score untouched.

| Job | Needs write-back | What changes |
|---|---|---|
| 86 | yes | `score` 0 → 39, `band` UNSUITABLE → LOW_MATCH, `suitable` false → true, `hard_blockers` cleared |
| 98 | yes | `score` unchanged at 0; `band` UNSUITABLE → LOW_MATCH, `suitable` false → true, `hard_blockers` cleared |

Both are REVIEW jobs with existing score rows, so they fall inside the existing 4B.3C-B rescore
population (`screening_disposition IN ('MATCH','REVIEW')` with a joined `job_scores` row) and
both produce a changed `ScoreCard`, so both would appear in the plan. **The existing guarded
mechanism is sufficient — no new write machinery is needed**, only a fresh `PREVIEW`, an
approved fingerprint, and one `WRITE` invocation with `EXPECTED_CHANGED_COUNT=2`.

Note the 28 stale rows of [A1.4](#a14-a-side-finding-28-stale-seniority-rows) would **not** be
repaired by that run, for the same reason they were skipped the first time.

---

## A2. Backend skill vocabulary coverage

Two distinct matching stages must be kept apart:

1. **Extraction** — `DeterministicRequirementExtractor` scans title + description with a fixed
   31-term vocabulary and word-boundary regexes, storing hits in `job_requirements.technologies`.
2. **Crediting** — `JobMatchingService` compares those extracted terms to
   `jobpilot.candidate.backend-skills` by exact lowercase equality.

A configured skill earns points only if it clears **both**. The measurements below replicate the
extractor's boundary semantics (`(?<![\p{L}\p{N}])term(?![\p{L}\p{N}])`) over raw text.

### A2.1 The eight configured backend skills

| Skill | Scored jobs (of 105) | All jobs (of 129) | Extracted | Diagnosis |
|---|---|---|---|---|
| SQL | 25 | 30 | 30 | healthy |
| Java | 21 | 25 | 25 | healthy count, **weak quality** — see below |
| REST | 16 | 18 | 18 | healthy, **3 English-word false positives** |
| PostgreSQL | 15 | 15 | 15 | healthy |
| Spring Boot | 3 | 3 | 3 | **too specific** — misses one-word "springboot" |
| JPA | **0** | **0** | 0 | **absent from postings** |
| Maven | **0** | **0** | 0 | **absent from postings** |
| JUnit | **0** | **0** | 0 | **absent from postings** |

**Extraction is faithful.** For every configured skill, extracted-job count equals raw-text-job
count exactly. JPA, Maven and JUnit are not being missed by a bad regex — the strings do not
occur in any of the 129 postings. This is a population fact, not a code defect.

**Java's count overstates its value.** Sampling every occurrence shows 17 of the 25 Java
mentions sit inside polyglot "any of these" lists:

> "…experience with one or more of c, c++, python, go, rust, **java**, ruby, php, or
> javascript/typescript…"

Only **6 jobs** mention Java *and* Spring together. The corpus contains roughly six genuine
Java/Spring backend vacancies.

**REST is partly a false friend.** The word-bounded `REST` regex also matches the English word:

> job 39 — "…optimized, and ready for integration with the **rest** of the stack."

3 of the 18 matching jobs contain `rest` **only** in non-technical usage and are credited a
backend match they do not deserve.

### A2.2 Measured alternatives, classified

Counts are jobs containing the term (scored / all 129). **Presence is reported; nothing is
proposed as a skill here.**

**Category 1 — actual candidate skills** (verified present in `candidate-profile.yml`)

| Term | Scored | All | Note |
|---|---|---|---|
| Hibernate | 3 | 3 | verified skill (`hibernate`, BACKEND); already in the extraction vocabulary, extracted, but **not** in `backend-skills`, so it earns nothing |
| unit testing / tests | 6 | 6 | candidate has JUnit 5, Spring Boot Test, MockMvc, Mockito, integration testing; no matching vocabulary term |
| relational database(s) | 4 | 5 | candidate has "relational data modelling"; concept phrase, not a vocabulary term |
| Git | 13 | 15 | verified skill — **already credited** via `supporting-skills` |
| Mockito | 0 | 0 | verified skill, absent from postings |
| JDBC | 0 | 0 | verified skill, absent from postings |

**Category 2 — safe aliases for an existing configured skill**

| Alias | Scored | Maps to | Note |
|---|---|---|---|
| `springboot` (one word) | 3 | Spring Boot | jobs 109/112/113: "frameworks like spring and **springboot**"; the two-word regex misses it |
| `Spring` (bare) | 6 | Spring Boot | 3 of the 6 are already caught by "Spring Boot"; the other 3 are the same 109/112/113 |
| REST API / RESTful | 2 | REST | already caught — the bare `REST` regex matches inside "rest api" |
| Spring Framework | 0 | Spring Boot | never occurs |

**Category 3 — adjacent skills the candidate does not necessarily possess**

| Term | Scored | All | Note |
|---|---|---|---|
| testing (any) | 46 | 49 | far too broad to imply a skill |
| Kubernetes | 44 | 48 | **not** in the verified profile |
| backend / back-end | 25 | 36 | a role descriptor, not a technology |
| Docker | 27 | 28 | **not** in the verified profile |
| microservice(s) | 6 | 6 | architectural style, not a verified skill |
| ORM | 0 | 0 | concept; candidate has Hibernate/Spring Data JPA but the term never occurs |
| Gradle | 0 | 0 | not a verified skill, and absent anyway |

The only two changes that are both *safe* and *non-zero* are therefore: crediting **Hibernate**
(a verified skill already extracted, 3 jobs) and aliasing **`springboot`** to Spring Boot
(3 jobs — the same three, as it happens).

---

## A3. Theoretical vs observed maximum

### A3.1 Per-component reconstruction (94 unblocked scored jobs)

| Component | Max possible | Observed max | Mean | Jobs at max | Headroom lost |
|---|---|---|---|---|---|
| formal eligibility | 25 | 25 | 20.3 | 41 | 0 |
| **java/backend** | **25** | **9** | **2.2** | **0** | **16** |
| **trainee quality** | **15** | **12** | **1.9** | **0** | **3** |
| supporting technology | 10 | 10 | 1.4 | 2 | 0 |
| location/format | 10 | 10 | 8.4 | 52 | 0 |
| experience compatibility | 10 | 10 | 9.8 | 91 | 0 |
| freshness | 5 | 5 | 2.2 | 13 | 0 |
| **total** | **100** | **81** (never simultaneous) | — | — | **19** |

Five of seven components are reached at maximum by at least one real vacancy. Two never are:
**java/backend** (best 9 of 25) and **trainee quality** (best 12 of 15). Those two carry 40 of
the 100 available points and deliver a combined mean of **4.1**.

Observed maximum total: **62**. The gap from 81 to 62 is because the components are
anti-correlated in this population — the Java-heavy postings are corporate ATS listings that are
stale by the time they are scored (low freshness) and carry no trainee signals, while the
trainee-signalling postings are not Java roles.

### A3.2 Are GOOD_MATCH and EXCELLENT_MATCH genuinely unreachable?

| Scenario | Max score | Jobs ≥70 |
|---|---|---|
| Current configuration, as scored | **62** | 0 |
| Current configuration, freshness reset to 5 (all postings hypothetically fresh) | **66** | 0 |
| Vocabulary fix B2 (dead terms dropped), as scored | **66** | 0 |
| Vocabulary fix B2 **+** freshness reset to 5 | **70** | **1** (job 110, *Java Developer (f/m/x)*) |

**GOOD_MATCH (≥70) is unreachable under the current configuration** — not merely unobserved. The
most favourable counterfactual available from real data reaches exactly 70, and only by
combining the vocabulary fix with a hypothetically fresh posting.

**EXCELLENT_MATCH (≥85) is unreachable by a wide margin.** It would require backend at or near
25 (best ever: 9, best modelled: 15) simultaneously with high trainee quality (best ever: 12,
mean 1.9). No vacancy in 129 comes close, and no vocabulary change moves it.

### A3.3 Cause attribution

| Cause | Contribution | Evidence |
|---|---|---|
| **Job population** | **dominant** | 62 greenhouse + 34 ashby jobs, heavily Canonical/Ubuntu infrastructure (Go, Python, Kubernetes: 48 jobs). Only 6 postings mention Java *and* Spring; 17 of 25 Java mentions are polyglot lists. The corpus barely contains the roles the scorer is tuned for. |
| **Weights** | major | 40 of 100 points sit on two components this population cannot fill. Even a perfect Java vacancy could not exceed ~87. |
| **Vocabulary / config** | moderate | 3 of 8 backend divisor terms never match, compressing every backend score by ~38%. Worth +4 to +9 points on Java jobs. |
| **Candidate profile** | moderate | `application.yml:104` `backend-skills` is a hand-written list that disagrees with the verified `candidate-profile.yml`: it lists JPA/Maven/JUnit (never in postings) and omits Hibernate/JDBC/Spring Data JPA (verified, and Hibernate does occur). |

The thresholds are not "slightly high" — they encode an intent the arithmetic cannot express
against this job supply.

---

## A4. Alternatives evaluated

A hard constraint applies to **every** option below and is easy to miss:

```java
// JobScoreRepository.findDigest
where s.band = :band and s.scoredAt >= :since
  and s.job.screeningDisposition = ScreeningDisposition.MATCH
```

Both Telegram channels filter on **`screening_disposition = MATCH`**, and only **3 of 129 jobs
(2.3 %)** are MATCH — currently scoring 56, 54 and 44. Daily MATCH arrivals over the observed
window: Aug 2 → 0, Aug 3 → 1, Aug 4 → 2, Aug 5 → 0. **Digest volume is capped by MATCH supply
(≈0.75/day), not by the band thresholds.**

| | Changed jobs | New max | Cross 70 | Cross 85 | Digest volume | Instant alerts | False-positive risk |
|---|---|---|---|---|---|---|---|
| **A** aliases only | 3 | 62 | 0 | 0 | 0 | 0 | **very low** — `springboot` is unambiguous |
| **B1** verified profile, 6 skills (drop JPA/Maven/JUnit, add Hibernate) | 54 | 64 | 0 | 0 | 0 | 0 | **low–moderate** |
| **B2** live terms only, 5 skills | 54 | 66 | 0 | 0 | 0 | 0 | **moderate** — 5 pts per match; the 3 English-word `rest` jobs gain 5 undeserved points |
| **C** lower thresholds to 55/70 | 0 (bands only) | 62 | 14 in GOOD band | 0 | **≤1 job/day** | 0 | **low** |
| **C′** lower thresholds to 50/62 | 0 (bands only) | 62 | 41 in GOOD band | 2 | ≤2 jobs/day | 0 | **moderate** — 41 of 94 unblocked jobs become "good" |
| **D** reweight (modelled as uniform ×100/62) | all 105 | 100 | 51 | 28 | ≤3 jobs/day | up to 1 | **high** — promotes jobs scoring as low as 44 today into GOOD_MATCH |
| **E** leave dormant | 0 | 62 | 0 | 0 | 0 | 0 | **none** |

**B detail — biggest movers under B2** (divisor 8 → 5):

| id | Title | backend before → after | score before → after |
|---|---|---|---|
| 109/112/113 | QA Automation Engineer (f/m/x) | 6 → 15 | 43 → 52 |
| 119 | Code First Girls Programme – Junior Java Developer | 9 → 15 | 56 → **62** |
| 126 | Junior Software Engineer, Regulatory News | 9 → 15 | 54 → 60 |
| 110 | Java Developer (f/m/x) | 6 → 10 | 62 → **66** |

B is the only option that improves *ranking quality* — it lifts the genuinely Java/Spring
vacancies (119, 126, 110, and the LSEG QA trio) relative to the Go/Python infrastructure jobs.
That is a real gain even though it opens no notification channel.

**D is near-equivalent to C with more risk.** Any monotone reweighting that lifts the top of the
distribution also lifts the middle, because top and middle jobs differ mainly in components that
are already saturated (formal, experience, location). Rescaling to make 62 → 100 promotes 51 of
94 unblocked jobs to GOOD_MATCH or better, including jobs scoring 44 today. It buys nothing that
C does not, and it destroys the comparability of every stored historical score.

---

## A5. Recommended next phase

**Phase 4B.5B — extractor false-positive fix plus guarded two-row rescore.** Nothing else.

1. Add the two `OTHER_PEOPLE_SENIORITY` alternatives from [A1.1](#a11-job-86) and
   [A1.2](#a12-job-98). No weight, band, blocker-semantics or threshold change.
2. Add the five tests from [A1.5](#a15-required-tests).
3. Run the existing 4B.3C-B `PREVIEW` command, confirm **exactly 2 changed rows** (86 and 98)
   and the expected before/after values, then a single `WRITE` with
   `EXPECTED_CHANGED_COUNT=2` and a fresh fingerprint.

Rationale: it is a genuine defect fix, its blast radius is provably two rows, it needs no new
write machinery, and it is the only item here that is unambiguously correct rather than a
judgement call about ranking policy.

**Deliberately deferred, in priority order:**

- **4B.5C — backend skill list alignment (option B).** The highest-value calibration change, but
  it is a policy decision about what the candidate claims and how much a single skill match is
  worth. It should be taken knowingly, with the `REST` English-word false positive fixed in the
  same phase (require a technical context or drop the bare term), because B2 raises the cost of
  that false positive from 3 points to 5.
- **Notification thresholds (option C).** Only worth revisiting *after* B lands, since B changes
  the distribution the thresholds must sit against. Note that digest volume is bounded by MATCH
  supply regardless — if the goal is more notifications, the screening funnel, not the score
  band, is the thing to examine.
- **Option D (reweighting) — do not pursue.** Strictly dominated by C.
- **The 28 stale `job_requirements.seniority` rows** — harmless to scoring; record and revisit
  only if a diagnostic ever needs that column to be authoritative.

The `BUCHAREST_LOCAL` + `UNKNOWN` dead branch from Question 2 can ride along with 4B.5B as a
zero-risk cleanup, or stand alone; it cannot change any score either way.

---
---

# Phase 4B.5B-A — other-person seniority contexts, fix and read-only preview

Implements the defect fix recommended in [A5](#a5-recommended-next-phase) and produces the
guarded read-only production preview. **No write was executed.** Scoring weights, bands,
blocker semantics, eligibility, screening and Telegram ordering are untouched.

## B1. Rule changes

One pattern changed: `OTHER_PEOPLE_SENIORITY` in `DeterministicRequirementExtractor`. Two
alternatives were appended; nothing existing was modified or relaxed. Title-first precedence,
the mixed-level check and `PROGRAMME_AUDIENCE_LEVEL` are unchanged.

```java
// Comparative form. "less senior" is relative by construction: it can only
// describe somebody positioned below the advertised role, never the role.
// Covers the whole "mentor/mentors/mentoring/coach less senior X" family.
+ "|\\bless\\s+senior\\b"
// Escalation recipients sit above the role, so naming them says nothing
// about the vacancy. Anchored on an escalation verb and kept inside one
// sentence, so an unrelated later clause cannot suppress a genuine level.
+ "|\\bescalat\\w*\\b[^.!?]{0,80}?\\bto\\s+(?:an?\\s+|the\\s+|our\\s+)?\\bsenior\\b"
```

**Why these shapes, and not the obvious alternatives.**

*Case 1* keys on the comparative `less senior`, not on `mentor` or `senior engineers`. The
comparative is relative by construction — it can only describe somebody below the advertised
role — so one bounded token covers the whole grammatical family (`mentor` / `mentors` /
`mentoring` / `coaching` … `less senior` … `engineers` / `developers` / `colleagues` /
`team members`) without excluding `mentor` or `senior engineers` generally.

*Case 2* keys on an **escalation verb plus a bounded same-sentence window**, deliberately not on
the noun phrase `senior level employees`. A noun-list rule would have been shorter but would
have created exactly the broad exclusion the phase forbids — and it would have destroyed a
genuine detection: job 87 advertises "a number of **mid-senior level** openings", which a
`senior level` exclusion would have suppressed. `[^.!?]{0,80}` cannot cross a sentence boundary,
so an escalation clause elsewhere in a long description cannot suppress a real role level.

`more senior` was considered and **excluded**: it occurs zero times in the corpus, and adding it
would widen the rule past the two confirmed defects for no measured benefit.

## B2. Tests

`DeterministicRequirementExtractorTest` grows from 15 to **22** tests. New cases:

| Test | Covers |
|---|---|
| `mentoringLessSeniorEngineersIsNotASeniorRole` | required case 1 |
| `boundedVariantsOfTheLessSeniorPhraseAreAlsoExcluded` | `mentoring` / `mentors` / `coaching` variants |
| `escalatingToSeniorLevelEmployeesIsNotASeniorRole` | required case 2 |
| `boundedVariantsOfTheEscalationPhraseAreAlsoExcluded` | `escalate` / `escalated` / `escalation` variants |
| `genuineSeniorWordingSurvivesTheNewExclusions` | "as a senior AI engineer", "looking for a senior engineer", "career stage: senior associate", "as a mid level backend engineer" |
| `newExclusionsStayBounded` | genuine level word in a later sentence still wins; `mid-senior level openings` survives; plain "senior engineers" still a signal |
| `productionRegressionFixturesForJobs86And98` | paraphrased single triggering clause of each vacancy — full production descriptions are **not** copied |

Totals: **923 unit tests** (0 failures, 1 skipped — the live-network smoke test) and
**55 integration tests** (0 failures). `./mvnw verify` BUILD SUCCESS.

## B3. Production corpus regression result

The regression check is the preview itself: it runs the real extractor over every scored
production job and reports each classification difference.

**Inspected 105 scored jobs. Seniority classification changed on exactly 2: jobs 86 and 98** —
both `SENIOR → UNKNOWN`, both flagged `seniorityExtractorFix=true`. No other job's seniority
moved, and **no genuine MIDDLE/SENIOR vacancy regressed**: jobs 5, 8, 54, 87, 123 and 129 all
keep their classification, including job 87's `mid-senior level openings` and jobs 70/71's
"openings ranging anywhere from junior to senior level".

Corpus frequency of the two new triggers, measured before implementation: `less senior`
occurs in **1** job of 129, and the bounded escalation pattern in **1** job of 129 — the two
intended targets exactly.

### The plan contains 4 rows, not 2

Two additional jobs appear in the plan for a reason unrelated to this fix:

| jobId | Title | stored → computed | Cause | `seniorityExtractorFix` |
|---|---|---|---|---|
| 16 | Performance Engineer - Performance Analysis & Tuning | 41 → 40 | **freshness decay** | `false` |
| 17 | Performance Engineer - Benchmarking | 41 → 40 | **freshness decay** | `false` |
| 86 | System Software Engineer - GCC/LLVM… | 0 → 39 | seniority fix | `true` |
| 98 | Data Engineering Specialist III | 0 → 0 | seniority fix | `true` |

Jobs 16 and 17 were published 2026-07-28 and scored 2026-08-02 at age 5 days
(`freshness = 5`, the `≤7 days` bucket). They are now 8 days old, so recomputation puts them in
the `≤14 days` bucket (`freshness = 4`), costing exactly one point each. Their seniority is
`UNKNOWN` before and after and their extraction inputs are unchanged.

This is ordinary time-driven drift, not a side effect of the rule change, and it has a standing
operational consequence: **`freshness` is time-dependent, so the plan and its fingerprint have a
short shelf life.** Any future preview will pick up whichever jobs have since crossed a 7/14/30-day
boundary. A WRITE must therefore use a fingerprint from a preview taken immediately beforehand,
and its expected-changed-count must match that fresh plan — not the count recorded here.

## B4. Rescore preview report

Command (read-only; scheduling and Telegram disabled **only** for this one-shot process):

```bash
docker compose run --rm --no-deps \
  -e SPRING_MAIN_WEB_APPLICATION_TYPE=none \
  -e JOBPILOT_SCHEDULING_ENABLED=false \
  -e TELEGRAM_COMMANDS_ENABLED=false \
  -e TELEGRAM_BOT_ENABLED=false \
  -e JOBPILOT_SCORE_RESCORE_COMMAND_MODE=PREVIEW \
  -e JOBPILOT_SCORE_RESCORE_MAX_JOBS=250 \
  app
```

| Metric | Value |
|---|---|
| status | SUCCESS (exit 0, duration 966 ms) |
| inspected score rows | **105** |
| unchanged (exact) | **101** |
| changed score rows | **4** (3 with a score delta, 1 blocker-only) |
| changed requirement rows | **2** (jobs 86 and 98, `seniority` only) |
| changed bands | 2 |
| blockers removed / added | 2 / 0 |
| increases / decreases | 1 / 2 |
| zero→positive / positive→zero | 1 / 0 |
| delta distribution | `{-1=2, 0=102, 39=1}` |
| boundary crossings | `unsuitable=[86, 98]`, `possibleMatch55=[]`, `strongMatch70=[]` |
| MATCH-below-REVIEW anomalies | none |
| **plan fingerprint** | `1a55925dfa3dd4d8d51cc23666c0dfb1a7ac695034bf71a61a6c5c89aca61898` |

### Affected jobs, before → after

| | job 86 | job 98 |
|---|---|---|
| Source / tenant | greenhouse / canonical | smartrecruiters / AECOM2 |
| Queue / workflow | REVIEW / UNREVIEWED | REVIEW / UNREVIEWED |
| seniority | `SENIOR` → **`UNKNOWN`** | `SENIOR` → **`UNKNOWN`** |
| blockers | `[Middle or senior seniority]` → **`[]`** | `[Middle or senior seniority]` → **`[]`** |
| score | 0 → **39** | 0 → **0** |
| band | `UNSUITABLE` → **`LOW_MATCH`** | `UNSUITABLE` → **`LOW_MATCH`** |
| suitable | `false` → **`true`** | `false` → **`true`** |
| penalties | 0 → 0 | 35 → 35 (`Mandatory 2+ years`) |
| raw component total | 39 | −1 (clamped to 0) |
| queue position | 97 → **70** | 101 → 101 |

Both match the counterfactuals predicted in [A1](#a1-the-two-remaining-false-seniority-classifications)
from the earlier snapshot, computed independently. Job 98's score stays 0 because its component
sum is negative; only its blocker/band/`suitable` semantics are corrected.

| | job 16 | job 17 |
|---|---|---|
| seniority | `UNKNOWN` → `UNKNOWN` | `UNKNOWN` → `UNKNOWN` |
| blockers | `[]` → `[]` | `[]` → `[]` |
| score / band | 41 → 40, `LOW_MATCH` (unchanged) | 41 → 40, `LOW_MATCH` (unchanged) |
| suitable | `true` → `true` | `true` → `true` |
| queue position | 64 → 66 | 63 → 65 |

### Zero-write proof

Deterministic `md5` fingerprints over an ordered projection of every row, captured immediately
before and immediately after the preview run:

| Table | Rows | Fingerprint (identical before and after) |
|---|---|---|
| jobs | 129 | `6a4b9af0d4995c1325012fdae91d3cab` |
| job_scores | 105 | `9077433ea5ab32b388c6f06e6ae9d7d9` |
| job_requirements | 129 | `274e95937aa307899bf0b8aef869cd47` |
| source_fetch_logs | 165 | `9c92593558c48a04f927b90b6c0e2760` |
| source_tenant_fetch_logs | 894 | `93898752c3861c5cc619d4192f10663b` |
| source_tenant_health | 64 | `924d40c2fa86bbe50bb511c20a11917d` |
| telegram_bot_state | 1 | `effdf3e6033ba0b76820f73cfac81429` |
| telegram_job_delivery | 31 | `47f8bf531294a2e32ba04c6c6e6a6539` |
| flyway_schema_history | 12 | `e47f19a1e14133951030b71322557188` |
| job_workflow_state / applications / application_status_history / job_analyses | 0 | empty before and after |

`diff` over all 13 tables: **no difference**. Additionally `source_fetch_logs RUNNING = 0`
before and after, fetch-log and tenant-log row counts unchanged (165 / 894), so no ingestion
began during the preview window. No ingestion was triggered manually.

### Can the existing guarded mechanism apply this plan?

**Yes — no new write code is required.** All four targets are `REVIEW` jobs with existing
`job_scores` rows, which is exactly the population the Phase 4B.3C-B planner selects, and the
writer already updates `job_scores` in place and `job_requirements` only where the extracted
value differs, under pessimistic locks with in-transaction revalidation. No migration, no schema
change, no new flag.

Two operational constraints for that later, separately-approved run:

1. `JOBPILOT_SCORE_RESCORE_EXPECTED_CHANGED_COUNT` must equal the **fresh plan's** count —
   the plan carries 4 entries, not the 2 jobs this fix targets, and freshness decay may change
   that number again before the write.
2. The fingerprint must come from a preview taken immediately before the write, for the same
   reason.

## B5. Stale `job_requirements.seniority` — unchanged by design

Approximately 28 REVIEW jobs still carry pre-4B.3B `seniority` values (stored `JUNIOR` where
current extraction yields `UNKNOWN`). **This phase does not touch them**, and they are absent
from the plan above for the documented reason: the guarded planner includes a job only when its
**`ScoreCard`** differs, and a `JUNIOR → UNKNOWN` change alters no component, no penalty and no
blocker.

Recorded consequence: **`job_requirements.seniority` is not automatically refreshed when the
corrected extraction produces no score change.** The column is advisory for diagnostics and
should not be read as authoritative without a rescore having touched the row. No cleanup of
these unchanged diagnostic fields is in scope here.

## B6. Post-deployment state

| Check | Result |
|---|---|
| `./mvnw test` | 923 tests, 0 failures, 1 skipped |
| `./mvnw verify` | 55 integration tests, 0 failures, BUILD SUCCESS |
| `docker compose config --quiet` | clean |
| `git diff --check` | clean |
| app | healthy, `/health` 200, `status: UP`, telegram `ENABLED` |
| PostgreSQL | healthy, container and volume never stopped or recreated |
| schema | V12 (`job review workflow`) |
| `source_fetch_logs` RUNNING | 0 |
| scheduling | enabled, `JOB_FETCH_CRON=0 0 */6 * * *` |
| cleanup mode / write | `OFF` / `false` |
| score rescore mode / write | `OFF` / `false` |

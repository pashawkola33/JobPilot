# JobPilot 2.0 — Master Product Requirements Document & Engineering Roadmap

**Status:** Reviewed product roadmap; Phase 12 approved as future product direction on 20 October 2026; implementation remains phase-gated  
**Product:** JobPilot — AI-Powered Personal Job Search Assistant  
**Primary interface:** Telegram Bot + Telegram Mini App  
**Backend:** Java 21, Spring Boot, PostgreSQL, Flyway  
**Existing frontend:** React / TypeScript  
**Infrastructure:** Existing Docker/Azure deployment, subject to separate release authorization  
**Target audience:** Job seekers across all industries, experience levels and countries  
**Launch model:** Free, invitation-only beta with bounded AI usage  
**Implementation owner:** Codex  
**Current baseline:** Locally verified Phases 1–4; Phase 5 preflight/checkpoint and proposed design exist; Phase 5 implementation and verification remain incomplete

---

# PART I — PRODUCT STRATEGY

## 1. Product vision

JobPilot should become an intelligent employment assistant that substantially reduces the work required to discover relevant vacancies and prepare high-quality, truthful job applications.

The intended experience is:

1. Open a Telegram bot.
2. Upload an existing resume, or create a professional profile manually.
3. Answer a few short, relevant questions.
4. Confirm the extracted information.
5. Receive personalized job recommendations automatically.
6. Understand why each vacancy is a good or uncertain match.
7. Receive practical advice on improving the resume for that vacancy.
8. Confirm any relevant experience missing from the resume.
9. Generate a tailored, ATS-friendly DOCX/PDF resume.
10. Review the final document and apply through the employer's official channel.
11. Track the application through the Mini App.
12. In future Phase 12, optionally authorize preparation and submission for one selected vacancy through a supported employer channel.

The user should not need to understand scraping, ranking algorithms, AI models, eligibility scoring or other implementation details.

**Core product principle: powerful personalization with minimal configuration.**

The platform should adapt to users rather than requiring users to configure the platform.

## 2. Target audience

Support candidates from all professional categories, including:

- Technology and engineering.
- Marketing, advertising and communications.
- Finance, accounting and auditing.
- Logistics and supply chain.
- Sales and business development.
- HR and recruiting.
- Administration and customer support.
- Education and research.
- Design and creative professions.
- Manufacturing and operations.
- Healthcare and regulated occupations, with additional qualification safeguards.
- Other supported professional categories.

Support internships, graduate positions, junior, mid-level, senior and management roles.

Do not impose the original owner's junior/software/Bucharest restrictions on other candidates.

**Coverage limitation:** worldwide search preferences do not imply that every country, industry or job board is already covered. Display source and geographic coverage limitations transparently.

## 3. Product success criteria

The primary measure of success is not the number of vacancies collected.

It is the number of genuinely relevant, actionable opportunities delivered to each candidate.

Recommended metrics:

| Metric | Meaning |
|---|---|
| Onboarding completion | Users who reach a usable search profile |
| Time to first recommendation | Time from completing onboarding to seeing a relevant job |
| Relevant recommendation rate | Recommendations rated relevant by users |
| False-blocker rate | Genuinely suitable jobs incorrectly excluded |
| Bad-recommendation rate | Jobs with confirmed mandatory incompatibility delivered as suitable |
| Notification engagement | Digest recipients opening job details |
| Tailoring acceptance | Generated CV drafts approved after human review |
| Unsupported-claim rate | Generated statements not supported by source or explicit user confirmation |
| Application conversion | Users marking a recommended job as applied |
| AI cost per active candidate | Actual provider spending per candidate |
| Privacy incidents | Cross-user exposure or unauthorized processing |
| Operational reliability | Successful, nonduplicated digest and workflow processing |

All targets must be established against measured beta baselines. Do not fabricate performance achievements or claim production readiness from synthetic benchmarks.

---

# PART II — SIMPLIFIED USER EXPERIENCE

## 4. UX philosophy

Design for a nontechnical user who wants results and does not want to manage settings.

The interface must be:

- Mobile-first.
- Simple to navigate.
- Consistent between Telegram messages and the Mini App.
- Understandable without documentation.
- Resistant to accidental changes.
- Accessible with readable typography and clear labels.
- Recoverable after interruption, expired sessions or network failure.
- Transparent about information that is unknown.

Do not show internal terms such as `extractor_version`, `fencing_token`, `UNKNOWN eligibility` or database revision identifiers to ordinary users.

Translate these concepts into understandable language:

- "Good match"
- "Some requirements need confirmation"
- "Not suitable because..."
- "We're still checking vacancies"
- "This information isn't in your resume"
- "Your updated resume is ready for review"

## 5. Minimal user-controlled settings

Expose only settings with immediate, understandable consequences.

### Essential controls

**Job search**

- Desired professions or roles.
- One or more countries.
- Optional preferred cities within each country.
- On-site, hybrid and/or remote preference.
- Willingness to relocate, if relevant.
- Salary expectations, optional.
- Employment arrangement, when relevant.

**Personal information**

- Residence country and city.
- Work authorization for relevant jurisdictions.
- Professional experience and skills.
- Uploaded resumes.
- Languages and proficiency.
- Ability to correct AI-extracted information.

**Notifications**

- Enabled or paused.
- Twice-daily digest, default.
- Optional once-daily digest.
- Timezone, shown only if automatic detection needs correction.
- Clear opt-out.

**Privacy**

- View stored resumes.
- Delete a resume.
- Export personal data.
- Delete account.
- Understand and manage external AI processing consent.

Do not expose as ordinary settings:

- Numeric matching weights.
- Compatibility thresholds.
- Model selection.
- AI temperature or prompt parameters.
- Scraper selection.
- Source polling frequency.
- Worker batch sizes.
- Deduplication rules.
- Retry policies.
- Internal extraction confidence.
- Database retention mechanics.
- Internal API usage quotas.

These belong to platform configuration.

### Current Phase 5 scope takes precedence

The approved Phase 5 implementation request includes immediate alerts, an optional daily digest, a candidate-configurable minimum compatibility score, timezone and optional quiet hours. Preserve those controls with sensible defaults; keep any numeric threshold under optional advanced notification controls rather than requiring it during setup. Notifications remain disabled until explicit opt-in and verified private bot activation. Do not add matching-weight or model controls.

The twice-daily schedule and simplified settings described above are future UX direction, not authorization to replace or expand the current Phase 5 delivery contract. A schedule/default cutover requires a separate approved change. The original owner's existing alerts and digest remain unchanged.

## 6. First-time onboarding

Target a short primary onboarding journey.

### Step 1 — Welcome

Explain in simple language what JobPilot does.

Present two choices:

- Upload my CV — recommended.
- Continue without a CV.

Do not require a CV to use the product.

### Step 2A — Resume upload

Accept PDF and DOCX.

Show upload progress, successful receipt, processing status and an understandable failure message.

Reject unsupported, oversized, encrypted or unreadable files safely.

Do not expose temporary Telegram file URLs to other users.

### Step 2B — Manual profile

If no CV is uploaded, ask for:

- Desired role or professional field.
- Experience level.
- Main skills or experience.
- Education, if relevant.
- Languages.
- Location and work preferences.

Provide examples rather than requiring formal resume terminology.

### Step 3 — Extract professional information

When a CV is provided, extract a structured candidate profile:

- Candidate name, if necessary for documents.
- Professional headline.
- Role history.
- Employers, where present.
- Employment dates.
- Role descriptions.
- Skills and tools.
- Education.
- Certifications and licences.
- Languages.
- Projects.
- Reported results and achievements.
- Preferred professional categories that can be reasonably inferred.

Every extracted fact must preserve its origin and evidence.

Never infer work authorization from nationality, residence or the presence of a language.

### Step 4 — Ask only unresolved questions

Collect:

1. Where do you currently live?
2. In which countries or cities would you like to work?
3. What kinds of roles are you looking for?
4. Do you prefer remote, hybrid or on-site work?
5. Where are you legally authorized to work?

Ask additional questions only when they materially affect recommendations.

Employment type and salary can remain optional.

For a candidate seeking regulated work, relevant certification and licence questions may be necessary.

Avoid repeatedly asking information already confirmed.

### Step 5 — User confirmation

Present a compact profile summary.

For example:

"You're looking for junior marketing roles in Bucharest or remote positions available from Romania. You have experience with social media, Meta Ads and reporting. Is that correct?"

Allow:

- Looks correct.
- Change something.

The user must actively confirm the profile before personalized processing begins.

### Step 6 — Notifications

Explain the delivery schedule:

"JobPilot can send you a personalized list of suitable vacancies twice a day, at 18:00 and 00:00 in your local timezone."

Require explicit opt-in before activating new-candidate Telegram delivery.

Do not silently activate notifications as a side effect of uploading a CV.

---

# PART III — CANDIDATE TRUTH MODEL

## 7. Resume and candidate-profile architecture

Maintain a distinction between:

1. Original resume.
2. Extracted candidate facts.
3. User-confirmed candidate facts.
4. Search preferences.
5. Vacancy requirements.
6. Job-specific tailoring suggestions.
7. Generated resume versions.

These entities must not be collapsed into one mutable document.

### Original resume

The user's source document.

Properties:

- Private and candidate-owned.
- Immutable once uploaded.
- Versioned when replaced.
- Downloadable by the owner.
- Retained until user-requested deletion, subject to a documented retention policy.
- Never directly exposed to another candidate.
- Not overwritten by AI-generated documents.

### Candidate facts

Store individual statements separately.

Suggested fields:

- Fact identifier.
- Candidate identifier.
- Fact category.
- Structured value.
- Human-readable value.
- Evidence source.
- Source-document version, when applicable.
- Evidence location or quotation.
- Confirmation status.
- Confirmation timestamp.
- Provenance: resume-extracted, user-entered, user-confirmed or externally verified where genuine verification exists.
- Validity dates when relevant.
- Superseded/retracted state.

A user-confirmed statement is self-reported. It must not be represented as independently verified.

### Search profile

Contains only information needed to discover and rank vacancies.

It must reference confirmed candidate facts while maintaining independent search preferences.

Profile changes should create an auditable version.

The matching engine should use the current confirmed revision.

### Generated resume

A candidate-owned, job-specific document generated from an immutable snapshot of approved facts.

Never overwrite the original resume.

## 8. Missing experience confirmation — critical feature

This is an essential product differentiator.

A vacancy may require experience that is absent from a user's resume but genuinely possessed by the candidate.

An omission must not automatically be interpreted as an absence.

### Trigger

A shortlisted vacancy contains a material requirement that is:

- Missing from the source resume.
- Not conclusively contradicted by existing confirmed facts.
- Potentially relevant to the candidate.
- Reasonably answerable by the user.

### Example

Vacancy requires:

"Experience with Google Analytics."

Existing resume mentions digital marketing but not Google Analytics.

JobPilot asks:

"Have you worked with Google Analytics before?"

Actions:

- Yes.
- No.
- Not sure / Some exposure.

If the user answers Yes:

"Where did you use it, and what did you do?"

Suggested optional structure:

- Context: work, internship, project, studies.
- Activities performed.
- Approximate dates or period, if remembered.
- Tools and responsibilities.
- Results, if supported.

Do not demand an exact numerical achievement where the user has none.

### Verification logic

The model may propose a candidate statement:

"Used Google Analytics to monitor website traffic and campaign performance."

It must then show that statement for approval.

The user can:

- Confirm.
- Edit.
- Reject.

Only confirmed wording may be included in a final document.

### Special cases

If the user says No, do not invent the skill.

If the user says Not sure, preserve uncertainty.

If the user confirms only academic exposure, do not relabel it as professional employment.

If the job requires three years of commercial experience, a general Yes does not establish three years.

For a mandatory degree, licence, certification or employment authorization, a loosely related statement must not be treated as proof.

Do not encourage the user to falsely claim qualifications.

### Reusing confirmed experience

After approval:

1. Create an immutable user-confirmed fact.
2. Reference the fact in the job-specific tailoring draft.
3. Generate `CV_updated` as a new version.
4. Re-evaluate the affected matching factors if appropriate.
5. Offer one unobtrusive choice to retain the fact in the candidate's reusable master profile.

Do not automatically rewrite the original CV.

Confirmed facts can improve future matching, but source-specific details and the user's consent must be preserved.

### Avoid repeated interrogation

Cache candidate responses using a stable fact identity.

Do not ask whether the user knows the same skill for every vacancy.

Ask at most a small, configurable number of high-value follow-up questions per tailoring session.

No repeated questioning after an explicit rejection unless the candidate later edits their profile.

---

# PART IV — VACANCY DISCOVERY AND MATCHING

## 9. Shared global vacancy catalogue

Retain the architecture introduced in Phase 4:

- One shared catalogue.
- Candidate-independent vacancy facts.
- Candidate-specific matching.
- Candidate-owned workflow.
- Independent legacy owner notifications.

Do not create a complete copied vacancy catalogue for every user.

Add geographic and professional coverage incrementally.

### Supported geography

Allow users to select:

- Multiple countries.
- Multiple cities per country.
- Remote work.
- Regional remote restrictions.
- Worldwide-remote vacancies when actually applicable.

Residence, work authorization and relocation willingness must remain separate facts.

A worldwide-remote label does not establish permission to employ a person in every country.

### Vacancy categories

Develop a normalized occupation taxonomy that supports cross-industry role matching.

Use standardized families with profession-specific synonyms and aliases.

Do not let a software-oriented skills taxonomy dominate results for marketing, finance, logistics, healthcare or other professions.

The original hard-coded owner screening must remain isolated from personalized candidate matching.

## 10. Source strategy

Prioritize sources in this order:

1. Official public ATS APIs.
2. Official employer career endpoints with authorized public access.
3. Structured job metadata on publicly accessible career pages.
4. Permitted JavaScript-rendered public pages through bounded browser automation.
5. Additional licensed or partner data sources, when commercially appropriate.

Existing integrations should be reused and generalized where possible.

Evaluate additional employer platforms based on:

- Geographic coverage.
- Vacancy quality.
- Freshness.
- Duplicate rate.
- API availability.
- Reliability.
- Terms of use and applicable access restrictions.
- Infrastructure cost.
- Maintenance burden.

### Playwright and Camoufox

Playwright may be considered for permitted JavaScript-rendered pages.

Camoufox may be evaluated independently as a technical prototype.

Do not make anti-bot evasion, CAPTCHA bypass, authentication circumvention or prohibited scraping an architectural dependency.

Do not assume every page can lawfully be scraped.

Every browser-based adapter must have:

- Explicit source approval.
- Isolated execution.
- Network restrictions.
- Request limits.
- Concurrency limits.
- Timeouts.
- Memory and CPU bounds.
- Maximum page count.
- Maximum content size.
- Per-source health metrics.
- Failure isolation.
- A documented shutdown mechanism.

Browser workers should not have access to candidate resumes or Telegram credentials.

### Source freshness

Track:

- Provider.
- External job identifier.
- Canonical URL.
- First seen.
- Last seen.
- Published date, if trustworthy.
- Vacancy validity.
- Content hash.
- Extraction revision.
- Source health.

Never describe a vacancy as open merely because its cached record exists.

Do not claim complete worldwide coverage.

## 11. Matching architecture

Preserve deterministic candidate eligibility as the authoritative first stage.

Matching has separate concepts:

**Eligibility**

- Suitable under supported mandatory requirements.
- Confirmed incompatible.
- Unknown due to missing or ambiguous facts.

**Compatibility**

- Role alignment.
- Skills alignment.
- Experience alignment.
- Preferences.
- Evidence coverage.

**AI explanation**

A human-readable explanation of available evidence, not an independently authoritative eligibility verdict.

### Ranking process

1. Fetch and normalize a vacancy.
2. Extract candidate-independent requirements.
3. Store source evidence.
4. Evaluate the candidate's current profile.
5. Apply confirmed mandatory blockers.
6. Calculate deterministic compatibility.
7. Keep uncertainty explicit.
8. Shortlist suitable or potentially suitable opportunities according to an internally versioned policy.
9. Optionally run AI analysis on selected high-value jobs.
10. Present useful, understandable recommendations.

Never allow an LLM to remove a confirmed hard blocker without new, valid evidence and deterministic re-evaluation.

If a requirement is unknown, label it as requiring confirmation.

If the system has inadequate evidence, avoid false certainty.

### Matching transparency

For each vacancy, show:

- Company.
- Job title.
- Location.
- Working arrangement.
- Salary when available.
- Main matching reasons.
- Important missing or uncertain requirements.
- Confirmed incompatibilities.
- Source link.
- Publication/verification information.
- Tailoring availability.

Compatibility scores must never be presented as the probability of an interview or offer.

---

# PART V — AI ARCHITECTURE

## 12. Role of AI

AI should assist with:

- Resume structure extraction.
- Professional skill normalization.
- Candidate profile summarization.
- Targeted clarification questions.
- Vacancy requirement interpretation.
- Job-specific resume improvement advice.
- Truth-constrained resume rewriting.
- Optional short cover-note generation.

AI should not be responsible for:

- Authorization.
- Identity.
- Database ownership.
- Consent.
- Delivery routing.
- Source admission.
- Independently authorizing or executing external job applications. Future Phase 12 may use evidence-constrained AI assistance within deterministic, candidate-authorized orchestration; the model never supplies authorization, invents binding answers or decides that submission succeeded.
- Final mandatory eligibility decisions.
- Inventing qualifications.
- Production configuration.

## 13. Model strategy

Use a small, cost-efficient OpenAI API model suitable for structured extraction and short professional analysis.

Do not hardcode a model name before checking current API availability, prices, structured-output support and quality on representative fixtures.

Create a provider abstraction so models can be upgraded or replaced without changing core product state.

Pin tested model identifiers and prompt/schema versions.

Separate model use cases:

| Task | Recommended processing |
|---|---|
| Basic job eligibility | Deterministic, no LLM |
| Bulk initial matching | Deterministic, no LLM |
| Resume extraction | Bounded AI-assisted structured extraction |
| Vacancy interpretation | Rules first, optional bounded AI fallback |
| Match explanation | Optional small LLM |
| Clarification question | Template first, AI when useful |
| CV rewriting | Structured AI with evidence validation |
| PDF/DOCX rendering | Deterministic document generator |

Do not send every vacancy and every complete resume to an LLM.

### Structured outputs

Every AI operation must have a versioned schema.

Reject malformed, oversized, inconsistent or unsupported outputs.

Do not blindly trust model-generated source quotations or evidence IDs.

Validate every source reference against persisted candidate facts and vacancy evidence.

### Prompt injection resistance

Resume text and vacancy descriptions are untrusted input.

A job posting can contain instructions that attempt to:

- Reveal system prompts.
- Access other users' records.
- Ignore matching rules.
- Invent candidate experience.
- Change notification destinations.
- Generate unsafe external requests.

None of these instructions may acquire system authority.

Restrict the model to the specific approved operation and validated input context.

## 14. AI financial controls

Free beta must have server-controlled cost limits.

Implement:

- Per-user usage accounting.
- Global daily and monthly spend ceilings.
- Task-specific budgets.
- Input/output token limits.
- Idempotent request accounting.
- Concurrency limits.
- Safe retry policy.
- Analysis caching using privacy-safe revision identities.
- Circuit breakers.
- Graceful fallback when the AI provider is unavailable.

Prefer deterministic matching and saved explanations when spending limits are reached.

Do not make job discovery dependent on successful AI processing.

Propose concrete quota values after benchmarking actual provider costs and onboarding usage.

Suggested beta experiments:

- Limited AI resume analysis per user.
- Bounded new vacancy explanations.
- Bounded tailored resume generations.
- Additional operations subject to shared beta budgets.

These are internal guardrails, not additional user settings.

Avoid displaying technical token quotas to ordinary users. Provide a simple message when a premium AI action is temporarily unavailable.

---

# PART VI — TELEGRAM EXPERIENCE

## 15. Bot and Mini App responsibilities

### Telegram bot

The bot is responsible for:

- Welcome and onboarding entry.
- CV upload.
- Connection verification.
- Notification opt-in.
- Personalized digests.
- Brief job cards.
- Deep links into the Mini App.
- Essential account and notification commands.

### Mini App

The Mini App is responsible for:

- Detailed onboarding and profile review.
- Job recommendations.
- Job detail and explanations.
- Saved jobs.
- Application tracking.
- Resume tailoring sessions.
- Document previews.
- Downloading generated resumes.
- Minimal settings.
- Privacy and account management.

Prefer guided user actions over free-form conversation for high-risk operations.

## 16. Notification policy

For new candidates, the default proposed delivery schedule is:

- 18:00 local time.
- 00:00 local time.

This schedule applies only after explicit notification activation and a separately approved schedule change. It is future product direction; the currently approved Phase 5 contract remains immediate alerts plus an optional daily digest, with no silent activation or owner schedule change.

Do not infer a change to the original owner's production schedule from this proposal.

Before modifying legacy delivery, inspect and characterize the real production configuration separately.

### Digest content

Each digest should contain a bounded selection of the best new opportunities.

Include:

- Title and company.
- Location and work arrangement.
- Short suitability explanation.
- Important uncertainty warning when relevant.
- Link to full details.
- Save or dismiss entry point.

Do not include complete CVs or sensitive profile data in Telegram messages.

### Deduplication

Use candidate-scoped logical delivery identities.

Avoid repeated notifications caused by:

- Re-ingesting the same vacancy.
- Matching-worker retries.
- Profile edits.
- Historical backfills.
- Application restarts.
- Delivery-scheduler restarts.
- Account reconnection.

### Historical baseline

When notifications are first enabled, create a stable activation baseline.

Show existing matching opportunities in the Mini App without sending hundreds of historical notifications.

Only appropriate new opportunities after activation should enter automatic digests.

### Delivery safety

Use:

- Verified private Telegram chat identity.
- Candidate-owned preferences.
- Durable PostgreSQL outbox.
- Transactional work claims.
- Bounded delivery retries.
- Safe handling of ambiguous Telegram outcomes.
- Revalidation before sending.
- Explicit opt-out checks.
- No accidental double-sending from legacy and personalized paths.

A Telegram timeout after an attempted send may have an ambiguous outcome. Do not promise guaranteed exactly-once external delivery.

### Timezones

Use IANA timezone identifiers.

Handle:

- Daylight-saving transitions.
- Midnight date boundaries.
- Missed delivery windows.
- Delayed workers.
- User timezone changes.
- Multiple schedulers.
- Duplicate local-time slots.

Delivery events must have stable slot identities.

---

# PART VII — JOB-SPECIFIC RESUME TAILORING

## 17. Vacancy analysis

When the user opens a promising vacancy, show:

1. Why the candidate matches.
2. Which requirements are supported by known facts.
3. Which requirements are unconfirmed.
4. Which requirements are confirmed blockers.
5. What relevant experience may be missing from the written CV.
6. What truthful changes could improve the resume's relevance.

Keep advice specific.

Avoid generic suggestions such as "improve your communication skills" unless supported by the vacancy and useful to the candidate.

## 18. Interactive tailoring session

Suggested flow:

**Step A — Compare**

Compare the vacancy requirements with the candidate's confirmed professional history.

**Step B — Identify opportunities**

Select a small number of high-impact improvements.

Examples:

- Move relevant work experience higher.
- Emphasize matching projects.
- Clarify a role description.
- Add an existing but omitted tool.
- Include measured achievements already supported by evidence.
- Remove irrelevant content where appropriate.
- Rewrite vague bullets into precise, accurate statements.

**Step C — Ask targeted questions**

For genuinely unresolved experience:

"Have you ever used this tool?"

"Was this part of your employment, internship or personal project?"

"What exactly did you do?"

"Approximately when?"

"Can you confirm this wording?"

Ask only when an answer can materially improve the document.

**Step D — Validate**

Maintain a candidate-fact provenance map for every generated professional claim.

Reject unsupported claims.

**Step E — Generate**

Create a job-specific draft.

**Step F — Review**

Show a structured difference between the original content and the proposed version.

**Step G — Approve**

Require explicit user approval.

**Step H — Export**

Provide editable DOCX and PDF versions.

## 19. Document versions

Suggested user-facing names:

- Original CV.
- CV updated for [Company] — [Role].
- CV updated for [Company] — [Role], Version 2.

Internally, use immutable IDs rather than relying on filenames.

Suggested metadata:

- Candidate ID.
- Source CV version.
- Search-profile revision.
- Target vacancy ID.
- Target vacancy revision.
- Approved candidate fact IDs.
- Prompt/template version.
- Generation timestamp.
- Document hash.
- Review status.
- Download status.
- Superseded state.

Preserve editable DOCX fidelity where possible.

Generate PDF from the reviewed version, and verify the PDF and DOCX contain consistent professional claims.

Do not create visually impressive formatting at the expense of ATS readability.

## 20. Truthfulness guarantees

Every factual resume statement must be supported by either:

- Source CV evidence.
- A manually entered candidate fact.
- An explicit user-confirmed candidate fact.
- A genuinely verified external fact, if that capability is later introduced.

The model cannot independently certify self-reported experience.

Never fabricate:

- Employment.
- Dates.
- Job titles.
- Degrees.
- Certifications.
- Professional licences.
- Language levels.
- Years of experience.
- Technologies used.
- Numerical achievements.
- Leadership responsibilities.

An improvement recommendation may suggest adding a missing fact, but the fact cannot become final resume content until the user confirms it.

Preserve the distinction between academic, personal, internship and professional experience.

---

# PART VIII — SECURITY, PRIVACY AND DATA MANAGEMENT

## 21. Resume privacy

Because resumes frequently contain sensitive personal information, treat them as protected candidate-owned documents.

Requirements:

- Private object storage or equivalent protected artifact storage.
- Encryption at rest and in transit.
- Strict per-candidate authorization.
- Nonpredictable storage identifiers.
- No public file URLs.
- Short-lived authenticated download access.
- File-type and content validation.
- Size and page limits.
- Safe PDF/DOCX parsing.
- Protection against malicious or decompression-bomb documents.
- No executable macros.
- No access to arbitrary filesystem paths.
- No plaintext CV content in ordinary logs.
- No raw document contents in analytics.
- No cross-user caching of private content.

Original resumes should remain stored until the user requests deletion, subject to the communicated retention and backup policy.

The user must be able to replace, view and delete them.

## 22. External AI processing

Before sending personal resume content to OpenAI or another external provider:

- Provide a clear privacy notice.
- Obtain the required user consent or establish and document another appropriate lawful basis, with legal review.
- Minimize transferred personal data.
- Avoid transmitting personal contact details when unnecessary.
- Document subprocessors and international data transfers.
- Review applicable API retention and data-processing terms.
- Do not assume that API use automatically satisfies GDPR.
- Provide a non-AI or manual-profile path where feasible.

Consent for AI processing must not silently imply consent to notifications or unrelated data sharing.

## 23. Data deletion

Provide user-initiated deletion for:

- An individual resume.
- An obsolete generated resume.
- Candidate profile and associated history, subject to applicable retention requirements.
- The complete account.

Deletion must address:

- Active database records.
- Object storage.
- Derived artifacts.
- Search indexes.
- Model-analysis caches.
- Queued background work.
- Pending notifications.
- Restored historical records and backups under a documented lifecycle.

Do not claim instantaneous deletion from immutable backups if that is not technically true.

Design for revocation, bounded backup retention and prevention of deleted records being silently reactivated after a restore.

## 24. Candidate isolation

Every private request must derive candidate ownership from authenticated server-side identity.

Never accept client-provided candidate IDs as authorization.

Prove isolation for:

- CV uploads and downloads.
- Extracted profile facts.
- Saved user confirmations.
- Search profiles.
- Matching results.
- Tailoring sessions.
- Generated documents.
- Notifications.
- Application history.
- AI analysis caches.
- Export and deletion operations.

Account switching and session expiration must invalidate stale frontend responses and pending sensitive actions.

---

# PART IX — PHASED ENGINEERING ROADMAP

## Phase 5 — Personalized Telegram Delivery

**Current status:** Preliminary implementation work initiated; not complete.

### Objective

Safely extend notification delivery to multiple users without changing the original owner's existing behavior.

### Scope

- Verified user-to-private-chat binding.
- Explicit notification consent.
- Candidate-owned notification preferences.
- Immediate strong-match alerts and an optional daily digest, as authorized in the current Phase 5 request.
- Candidate-configurable minimum compatibility score, timezone and optional quiet hours; numeric threshold is optional advanced UI.
- Twice-daily digest scheduling is future direction and is excluded from the current Phase 5 implementation.
- Post-subscription baseline.
- Personalized digest selection.
- Durable outbox.
- Duplicate prevention.
- Failure handling.
- Timezone correctness.
- Pause/resume.
- Basic notification controls in the Mini App.
- Legacy compatibility.
- Recovery-safe operation.

### Explicit exclusions

- Resume upload.
- AI onboarding.
- New web scrapers.
- Automated applications.
- Resume tailoring.
- Production deployment.

### Acceptance criteria

1. Two candidates receive only their own relevant vacancies.
2. A third party cannot redirect deliveries.
3. New candidate delivery requires explicit opt-in.
4. Opt-out prevents subsequent queued delivery.
5. No historical flood occurs after enabling alerts.
6. Duplicate matching results do not generate duplicate logical delivery events.
7. Recovery mode sends nothing.
8. Digest schedule handles timezone and DST behavior.
9. Legacy owner alerts and digest remain unchanged.
10. Full Java/PostgreSQL/frontend verification passes.
11. Saved-image recovery is tested against the new schema.
12. Independent security review closes all Critical/High findings.

### Gate

Local GO only after full verification.

Production remains NO-GO until a separate operational release process.

**Important:** Continue from the existing unfinished Phase 5 work. Do not restart the implementation or discard its checkpoint.

---

## Phase 6 — Secure Resume Management & Structured Extraction

### Objective

Allow every candidate to upload, maintain and inspect their own resumes.

### Scope

- Telegram PDF/DOCX upload.
- Mini App resume upload.
- Secure private storage.
- Original document versioning.
- File validation.
- Text extraction.
- Resume parsing schema.
- Evidence-linked structured candidate facts.
- Explicit candidate review.
- Manual-profile alternative.
- Processing retries.
- User deletion.
- Controlled AI usage.
- Clear extraction failures.

### Engineering tasks

1. Audit existing owner-only document storage and generation.
2. Design candidate-owned resume entities and file references.
3. Add the next valid additive Flyway migration.
4. Implement secure Telegram file retrieval.
5. Implement private upload/download endpoints.
6. Build a bounded document parsing pipeline.
7. Integrate optional structured AI extraction.
8. Preserve source-evidence references.
9. Build extraction-review UI.
10. Implement resumable processing states.
11. Implement document replacement and deletion.
12. Extend recovery and privacy tests.

### Acceptance criteria

- Candidate A cannot view or download Candidate B's document.
- Original files remain byte-identical.
- PDF and DOCX extraction work for representative fixtures.
- Invalid and malicious files fail safely.
- Unsupported facts are never silently invented.
- An AI parsing failure does not prevent manual onboarding.
- Deletion prevents future ordinary access to the document.
- AI usage respects consent and cost budgets.
- No user document content appears in ordinary logs.
- Recovery preserves existing users' data and does not expose deleted records.

### Gate

Secure candidate-owned resume storage and verified fact extraction.

---

## Phase 7 — Intelligent Minimal Onboarding

### Objective

Turn a resume or manually entered information into a usable search profile with minimal friction.

### Scope

- Short guided questionnaire.
- Resume-derived profile suggestions.
- User confirmation.
- Missing-fact clarification.
- Work-authorization questions.
- Multi-country selection.
- Occupation selection.
- Basic salary and arrangement preferences.
- Resume-less onboarding.
- Profile editing.
- Progress recovery.

### Engineering tasks

1. Design a minimal onboarding state machine.
2. Reuse the Phase 3 versioned search-profile foundation.
3. Map extracted facts to proposed search-profile fields.
4. Distinguish inferred preferences from confirmed facts.
5. Avoid repeated questions.
6. Validate geographic and legal-work inputs.
7. Add professional occupation normalization.
8. Add interrupted-onboarding recovery.
9. Design mobile-first review screens.
10. Add accessibility and plain-language tests.

### Acceptance criteria

- A candidate can complete onboarding with a CV.
- A candidate can complete onboarding without a CV.
- No residence-to-authorization inference occurs.
- Several countries/cities can be selected.
- Users can correct AI extraction mistakes.
- Technical configuration remains hidden.
- Back/refresh/reopen actions preserve progress.
- Search-profile revisions remain candidate-owned.
- The matching engine uses only the currently confirmed active profile.

### Gate

Usable onboarding without requiring technical knowledge or an uploaded CV.

---

## Phase 8 — Global Professional Search & Source Expansion

### Objective

Move from primarily Romania-oriented source discovery to configurable multi-region, multi-profession discovery.

### Scope

- Multi-country and multi-city search support.
- Occupation taxonomy.
- Role aliases.
- Generalized geographic matching.
- Remote eligibility.
- Source coverage registry.
- Additional permitted ATS adapters.
- Optional bounded browser-based source adapters.
- Source freshness validation.
- Global duplicate prevention.
- Search coverage diagnostics.
- Capacity analysis.

### Engineering tasks

1. Audit source retrieval against user-selected geographies.
2. Separate source-level filters from candidate eligibility.
3. Design globally applicable location normalization.
4. Generalize role taxonomy beyond technology.
5. Identify high-quality sources by profession/region.
6. Prefer approved public ATS APIs.
7. Benchmark optional Playwright extraction.
8. Evaluate Camoufox separately, only where access is permitted.
9. Add source-specific rate limits and shutdown controls.
10. Extend vacancy expiration and canonicalization.
11. Benchmark catalogue growth and candidate fanout.
12. Re-evaluate matching history retention and indexing.

### Acceptance criteria

- Marketing, software, finance, logistics and other representative professions do not share inappropriate role biases.
- Multiple countries are supported without changing the legacy owner's screening.
- Country-specific restrictions remain independently evaluated.
- Unverified remote scope does not become unrestricted worldwide employment.
- Failed providers do not block healthy providers.
- No protected-site access controls are bypassed.
- Deduplication works across supported sources.
- Source health and data freshness are observable.
- No claim of comprehensive global coverage is made without evidence.
- Production capacity remains a separate release gate.

### Gate

Demonstrated multi-region and multi-profession discovery with documented limits.

---

## Phase 9 — AI Vacancy Intelligence & Interactive Fact Confirmation

### Objective

Add evidence-backed professional analysis to the existing deterministic matching engine.

### Scope

- Shortlisted vacancy interpretation.
- Structured AI explanations.
- Candidate skill-gap detection.
- Missing-resume-fact questions.
- Candidate confirmations.
- Fact provenance.
- Re-scoring after relevant confirmations.
- Cost-aware model selection.
- AI budget enforcement.
- Prompt injection testing.
- Uncertainty-aware UX.

### Engineering tasks

1. Define AI input contracts.
2. Use deterministic shortlist selection.
3. Send only necessary candidate and vacancy evidence.
4. Implement versioned structured outputs.
5. Validate all referenced facts and requirements.
6. Identify high-value missing facts.
7. Generate minimal clarification questions.
8. Save approved user-confirmed facts.
9. Recompute affected matches.
10. Reuse confirmed facts to avoid repetitive questioning.
11. Add model caching and failure fallbacks.
12. Add quality evaluation fixtures and cost dashboards.

### Acceptance criteria

- AI cannot override deterministic mandatory blockers.
- The system asks before using missing professional experience.
- Rejected claims never appear in a generated CV.
- Uncertain skill exposure remains uncertain.
- User confirmation does not become independent certification.
- A candidate can correct or retract a confirmed fact.
- False claim references are rejected.
- Malicious job text cannot change instructions or reveal private data.
- User data never appears in another user's AI context.
- AI outages do not stop deterministic job recommendations.
- Cost controls are tested under concurrency and retries.

### Gate

Evidence-backed explanations and user-confirmed fact enrichment.

---

## Phase 10 — Tailored CV Generation & Review

### Objective

Produce accurate, job-specific DOCX/PDF resumes after candidate review.

### Scope

- Job-specific tailoring recommendations.
- Fact-to-requirement mapping.
- Interactive missing-experience questions.
- Updated professional summaries.
- Relevant skill prioritization.
- Achievement rewrites based on actual facts.
- ATS-friendly templates.
- Change comparison.
- User approval.
- Document generation.
- Secure download.
- Immutable version history.
- Regeneration after candidate edits.

### Engineering tasks

1. Audit and reuse existing truthful document-generation code.
2. Extend it to authenticated candidate ownership.
3. Introduce job-specific tailoring sessions.
4. Pin source resume, fact and vacancy revisions.
5. Generate structured change proposals.
6. Validate each claim against approved facts.
7. Implement resume diff/review screens.
8. Require explicit document approval.
9. Render DOCX.
10. Produce matching PDF.
11. Validate generated document consistency.
12. Store both artifacts privately.
13. Add download/deletion controls.
14. Test stale-job and changed-profile behavior.

### Acceptance criteria

- Original CV never changes.
- `CV_updated` is a separate private artifact.
- Every factual addition has approved provenance.
- Job-specific information is not confused with personal qualifications.
- Candidate approval precedes final use/export.
- DOCX is editable and PDF is readable.
- Documents remain ATS-friendly.
- Another user cannot retrieve the generated resume.
- An obsolete AI result cannot publish over a newer revision.
- Retries never silently duplicate or replace documents.
- User deletion respects document dependencies and retention rules.
- No automatic employer submission occurs.

### Gate

Reliable and truthful candidate-approved resume tailoring.

---

## Phase 11 — Closed Beta, Quality Evaluation & Production Readiness

### Objective

Validate the entire candidate journey under realistic operational conditions before exposing the service publicly.

### Scope

- Invitation-only access.
- Controlled beta onboarding.
- Realistic test users from different professions.
- Full workflow observability.
- AI spending safeguards.
- Privacy audit.
- Source reliability.
- Candidate feedback.
- Production capacity planning.
- Database backup/recovery.
- Existing owner notification preservation.
- Deployment and rollback runbooks.

### Suggested initial beta cohort

Recruit a small group representing different search patterns:

- Technology.
- Marketing.
- Finance.
- Logistics.
- One additional profession with distinct requirements.

Include candidates with and without uploaded CVs.

Use synthetic profiles for adversarial privacy tests, not real users' private data.

### Quality tests

Measure:

- Onboarding usability.
- Correct role matching.
- Work-authorization handling.
- Missing-fact confirmation behavior.
- CV rewrite truthfulness.
- Notification relevance.
- Digest deduplication.
- User satisfaction.
- Real API expense.
- Source freshness.
- Worker throughput.
- Recovery performance.

### Production controls

Before deployment, separately validate:

1. Actual Azure environment and access.
2. Backup and restoration capability.
3. Flyway migration compatibility.
4. Production database capacity.
5. Notification ownership and Telegram poller uniqueness.
6. Caddy public route allowlist.
7. Authentication and admission controls.
8. Secrets and environment configuration.
9. Background job capacity.
10. Disk and backup growth.
11. AI credentials and expenditure limits.
12. Private resume storage.
13. Operational alerts.
14. Live legacy owner behavior.
15. Recovery-image compatibility.
16. Go/no-go rollback procedures.
17. GDPR-related operational and legal requirements.

### Gate

An explicit production release authorization based on measured evidence.

Local test success never automatically authorizes deployment.

---

## Phase 12 — Candidate-Authorized Application Automation

**Status:** Approved future product direction on 20 October 2026. No Phase 12 implementation, migration, browser worker, external submission or deployment is authorized now.

### Objective and dependency gate

After the required candidate-owned resume/fact, onboarding, AI analysis, document-review and Phase 11 operational safeguards are verified and separately approved, allow a candidate to deliberately authorize preparation and submission for one exact vacancy. The current Phase 5 scope remains unchanged.

The detailed proposal is [Autonomous Job Applications](autonomous-applications.md). Its “Proposed for design review” status applies to the detailed engineering design, not to whether Phase 12 belongs in the approved future roadmap.

### Planned scope

- 12A: Durable candidate-owned execution, cancellation and recovery.
- 12B: Official supported APIs or permitted isolated browser form automation.
- 12C–12D: Provenance-based field answers and bounded, risk-aware grouped clarification; 0.10 uncertainty remains a heuristic rather than a calibrated probability.
- 12E: Reuse Phase 9/10 missing-skill confirmations and approved facts; do not create a competing fact model or relabel academic experience as employment.
- 12F: Versioned writing-quality integration that preserves facts, limits and candidate voice; an installed ChatGPT skill is not assumed to be a production API.
- 12G–12H: Vacancy-specific submission authorization, high-risk declaration checks, receipts, ambiguous-outcome holds and candidate-owned application history.
- 12I: Independent isolation, accuracy, browser, retry, privacy, security and recovery verification.

### Non-negotiable boundaries

A deliberate candidate action grants authority only for the selected vacancy and disclosed employer destination. Ordinary supported fields may be filled from approved facts without repeated confirmation. New binding declarations, essential unresolved facts, CAPTCHA, MFA, prohibited automation or unapproved access require a pause/manual handoff. Never fabricate qualifications, credentials or answers, bypass security, blindly retry an uncertain submission or claim success without evidence. Recovery never submits applications. These safeguards apply regardless of any later phase approval.

### Gate

A separate Phase 12 design and test plan, explicit implementation approval, independent reviews, bounded supported-platform allowlist and separately authorized release are required. Phase 12 is outside the initial MVP and does not authorize work in Phases 6–12 during Phase 5. Production remains NO-GO.

---

# PART X — CROSS-PHASE ENGINEERING RULES

## 25. Mandatory preservation constraints

Throughout all phases:

- Preserve existing database migrations byte-for-byte.
- Select new Flyway versions only after inspecting the repository inventory.
- Use additive, independently tested migrations.
- Never silently rewrite historical user facts.
- Never merge legacy owner data into another candidate's profile.
- Keep original owner Telegram delivery compatible.
- Preserve saved recovery artifacts and their hashes.
- Do not purge history merely to make a migration pass.
- Do not downgrade Flyway schema versions.
- Keep recovery mode read-only with respect to candidate background processing.
- Do not publish images or deploy without explicit approval.
- Do not modify production secrets.
- Do not use a production database for exploratory testing.

## 26. Phase implementation workflow

For each phase, Codex must follow this sequence:

### A. Inspect

- Inspect Git status and current branch.
- Identify the frozen previous-phase baseline.
- Read relevant architecture and verification reports.
- Enumerate changed modules.
- Confirm protected migration and recovery artifacts.
- Identify existing capabilities that can be reused.
- Inspect current test infrastructure.

### B. Design

Produce a phase-specific design specification containing:

- User stories.
- Acceptance criteria.
- API contracts.
- Database changes.
- Security boundaries.
- Component dependencies.
- Data-flow diagrams.
- Failure modes.
- Recovery behavior.
- Performance implications.
- Cost implications.
- Explicit non-goals.

Identify architectural alternatives and recommend one.

### C. Review

Conduct an independent design review with attention to:

- Cross-user authorization.
- Privacy.
- Candidate fact truthfulness.
- Legacy owner behavior.
- Concurrency.
- Retry semantics.
- Database integrity.
- AI prompt injection.
- Resource bounds.
- Deployment recovery.

Resolve Critical/High blockers before implementation.

### D. Implement

Use incremental, test-driven implementation.

Keep changes scoped to the current phase.

Do not start the next phase merely because implementation is progressing quickly.

### E. Verify

Run:

- Java unit tests.
- Real PostgreSQL integration tests.
- Frontend tests.
- Typecheck.
- Lint.
- Production builds.
- Migration preservation checks.
- Recovery compatibility tests.
- Independent security review.
- Relevant performance benchmarks.
- Actual-image rehearsal when required.

Report exact counts, including skipped tests.

### F. Preserve

- Create a stable source checkpoint.
- Preserve source/image/report hashes.
- Maintain a complete change inventory.
- Ensure required nonsecret source is committed when approved.
- Keep generated private artifacts out of public Git.
- Do not accidentally replace existing evidence directories.

### G. Report

Provide:

- Implemented functionality.
- Deferred functionality.
- Exact changed files.
- Migration inventory.
- Tests passed/failed/skipped.
- Independent review findings.
- Remaining limitations.
- Performance and storage measurements.
- Security and privacy assessment.
- Source identity.
- Recovery-image status.
- Local GO/NO-GO.
- Production GO/NO-GO.

Do not describe a planned feature as implemented.

---

# PART XI — CRITICAL END-TO-END ACCEPTANCE SCENARIOS

## Scenario A — Candidate with complete CV

A marketing candidate uploads a CV containing relevant skills and experience.

Expected:

- Profile extraction identifies actual qualifications.
- Candidate reviews the results.
- Country and city preferences are collected.
- Matching finds relevant marketing positions.
- Incompatible mandatory requirements are not hidden.
- Twice-daily notifications contain only candidate-specific recommendations.

## Scenario B — Candidate without CV

A logistics candidate selects manual onboarding.

Expected:

- Candidate can create a usable search profile.
- Job recommendations function without AI document extraction.
- Resume tailoring remains unavailable or appropriately limited until sufficient document/fact input exists.
- User is not forced to upload a resume.

## Scenario C — Missing skill that user actually has

A vacancy requires Google Analytics.

Candidate CV does not mention Google Analytics.

Candidate confirms prior use and describes actual tasks.

Expected:

- JobPilot records the exact user-confirmed experience.
- Matching may be revised when the fact is relevant.
- The proposed resume statement is shown for approval.
- The approved fact appears in the new tailored document.
- Original CV remains unchanged.
- Future duplicate questions are avoided.

## Scenario D — Missing skill that user does not have

Candidate explicitly confirms no experience with the required tool.

Expected:

- The tool is not invented.
- The candidate is not pressured to claim it.
- The matching result preserves the correct eligibility outcome.
- Generated resume excludes the unsupported skill.

## Scenario E — Cross-country job search

Candidate lives in Romania and selects Germany and remote positions across Europe.

Expected:

- Residence is not treated as German work authorization.
- Country-specific restrictions are evaluated independently.
- Relocation preferences do not override legal requirements.
- Unknown authorization is flagged instead of inferred.

## Scenario F — Dangerous vacancy content

A malicious job posting contains instructions to reveal candidate data or change notification routing.

Expected:

- Content remains untrusted.
- No cross-user information is exposed.
- No system configuration changes.
- No unauthorized API or external action occurs.

## Scenario G — Two users, same vacancy

Two users have substantially different experience and authorization.

Expected:

- Shared vacancy facts are reused.
- Each user has independent eligibility and matching.
- Each receives only their own explanations and documents.
- No cross-user workflow or delivery leakage occurs.

## Scenario H — Interrupted AI generation

AI completes a draft but the candidate changes their profile before approving it.

Expected:

- The old generation cannot silently become current.
- The interface detects outdated source facts.
- The candidate can regenerate safely.
- No document is overwritten.

## Scenario I — Notification restart

A worker crashes during a Telegram send.

Expected:

- Delivery state remains coherent.
- Ambiguous outcomes are handled through the defined reconciliation policy.
- No uncontrolled retry loop occurs.
- Other candidates' notifications remain isolated.

## Scenario J — User deletion

A user removes their CV or deletes their account.

Expected:

- Access is revoked.
- Background work is canceled or invalidated.
- Sensitive active artifacts are deleted according to policy.
- Retention and backup limitations are documented.
- Restoring an old backup does not silently reactivate deleted-user delivery.

---

# PART XII — PRIORITY AND DELIVERY ORDER

## 27. Recommended implementation sequence

| Priority | Phase | Business value |
|---|---|---|
| P0 | Finish Phase 5 notification foundation | Safe proactive delivery |
| P0 | Phase 6 secure resume management | CV-first product experience |
| P0 | Phase 7 minimal onboarding | Low-friction user activation |
| P1 | Phase 8 global search expansion | Relevant job supply |
| P1 | Phase 9 AI explanations and missing-fact confirmation | Higher-quality matching and guidance |
| P1 | Phase 10 tailored document generation | Direct application value |
| P0 for public availability | Phase 11 beta and production hardening | Safe real-world operation |
| Future, after prerequisites and separate approval | Phase 12 candidate-authorized application automation | Bounded preparation/submission for one selected vacancy |

Phase 11 operational/security requirements must influence all earlier architecture decisions, not be postponed entirely until the end.

Do not launch candidate CV processing publicly without appropriate storage, deletion, authorization and privacy controls.

## 28. MVP definition

The minimum useful beta should allow a person to:

1. Start the bot.
2. Upload a resume or create a manual profile.
3. Answer a short questionnaire.
4. Confirm professional facts.
5. Select multiple job-search locations.
6. See personalized matching results.
7. Receive scheduled job recommendations.
8. Review job requirements.
9. Save and track interesting vacancies.
10. Pause notifications and manage personal data.

AI-assisted CV tailoring can be enabled progressively behind controlled beta flags after its separate verification.

The first beta does not need to support every occupation or every country perfectly.

It must communicate real coverage honestly.

## 29. Explicitly excluded from the initial product

Approved future direction does not authorize implementation in the initial product or current Phase 5. The following require a separately approved phase-specific design and implementation request:

- Candidate-authorized automatic job application submission and evidence-backed employer-field assistance under Phase 12; see [the amendment](autonomous-applications.md).

The following remain prohibited even if application automation is later approved:

- Impersonating candidates outside their explicit vacancy-specific authorization.
- Fabricating resume qualifications or employer answers.
- Browser-based bypass of access restrictions.
- CAPTCHA circumvention.

Other initial-product exclusions remain:
- Purchasing job-board access.
- Subscription billing or paid checkout.
- User-facing selection of AI models.
- Complex notification policy editors. The simple optional minimum-score control already authorized for Phase 5 remains in scope; matching weights and model parameters stay internal.
- Public exposure of stored resumes.
- Cross-candidate training datasets.
- Automated outreach to recruiters.
- Automated acceptance of employment agreements.

---

# PART XIII — CODEX EXECUTION INSTRUCTIONS

## 30. Immediate next action

This document is a MASTER PRODUCT ROADMAP, not authorization to implement all phases at once.

Codex must:

1. Read the current repository and the final Phase 4 verification.
2. Inspect the already-started Phase 5 work.
3. Preserve the existing checkpoint and any completed reviews.
4. Compare the ongoing Phase 5 design with this PRD.
5. Identify only material conflicts.
6. Recommend the smallest necessary Phase 5 adjustments.
7. Continue Phase 5 only within its authorized scope.
8. Leave resume extraction, global scraping, AI tailoring and later migrations untouched until their respective design approvals.
9. Maintain explicit local and production release gates.
10. Recognize Phase 12 as approved future product direction only; do not implement it or expand Phase 5. The amendment remains subject to its separate detailed design and implementation approvals.

Before implementing each subsequent phase, create a separate design and test plan, then request approval.

## 31. Engineering judgment

When a requirement is technically uncertain:

- Investigate the actual repository.
- Prefer reuse of verified capabilities.
- Identify tradeoffs.
- Provide evidence.
- Choose the safest minimal architecture.
- Do not silently expand the project.
- Do not claim certainty without measurement.

User experience should remain simple even when internal architecture becomes sophisticated.

## 32. Final product goal

JobPilot should require very little effort from a job seeker.

A person should be able to provide their real professional experience, specify where they want to work and receive useful opportunities automatically.

When a suitable vacancy appears, JobPilot should explain why it fits, identify relevant missing information, ask only necessary questions and help generate a better, truthful resume for that vacancy.

The product must be proactive, understandable, privacy-conscious, affordable to operate and reliable enough that users can trust its recommendations.

**Build a genuinely useful employment assistant, not merely a larger job scraper or a generic AI chatbot.**

---

**END OF MASTER PRD — Planning specification only. No deployment or additional implementation is authorized by this document.**
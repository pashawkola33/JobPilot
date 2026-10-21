# JobPilot 2.0 — PRD Amendment: Autonomous Job Applications

**Status:** Proposed for design review  
**Phase:** 12, after candidate-owned resume management, intelligent onboarding, AI analysis and tailored document generation  
**Feature flag:** `AUTOMATED_APPLICATIONS_ENABLED`, disabled by default  
**Production release:** Not authorized

## Objective

Allow a candidate to select a specific vacancy, press "Apply automatically" and delegate the application process to JobPilot.

JobPilot should complete ordinary application steps without repeated interruptions. It must ask for essential missing information instead of inventing it and must not claim submission success without evidence.

## Phase 12A — Application orchestration

Create a durable, candidate-owned application execution model.

Support:

- A user-initiated application for one exact vacancy.
- Explicit permission to prepare and submit that application.
- A bounded execution session.
- Candidate-owned selected resume versions.
- Durable state transitions.
- Cancellation.
- Retry and recovery.
- Evidence of submission.
- Automatic synchronization with existing candidate application history.

Suggested states:

`CREATED`, `PREFLIGHT`, `NEEDS_INPUT`, `PREPARING_DOCUMENTS`, `FILLING`, `READY_TO_SUBMIT`, `SUBMITTING`, `SUBMITTED`, `OUTCOME_UNKNOWN`, `MANUAL_ACTION_REQUIRED`, `FAILED`, `CANCELLED`.

Do not infer successful submission from a button click alone.

## Phase 12B — Browser-based form automation

Prefer supported official application APIs where appropriate. Otherwise use permitted Playwright browser automation against employer career pages and supported ATS workflows.

Requirements:

- Isolated browser workers with no access to unrelated users' information.
- Per-application network restrictions.
- Safe redirects and destination validation.
- Bounded time, CPU, memory and browser sessions.
- Structured form-field discovery.
- Resume attachment.
- Repeatable field mapping.
- Support for ordinary text fields, dropdowns, checkboxes and relevant document uploads.
- Evidence capture without unnecessarily storing sensitive personal data.
- Detection of successful confirmation pages and application identifiers.
- Failure reporting when a website is unsupported.
- Explicit manual handoff for CAPTCHA, MFA, prohibited automation, unapproved account access and other protected workflows.

Never circumvent site security controls or fabricate accounts or credentials.

No automatic applications should occur without the candidate's deliberate action for that specific vacancy.

## Phase 12C — Evidence-aware question answering

Before filling a form, construct a candidate fact context from:

- The current confirmed profile.
- Original resume evidence.
- Approved user-confirmed facts.
- The selected tailored CV.
- Current application-specific answers.

Each answer must retain provenance.

Classify fields according to risk:

**Low-risk:** exact contact details, already-confirmed professional facts and formatting choices. Autofill.

**Material uncertainty:** missing dates, compensation expectations, experience requirements, work arrangement and similarly consequential answers. Ask when necessary for submission.

**High-risk or binding:** work authorization, regulated qualifications, criminal/background declarations, legal attestations, signatures, sensitive demographic responses and mandatory contractual agreements. Require appropriate explicit candidate input or authorization.

Do not infer answers from unrelated facts.

## Phase 12D — Adaptive clarification engine

Implement a versioned decision policy with a target uncertainty threshold of 0.10.

A model's self-reported confidence must not be treated as calibrated evidence.

Use:

- Source-backed facts.
- Exact field semantics.
- Conflicts between confirmed facts.
- Requirement importance.
- Reversibility of the action.
- Consequences of an incorrect answer.
- Explicit candidate preferences.

Request missing information only when the system cannot safely proceed.

Bundle closely related questions.

Default interaction budget:

- One grouped clarification request.
- Up to one additional targeted follow-up for an unresolved material issue.
- Manual handoff if a required answer remains unavailable.

The question budget is an interruption preference, not permission to guess critical facts.

Never replace uncertainty with a fabricated answer.

## Phase 12E — Vacancy-specific skills discovery

Analyze the precise requirements of a selected vacancy.

Identify useful candidate experience absent from the current CV, such as:

- Java Interfaces, Collections and Exceptions.
- SQL JOIN, INNER JOIN, GROUP BY and subqueries.
- DBeaver and database-querying experience.
- Frameworks, professional tools or domain-specific workflows relevant to other professions.

Ask whether the user has actually used each selected skill.

Collect context when material:

- Professional employment.
- Internship.
- University coursework.
- Personal project.
- Other genuine experience.

Do not convert academic use into commercial experience.

Suggest exact resume wording and request explicit approval.

Save accepted candidate facts with provenance.

Do not silently insert claims based only on model inference.

## Phase 12F — Writing quality integration

Apply the Avoid AI Writing editorial rules to generated free-text application answers, professional summaries, cover notes and optional cover letters.

The purpose is natural, specific, professional writing that reflects the candidate's background.

Mandatory safeguards:

- Preserve factual meaning.
- Preserve employer question intent.
- Preserve source-backed experience.
- Preserve dates, titles, qualifications, numbers and names.
- Do not invent achievements.
- Do not insert fake personal anecdotes.
- Do not imitate a person whose voice the candidate did not provide.
- Keep employer-specified word and character limits.
- Retain necessary technical terminology.
- Do not alter legal statements or structured answers.
- Validate before/after claims and run a bounded editorial review.
- Avoid claims of guaranteed AI-detector evasion.

The installed ChatGPT skill is not a callable JobPilot production API. Engineer a separately reviewed, versioned implementation or approved runtime integration. Verify licensing, dependencies and source provenance.

Writing style review must not modify stored candidate facts.

## Phase 12G — Submission authorization and execution

A candidate's explicit "Apply automatically" action authorizes preparation and submission to that selected employer and vacancy, subject to the product's disclosed terms and any additional required legal consent.

Before submission:

1. Verify the candidate identity and current authorization.
2. Check job identity and destination.
3. Validate the selected document version.
4. Revalidate mandatory application facts.
5. Detect duplicate applications.
6. Confirm that no unresolved high-risk statement remains.
7. Verify required user-specific declarations and consents.
8. Complete supported form steps.
9. Submit within the granted authorization scope.

Do not require an additional confirmation of every ordinary field.

Pause if the employer asks for a new binding declaration, unsupported sensitive information or authorization outside the original scope.

If a form is blocked by access controls or disallows automation, use manual handoff rather than bypassing restrictions.

## Phase 12H — Submission receipts and history

Persist:

- Candidate identifier.
- Vacancy identifier.
- Employer destination.
- Application attempt identity.
- Document versions.
- Approved factual inputs.
- Execution timestamps.
- Final outcome.
- Supported employer confirmation ID or receipt.
- Redacted diagnostic evidence.
- Errors or manual-intervention requirements.

Do not retain session cookies or credentials in application logs.

Treat ambiguous outcomes as `OUTCOME_UNKNOWN`.

Never blindly resubmit after a timeout, network interruption or uncertain confirmation.

## Phase 12I — Independent testing

Required acceptance scenarios:

1. Candidate A cannot access Candidate B's application.
2. A click authorizes only the selected vacancy.
3. Confirmed facts populate normal fields without unnecessary questions.
4. A missing material fact produces an understandable grouped clarification.
5. Rejected skills never enter an application.
6. Academic skill experience is not converted to professional employment.
7. Unknown work authorization cannot be guessed.
8. Sensitive questions are handled according to consent and applicable rules.
9. Resume attachments correspond to the approved document version.
10. Redirects cannot exfiltrate candidate data.
11. An injected webpage prompt cannot control the agent.
12. A concurrent cancellation prevents uncommitted actions where technically possible.
13. Worker retries do not cause blind duplicate submission.
14. Ambiguous submission outcomes remain unresolved until evidence is obtained.
15. CAPTCHA/MFA invokes manual handoff, without bypass.
16. Unsupported employer forms fail safely.
17. A successful application creates a correct candidate-owned history event.
18. Recovery mode never continues submitting external applications.
19. Writing review preserves professional claims.
20. Legacy owner matching and notifications remain unchanged.

Run isolated browser fixtures and mock employer endpoints before authorized end-to-end trials.

Do not conduct unattended live submission testing using real employers without explicit authorization and an appropriate test account or approved environment.

## Release gate

Phase 12 must pass independent privacy, security, data-accuracy, browser-automation, application-state and recovery reviews.

Launch only with a small allowlist of tested application platforms.

Unsupported sites should offer a manual application link.

Remain production NO-GO until separately authorized deployment and real beta verification.

**End of Phase 12 proposal.**
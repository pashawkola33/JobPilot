# JobPilot — Codex Project Instructions

## Product direction

JobPilot is a Telegram-first, multi-user AI job search assistant.

The user should provide a resume or manually create a professional profile, answer a short questionnaire, receive relevant vacancies, improve job-specific resumes and optionally authorize automated applications.

The product should require minimal configuration from nontechnical users.

Read `docs/product/jobpilot-master-prd.md` before planning product changes. Read `docs/product/autonomous-applications.md` for application automation requirements.

These documents describe product direction. They do not authorize implementation beyond the explicitly approved current phase.

## Preserve existing behavior

- Preserve all verified Phases 1–4 functionality.
- Preserve the original owner's Telegram alerts, digests and workflows.
- Inspect current work before modifying code.
- Do not restart an in-progress phase without authorization.
- Keep database migrations immutable.
- Maintain candidate ownership and account isolation.
- Never deploy, access production, push or merge without explicit approval.
- Keep recovery mode free of Telegram sends, scraping and automated applications.

## User experience

- Prefer sensible system defaults over extensive settings.
- Ask the fewest questions necessary to obtain accurate, useful results.
- Use clear language rather than implementation terminology.
- Allow users to control search preferences, personal data, notifications and application authorization.
- Do not expose technical model parameters or matching weights as ordinary settings.

## Evidence and factual accuracy

- Do not invent employment, experience, qualifications, achievements, dates or credentials.
- Missing resume information does not automatically mean missing experience.
- Ask targeted, vacancy-specific questions about potentially relevant skills.
- Distinguish professional, internship, academic and personal-project experience.
- Use explicitly approved user-confirmed facts in generated application documents.
- Keep source documents immutable and generated versions separate.
- Preserve provenance for every factual application claim.

## Application automation

- Automatic application submission requires an explicit candidate action for a specific vacancy.
- Ordinary supported fields may be filled automatically from approved facts.
- Ask concise grouped questions for material missing information.
- Treat a 0.10 uncertainty threshold as a product heuristic, not a calibrated probability.
- Use risk, evidence and reversibility when deciding whether to ask.
- Never guess work authorization, regulated qualifications, binding declarations or other consequential facts.
- Do not endlessly repeat clarification questions.
- Pause and request manual assistance when an essential answer remains unresolved.
- Never bypass CAPTCHA, MFA, prohibited automation or website security controls.
- Never blindly retry an ambiguous external submission.
- Record submission evidence before marking an application successful.

## Writing quality

Use the Avoid AI Writing editorial principles for user-facing free-text applications, tailored professional summaries and cover notes when the skill or its approved implementation is available.

Preserve facts, technical details, intent, candidate voice and employer requirements.

Avoid generic AI phrasing and unsupported personal claims.

Style editing must never become factual invention.

Do not assume a ChatGPT plugin is available to the JobPilot production runtime.

## Engineering workflow

- Design and review one phase at a time.
- Inspect existing implementations before introducing replacements.
- Use test-driven implementation.
- Verify with Java, PostgreSQL, frontend and recovery tests.
- Perform independent security and privacy reviews for sensitive workflows.
- Report exact evidence, limitations and local versus production GO/NO-GO.
- Stop at the boundaries of the currently authorized phase.
/**
 * Frontend-facing mirrors of the JobPilot backend domain.
 *
 * Every field below except the four marked `NOT YET SERVED` maps onto a value the
 * Spring backend already produces. See README "Backend integration boundary".
 */

/** com.jobpilot.jobreview.domain.WorkflowStatus */
export type WorkflowStatus = 'UNREVIEWED' | 'SAVED' | 'APPLIED' | 'DISMISSED';

/** com.jobpilot.jobs.domain.ScreeningDisposition — REJECT is never persisted, so never shown. */
export type ScreeningDisposition = 'MATCH' | 'REVIEW';

/** com.jobpilot.matching.ScoreBand */
export type ScoreBand =
  | 'EXCELLENT_MATCH'
  | 'GOOD_MATCH'
  | 'POSSIBLE_MATCH'
  | 'LOW_MATCH'
  | 'UNSUITABLE';

/** com.jobpilot.jobs.domain.RemoteType */
export type RemoteType = 'REMOTE' | 'HYBRID' | 'ONSITE' | 'UNKNOWN';

/** com.jobpilot.jobs.domain.SeniorityLevel */
export type SeniorityLevel =
  | 'INTERNSHIP'
  | 'TRAINEE'
  | 'WORKING_STUDENT'
  | 'GRADUATE'
  | 'ENTRY_LEVEL'
  | 'JUNIOR'
  | 'MID_LEVEL'
  | 'SENIOR'
  | 'LEADERSHIP'
  | 'UNKNOWN';

/** com.jobpilot.applications.domain.ApplicationStatus */
export type ApplicationStatus =
  | 'SAVED'
  | 'APPLIED'
  | 'INTERVIEW'
  | 'OFFER'
  | 'REJECTED'
  | 'WITHDRAWN';

/** Band thresholds live in JobMatchingService; the score rail draws its ticks from them. */
export const BAND_THRESHOLDS = [55, 70, 85] as const;

export interface Job {
  /** jobs.id — used as a key, never rendered. */
  id: number;
  title: string;
  company: string;
  location: string;
  remoteType: RemoteType;
  seniority: SeniorityLevel;
  /** jobs.employment_type is free text and frequently absent. */
  employmentType: string | null;
  /** JobScore.score, 0–100. */
  score: number;
  /** Null when the vacancy has no job_scores row yet; the rail then shows only the number. */
  band: ScoreBand | null;
  disposition: ScreeningDisposition;
  workflowStatus: WorkflowStatus;
  /** Provider name, e.g. "greenhouse". Rendered as a label, never the tenant slug. */
  source: string;
  publishedAt: string;
  /** Null when no canonical HTTPS link survived TelegramMessageRenderer.safeUrl. */
  canonicalUrl: string | null;
  /**
   * Null in API mode. The backend's nearest value is JobAnalysisData.roleSummary, which
   * only exists after an on-demand LLM analysis, and loading the queue must not trigger
   * one. Absent rather than invented — the UI omits the block instead of filling it.
   */
  matchSummary: string | null;
  /** JobScore.strengths */
  strengths: string[];
  /** JobScore.risks */
  risks: string[];
  /** JobAnalysisData.mustHaveRequirements. Empty in API mode: LLM analysis is on demand. */
  requirements: string[];
  /** Empty in API mode: no backend endpoint combines workflow and application history. */
  activity: ActivityEntry[];
  /** Mock mode only. The API never sends tenants, external ids, or screening codes. */
  diagnostics?: Diagnostics;
}

export interface ActivityEntry {
  at: string;
  label: string;
}

export interface Diagnostics {
  providerTenant: string;
  externalId: string;
  /** JobReasonView[] — stage/code/message triples from deterministic screening. */
  screeningReasons: { stage: string; code: string; message: string }[];
}

export interface Application {
  jobId: number;
  title: string;
  company: string;
  status: ApplicationStatus;
  canonicalUrl: string | null;
  /** Null once the vacancy has aged out of the snapshot: applications outlive the queue. */
  score: number | null;
  /** ISO date of the last status change. */
  updatedAt: string;
  /** Null until the role reaches APPLIED. */
  appliedAt: string | null;
  nextFollowUpDate: string | null;
}

/** com.jobpilot.jobreview.application.JobReviewStats */
export interface ReviewStats {
  unreviewedMatch: number;
  unreviewedReview: number;
  saved: number;
  applied: number;
  dismissed: number;
}

export interface Snapshot {
  jobs: Job[];
  applications: Application[];
}

/**
 * The single seam between UI and backend. Both implementations reject with
 * {@link JobPilotError}; nothing else in the app talks to a server.
 */
export interface JobPilotRepository {
  load(): Promise<Snapshot>;
  setWorkflowStatus(jobId: number, status: WorkflowStatus): Promise<void>;
}

/** Every way a repository call can fail, each with its own user-facing message. */
export type FailureKind =
  | 'telegram-required'
  | 'disabled'
  | 'unauthenticated'
  | 'invalid-auth'
  | 'expired'
  | 'forbidden'
  | 'not-found'
  | 'conflict'
  | 'unavailable';

export const FAILURE_MESSAGES: Record<FailureKind, { title: string; text: string }> = {
  'telegram-required': {
    title: 'Open JobPilot from Telegram',
    text: 'This app signs you in with your Telegram account, so it only works when opened from the JobPilot bot.',
  },
  disabled: {
    title: 'Mini App is switched off',
    text: 'The JobPilot server has not enabled the Mini App API yet. The Telegram bot still works.',
  },
  unauthenticated: {
    title: 'Sign-in details missing',
    text: 'Telegram did not send sign-in details with this launch. Close JobPilot and open it again from the bot.',
  },
  'invalid-auth': {
    title: 'Sign-in could not be verified',
    text: 'The server could not verify this session. Close JobPilot and open it again from the bot.',
  },
  expired: {
    title: 'Session expired',
    text: 'This session has been open too long. Close JobPilot and open it again from the bot.',
  },
  forbidden: {
    title: 'Account not allowed',
    text: 'This Telegram account is not on the JobPilot allow-list.',
  },
  'not-found': {
    title: 'Vacancy no longer available',
    text: 'That vacancy has left the review queue. Reload to get the current list.',
  },
  conflict: {
    title: 'Change was rejected',
    text: 'JobPilot could not apply that change to this vacancy.',
  },
  unavailable: {
    title: 'JobPilot is unreachable',
    text: 'The server did not respond. Your review queue is safe — try again.',
  },
};

export class JobPilotError extends Error {
  readonly kind: FailureKind;

  constructor(kind: FailureKind) {
    super(FAILURE_MESSAGES[kind].title);
    this.name = 'JobPilotError';
    this.kind = kind;
  }
}

import type {
  ApplicationStatus,
  RemoteType,
  ScoreBand,
  SeniorityLevel,
  WorkflowStatus,
} from '../data/types';

/** Enum-to-English. Backend enum names never reach the screen. */

const BANDS: Record<ScoreBand, string> = {
  EXCELLENT_MATCH: 'Excellent match',
  GOOD_MATCH: 'Good match',
  POSSIBLE_MATCH: 'Possible match',
  LOW_MATCH: 'Low match',
  UNSUITABLE: 'Not suitable',
};

const REMOTE: Record<RemoteType, string> = {
  REMOTE: 'Remote',
  HYBRID: 'Hybrid',
  ONSITE: 'On site',
  UNKNOWN: 'Not stated',
};

const SENIORITY: Record<SeniorityLevel, string> = {
  INTERNSHIP: 'Internship',
  TRAINEE: 'Trainee',
  WORKING_STUDENT: 'Working student',
  GRADUATE: 'Graduate',
  ENTRY_LEVEL: 'Entry level',
  JUNIOR: 'Junior',
  MID_LEVEL: 'Mid level',
  SENIOR: 'Senior',
  LEADERSHIP: 'Leadership',
  UNKNOWN: 'Not stated',
};

const WORKFLOW: Record<WorkflowStatus, string> = {
  UNREVIEWED: 'Not reviewed',
  SAVED: 'Saved',
  APPLIED: 'Applied',
  DISMISSED: 'Skipped',
};

const APPLICATION: Record<ApplicationStatus, string> = {
  SAVED: 'Saved',
  APPLIED: 'Applied',
  INTERVIEW: 'Interview',
  OFFER: 'Offer',
  REJECTED: 'Rejected',
  WITHDRAWN: 'Withdrawn',
};

const SOURCES: Record<string, string> = {
  ashby: 'Ashby',
  greenhouse: 'Greenhouse',
  lever: 'Lever',
  recruitee: 'Recruitee',
  smartrecruiters: 'SmartRecruiters',
  workday: 'Workday',
};

export const bandLabel = (band: ScoreBand) => BANDS[band];
export const remoteLabel = (type: RemoteType) => REMOTE[type];
export const seniorityLabel = (level: SeniorityLevel) => SENIORITY[level];
export const workflowLabel = (status: WorkflowStatus) => WORKFLOW[status];
export const applicationLabel = (status: ApplicationStatus) => APPLICATION[status];
export const sourceLabel = (source: string) => SOURCES[source] ?? source;

/** "Today", "3 days ago", "5 weeks ago" — coarse on purpose, posting dates are noisy. */
export function age(iso: string): string {
  const days = Math.floor((Date.now() - Date.parse(iso)) / 86_400_000);
  if (days <= 0) return 'Today';
  if (days === 1) return 'Yesterday';
  if (days < 14) return `${days} days ago`;
  const weeks = Math.floor(days / 7);
  return weeks < 9 ? `${weeks} weeks ago` : `${Math.floor(days / 30)} months ago`;
}

/** Forward-looking counterpart, for follow-up dates. */
export function until(iso: string): string {
  const days = Math.ceil((Date.parse(iso) - Date.now()) / 86_400_000);
  if (days < 0) return `${Math.abs(days)} days overdue`;
  if (days === 0) return 'Today';
  if (days === 1) return 'Tomorrow';
  return `In ${days} days`;
}

export const shortDate = (iso: string) =>
  new Date(iso).toLocaleDateString('en-GB', { day: 'numeric', month: 'short' });

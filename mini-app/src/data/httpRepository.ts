import type {
  Application,
  FailureKind,
  Job,
  JobPilotRepository,
  MutationOutcome,
  RemoteType,
  ScoreBand,
  SeniorityLevel,
  Snapshot,
  WorkflowStatus,
} from './types';
import { JobPilotError } from './types';
import { captureWorkspaceCredential, invalidateAccount } from './account';

/**
 * Talks to the JobPilot Mini App API.
 *
 * Same-origin relative paths only, so the browser sends no cross-origin preflight and the
 * server needs no CORS rule. Owner v1 uses signed launch data; beta v2 uses its memory-only
 * bearer. The transport captures one immutable account generation for all operations.
 */
const TIMEOUT_MS = 10_000;

export function createHttpRepository(): JobPilotRepository {
  const credential = captureWorkspaceCredential();
  const call = (path: string, init?: RequestInit) => request(credential, path, init);
  return {
    async load(): Promise<Snapshot> {
      const body = (await call('/snapshot')) as ApiSnapshot;
      return snapshot(body);
    },

    /** Returns an operation result, never a snapshot: global state comes only from load(). */
    async setWorkflowStatus(
      jobId: number,
      status: WorkflowStatus,
      mutationId: string,
    ): Promise<MutationOutcome> {
      return (await call(`/jobs/${jobId}/workflow`, {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ status, mutationId }),
      })) as MutationOutcome;
    },

    async undo(mutationId: string, undoToken: string): Promise<MutationOutcome> {
      return (await call('/undo', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ mutationId, undoToken }),
      })) as MutationOutcome;
    },
  };
}

async function request(credential: ReturnType<typeof captureWorkspaceCredential>, path: string, init?: RequestInit): Promise<unknown> {
  const auth = credential.headers();

  let response: Response;
  try {
    response = await fetch(credential.base + path, {
      ...init,
      headers: { ...init?.headers, ...auth },
      // The header is the only credential; cookies would add an ambient one.
      credentials: 'omit',
      signal: AbortSignal.timeout(TIMEOUT_MS),
    });
  } catch {
    credential.headers();
    // Network failure or timeout — indistinguishable from here and identical to the user.
    throw new JobPilotError('unavailable');
  }

  credential.headers();
  if (!response.ok) {
    const failure = await failureKind(response);
    credential.headers();
    if (response.status === 401 || response.status === 403) invalidateAccount(failure);
    throw new JobPilotError(failure);
  }
  try {
    const body: unknown = await response.json();
    credential.headers();
    return body;
  } catch {
    credential.headers();
    throw new JobPilotError('unavailable');
  }
}

/** The server's own category decides the message; the status code is the fallback. */
async function failureKind(response: Response): Promise<FailureKind> {
  let category = '';
  try {
    const body = (await response.json()) as { category?: unknown };
    category = typeof body.category === 'string' ? body.category : '';
  } catch {
    category = '';
  }

  switch (category) {
    case 'MINI_APP_DISABLED':
      return 'disabled';
    case 'UNAUTHENTICATED':
      return 'unauthenticated';
    case 'INVALID_AUTH':
      return 'invalid-auth';
    case 'EXPIRED_AUTH':
      return 'expired';
    case 'FORBIDDEN':
      return 'forbidden';
    case 'JOB_NOT_FOUND':
      return 'not-found';
    case 'INVALID_WORKFLOW':
    case 'IDEMPOTENCY_CONFLICT':
      return 'conflict';
    case 'UNDO_STALE':
      return 'undo-stale';
    default:
      break;
  }

  if (response.status === 401) return 'unauthenticated';
  if (response.status === 403) return 'forbidden';
  if (response.status === 404) return 'not-found';
  if (response.status === 409) return 'conflict';
  if (response.status === 503) return 'disabled';
  return 'unavailable';
}

// ---------------------------------------------------------------- wire shape

interface ApiSnapshot {
  reviewQueue: ApiPage<ApiJob>;
  saved: ApiPage<ApiJob>;
  applications: ApiPage<ApiApplication>;
  workflowCounts: Snapshot['workflowCounts'];
  applicationCounts: Snapshot['applicationCounts'];
}

interface ApiPage<T> {
  items: T[];
  total: number;
  limit: number;
  truncated: boolean;
}

interface ApiJob {
  id: number;
  title: string;
  company: string;
  location: string;
  remoteType: string | null;
  seniority: string | null;
  employmentType: string | null;
  score: number;
  band: string | null;
  disposition: 'MATCH' | 'REVIEW';
  workflowStatus: WorkflowStatus;
  source: string;
  publishedAt: string;
  canonicalUrl: string | null;
  strengths: string[];
  risks: string[];
}

interface ApiApplication {
  jobId: number;
  title: string;
  company: string;
  status: Application['status'];
  canonicalUrl: string | null;
  updatedAt: string;
  appliedAt: string | null;
  nextFollowUpDate: string | null;
  job: ApiJob | null;
}

const BANDS: ScoreBand[] = [
  'EXCELLENT_MATCH',
  'GOOD_MATCH',
  'POSSIBLE_MATCH',
  'LOW_MATCH',
  'UNSUITABLE',
];
const REMOTE: RemoteType[] = ['REMOTE', 'HYBRID', 'ONSITE', 'UNKNOWN'];
const SENIORITY: SeniorityLevel[] = [
  'INTERNSHIP',
  'TRAINEE',
  'WORKING_STUDENT',
  'GRADUATE',
  'ENTRY_LEVEL',
  'JUNIOR',
  'MID_LEVEL',
  'SENIOR',
  'LEADERSHIP',
  'UNKNOWN',
];

/** An enum this build does not know about is treated as unstated, never guessed at. */
const oneOf = <T extends string>(allowed: T[], value: string | null, fallback: T): T =>
  allowed.includes(value as T) ? (value as T) : fallback;

function job(row: ApiJob): Job {
  return {
    id: row.id,
    title: row.title,
    company: row.company,
    location: row.location,
    remoteType: oneOf(REMOTE, row.remoteType, 'UNKNOWN'),
    seniority: oneOf(SENIORITY, row.seniority, 'UNKNOWN'),
    employmentType: row.employmentType,
    score: row.score,
    band: BANDS.includes(row.band as ScoreBand) ? (row.band as ScoreBand) : null,
    disposition: row.disposition,
    workflowStatus: row.workflowStatus,
    source: row.source,
    publishedAt: row.publishedAt,
    canonicalUrl: row.canonicalUrl,
    // Absent by design — see the field comments on Job.
    matchSummary: null,
    strengths: row.strengths,
    risks: row.risks,
    requirements: [],
    activity: [],
  };
}

/** Applications carry no score of their own; it comes from the vacancy when still queued. */
function application(row: ApiApplication, jobs: Job[]): Application {
  return {
    jobId: row.jobId,
    title: row.title,
    company: row.company,
    status: row.status,
    canonicalUrl: row.canonicalUrl,
    score: jobs.find((entry) => entry.id === row.jobId)?.score ?? null,
    updatedAt: row.updatedAt,
    appliedAt: row.appliedAt,
    nextFollowUpDate: row.nextFollowUpDate,
  };
}

function snapshot(body: ApiSnapshot): Snapshot {
  const review = body.reviewQueue.items.map(job);
  const saved = body.saved.items.map(job);
  const applicationJobs = body.applications.items.flatMap((entry) =>
    entry.job ? [job(entry.job)] : [],
  );
  const jobs = [...review, ...saved, ...applicationJobs];
  return {
    reviewQueue: { ...body.reviewQueue, items: review },
    saved: { ...body.saved, items: saved },
    applications: {
      ...body.applications,
      items: body.applications.items.map((entry) => application(entry, jobs)),
    },
    applicationJobs,
    workflowCounts: body.workflowCounts,
    applicationCounts: body.applicationCounts,
  };
}

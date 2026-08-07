import type { JobPilotRepository, Snapshot, WorkflowStatus } from './types';
import { JobPilotError } from './types';
import { snapshot } from './sample';

let current: Snapshot | null = null;

/**
 * In-memory stand-in for the Spring backend. Latency is deliberate and small: enough
 * to make the loading state real, short enough that review never waits on it.
 *
 * `?mock=fail` makes load() reject and `?mock=fail-write` makes mutations reject, so both
 * error paths are reachable without a backend.
 */
export const mockRepository: JobPilotRepository = {
  async load(): Promise<Snapshot> {
    await delay(420);
    if (flag() === 'fail') throw new JobPilotError('unavailable');
    current = snapshot();
    return structuredClone(current);
  },

  async setWorkflowStatus(jobId: number, status: WorkflowStatus): Promise<Snapshot> {
    await delay(120);
    if (flag() === 'fail-write') throw new JobPilotError('conflict');
    current = mutate(current ?? snapshot(), jobId, status);
    return structuredClone(current);
  },
};

function mutate(previous: Snapshot, jobId: number, status: WorkflowStatus): Snapshot {
  const next = structuredClone(previous);
  const source = [...next.reviewQueue.items, ...next.saved.items]
    .find((job) => job.id === jobId);
  if (!source) return next;

  next.reviewQueue.items = next.reviewQueue.items.filter((job) => job.id !== jobId);
  next.saved.items = next.saved.items.filter((job) => job.id !== jobId);
  source.workflowStatus = status;
  if (status === 'UNREVIEWED') next.reviewQueue.items.push(source);
  if (status === 'SAVED') next.saved.items.unshift(source);

  const existing = next.applications.items.find((application) => application.jobId === jobId);
  if (status === 'SAVED' || status === 'APPLIED') {
    const now = new Date().toISOString();
    const application = {
      jobId: source.id,
      title: source.title,
      company: source.company,
      status,
      canonicalUrl: source.canonicalUrl,
      score: source.score,
      updatedAt: now,
      appliedAt: status === 'APPLIED' ? (existing?.appliedAt ?? now) : null,
      nextFollowUpDate: existing?.nextFollowUpDate ?? null,
    } as const;
    next.applications.items = existing
      ? next.applications.items.map((entry) => entry.jobId === jobId ? application : entry)
      : [application, ...next.applications.items];
    if (!next.applicationJobs.some((job) => job.id === source.id)) {
      next.applicationJobs.push(source);
    }
  }
  refreshTotals(next);
  return next;
}

function refreshTotals(next: Snapshot) {
  const countWorkflow = (status: WorkflowStatus) =>
    [...next.reviewQueue.items, ...next.saved.items]
      .filter((job) => job.workflowStatus === status).length;
  next.reviewQueue.total = next.reviewQueue.items.length;
  next.saved.total = next.saved.items.length;
  next.workflowCounts.unreviewedMatch = next.reviewQueue.items
    .filter((job) => job.disposition === 'MATCH').length;
  next.workflowCounts.unreviewedReview = next.reviewQueue.items
    .filter((job) => job.disposition === 'REVIEW').length;
  next.workflowCounts.saved = countWorkflow('SAVED');
  next.workflowCounts.applied = countWorkflow('APPLIED');
  next.workflowCounts.dismissed = countWorkflow('DISMISSED');
  next.applications.total = next.applications.items.length;
  const countApplication = (status: import('./types').ApplicationStatus) =>
    next.applications.items.filter((application) => application.status === status).length;
  next.applicationCounts = {
    total: next.applications.items.length,
    saved: countApplication('SAVED'),
    applied: countApplication('APPLIED'),
    interview: countApplication('INTERVIEW'),
    offer: countApplication('OFFER'),
    rejected: countApplication('REJECTED'),
    withdrawn: countApplication('WITHDRAWN'),
  };
}

const flag = () => new URLSearchParams(location.search).get('mock');
const delay = (ms: number) => new Promise((resolve) => setTimeout(resolve, ms));

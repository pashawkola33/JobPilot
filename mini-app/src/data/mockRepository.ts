import type {
  JobPilotRepository,
  MutationOutcome,
  Snapshot,
  WorkflowStatus,
} from './types';
import { JobPilotError } from './types';
import { snapshot } from './sample';

let current: Snapshot | null = null;

/**
 * In-memory stand-in for the Spring backend. Latency is deliberate and small: enough
 * to make the loading state real, short enough that review never waits on it.
 *
 * `?mock=fail` makes load() reject and `?mock=fail-write` makes mutations reject, so both
 * error paths are reachable without a backend.
 *
 * It models the ledger, not just the data. Mutations return an operation result rather than a
 * snapshot, a repeated mutation id replays instead of re-executing, and undo is a server
 * operation addressed by token — because a mock that answered differently from the real API
 * would make every mock-mode spec a test of the mock.
 */
export const mockRepository: JobPilotRepository = {
  async load(): Promise<Snapshot> {
    await delay(420);
    if (flag() === 'fail') throw new JobPilotError('unavailable');
    // Seeded once, then durable for the session. Reconciliation reads run constantly now, and
    // re-seeding here would silently erase every mutation the session had made.
    current ??= snapshot();
    return structuredClone(current);
  },

  async setWorkflowStatus(
    jobId: number,
    status: WorkflowStatus,
    mutationId: string,
  ): Promise<MutationOutcome> {
    await delay(120);
    if (flag() === 'fail-write') throw new JobPilotError('conflict');

    const replay = ledger.get(mutationId);
    if (replay) {
      if (replay.jobId !== jobId || replay.status !== status) {
        throw new JobPilotError('conflict');
      }
      return { ...replay.outcome, replayed: true, undoToken: liveToken(replay) };
    }

    const before = current ?? snapshot();
    const previous = [...before.reviewQueue.items, ...before.saved.items]
      .find((job) => job.id === jobId)?.workflowStatus ?? 'UNREVIEWED';
    current = mutate(before, jobId, status);
    revision += 1;

    // A newer mutation on the same job retires every older reversal capability on it.
    for (const entry of ledger.values()) {
      if (entry.jobId === jobId) entry.superseded = true;
    }
    const outcome: MutationOutcome = {
      mutationId,
      mutationRevision: revision,
      replayed: false,
      jobId,
      status,
      changed: previous !== status,
      applicationStatus:
        current.applications.items.find((entry) => entry.jobId === jobId)?.status ?? null,
      undoToken: previous === status ? null : `undo-${mutationId}`,
    };
    ledger.set(mutationId, { jobId, status, previous, outcome, superseded: false });
    return outcome;
  },

  async undo(mutationId: string, undoToken: string): Promise<MutationOutcome> {
    await delay(120);
    const replay = reversals.get(mutationId);
    if (replay) return { ...replay, replayed: true };

    const entry = [...ledger.values()].find((row) => row.outcome.undoToken === undoToken);
    // Unknown, superseded and already-reversed are one answer: it cannot be undone now.
    if (!entry || entry.superseded || entry.reversed) throw new JobPilotError('undo-stale');

    current = mutate(current ?? snapshot(), entry.jobId, entry.previous);
    revision += 1;
    entry.reversed = true;

    const outcome: MutationOutcome = {
      mutationId,
      mutationRevision: revision,
      replayed: false,
      jobId: entry.jobId,
      status: entry.previous,
      changed: true,
      applicationStatus:
        current.applications.items.find((item) => item.jobId === entry.jobId)?.status ?? null,
      // A reversal is never itself reversible.
      undoToken: null,
    };
    reversals.set(mutationId, outcome);
    return outcome;
  },
};

interface LedgerEntry {
  jobId: number;
  status: WorkflowStatus;
  previous: WorkflowStatus;
  outcome: MutationOutcome;
  superseded: boolean;
  reversed?: boolean;
}

let revision = 0;
const ledger = new Map<string, LedgerEntry>();
const reversals = new Map<string, MutationOutcome>();

/** Liveness is read now, so a replay never re-arms a capability a later mutation retired. */
const liveToken = (entry: LedgerEntry): string | null =>
  entry.superseded || entry.reversed ? null : entry.outcome.undoToken;

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

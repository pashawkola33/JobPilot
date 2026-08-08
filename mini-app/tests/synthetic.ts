import { createHmac } from 'node:crypto';
import type { Browser, BrowserContext, Page, Route } from '@playwright/test';

const SYNTHETIC_BOT_TOKEN = '000000:durability-test-only';

const SYNTHETIC_INIT_DATA = signedInitData({
  auth_date: '1767182400',
  query_id: 'AAF_durability',
  user: JSON.stringify({ id: 4242 }),
});

function signedInitData(fields: Record<string, string>): string {
  const dataCheckString = Object.entries(fields)
    .sort(([left], [right]) => left.localeCompare(right))
    .map(([key, value]) => `${key}=${value}`)
    .join('\n');
  const secret = createHmac('sha256', 'WebAppData').update(SYNTHETIC_BOT_TOKEN).digest();
  const hash = createHmac('sha256', secret).update(dataCheckString).digest('hex');
  return new URLSearchParams({ ...fields, hash }).toString();
}

export type WorkflowStatus = 'UNREVIEWED' | 'SAVED' | 'APPLIED' | 'DISMISSED';

export interface ServerJob {
  id: number;
  title: string;
  score: number;
  status: WorkflowStatus;
}

export interface ServerApplication {
  jobId: number;
  status: 'SAVED' | 'APPLIED';
  updatedAt: string;
  appliedAt: string | null;
}

export interface LedgerEntry {
  jobId: number;
  status: WorkflowStatus;
  previous: WorkflowStatus;
  outcome: MutationOutcome;
  superseded: boolean;
  reversed: boolean;
  /** The history this mutation appended, so a reversal removes that row and no other. */
  createdHistory: boolean;
  createdApplication: boolean;
}

export interface MutationOutcome {
  mutationId: string;
  mutationRevision: number;
  replayed: boolean;
  jobId: number;
  status: WorkflowStatus;
  changed: boolean;
  applicationStatus: 'SAVED' | 'APPLIED' | null;
  undoToken: string | null;
}

/** Resolved from outside, so a test controls exactly when a held request completes. */
export interface Deferred {
  release(): void;
  arrived: Promise<void>;
}

/**
 * A synthetic JobPilot API with the P0-B contract: mutations return an operation result rather
 * than a snapshot, a repeated mutation id replays instead of re-executing, and undo is a server
 * operation addressed by an opaque token.
 *
 * It also counts history rows, because "exactly one history row from two deliveries" is the
 * assertion that catches a client which quietly mutates twice.
 */
export class SyntheticServer {
  readonly jobs: ServerJob[];
  readonly applications = new Map<number, ServerApplication>();
  /** One entry per applied status change, so duplicates are countable rather than assumed. */
  readonly history: { jobId: number; status: WorkflowStatus }[] = [];
  readonly ledger = new Map<string, LedgerEntry>();
  readonly reversals = new Map<string, MutationOutcome>();
  revision = 0;
  /** Every workflow/undo request the server actually processed, in order. */
  readonly writes: { mutationId: string; jobId: number; kind: 'workflow' | 'undo' }[] = [];
  /**
   * Every delivery that *arrived*, including ones deliberately dropped before they applied.
   * Recovery is about identity, so the test has to see the ids on the wire, not just the ids
   * that survived to commit.
   */
  readonly deliveries: { mutationId: string; kind: 'workflow' | 'undo' }[] = [];
  snapshotReads = 0;

  private rejectNextWrite: { status: number; category: string } | null = null;
  private dropWrites = 0;
  private dropWritesAfterApplying = 0;
  private heldWrite: (() => void) | null = null;
  private heldWriteArrived: (() => void) | null = null;
  private heldSnapshot: (() => void) | null = null;
  private heldSnapshotArrived: (() => void) | null = null;
  private failSnapshots = 0;

  constructor(jobs: ServerJob[]) {
    this.jobs = jobs;
  }

  rejectNext(category = 'INVALID_WORKFLOW', status = 409) {
    this.rejectNextWrite = { status, category };
  }

  /** The next `count` writes fail at the transport, having changed nothing. */
  dropNextWrites(count = 1) {
    this.dropWrites = count;
  }

  /**
   * The next write is applied and recorded, then the connection dies before the reply lands —
   * the ambiguous case a recovery read cannot resolve.
   */
  dropNextResponseAfterApplying(count = 1) {
    this.dropWritesAfterApplying = count;
  }

  /** The next `count` snapshot reads fail at the transport. */
  failNextSnapshots(count = 1) {
    this.failSnapshots = count;
  }

  /** Holds the next write open until released, so a test can act while it is in flight. */
  holdNextWrite(): Deferred {
    let arrived: () => void;
    const arrivedPromise = new Promise<void>((resolve) => { arrived = resolve; });
    this.heldWriteArrived = arrived!;
    return {
      arrived: arrivedPromise,
      release: () => {
        const release = this.heldWrite;
        this.heldWrite = null;
        this.heldWriteArrived = null;
        release?.();
      },
    };
  }

  holdNextSnapshot(): Deferred {
    let arrived: () => void;
    const arrivedPromise = new Promise<void>((resolve) => { arrived = resolve; });
    this.heldSnapshotArrived = arrived!;
    return {
      arrived: arrivedPromise,
      release: () => {
        const release = this.heldSnapshot;
        this.heldSnapshot = null;
        this.heldSnapshotArrived = null;
        release?.();
      },
    };
  }

  /**
   * An out-of-band writer: the Telegram bot or the applications API. It changes what the
   * snapshot shows and deliberately does NOT advance the Mini App revision, which is exactly
   * why a client must not order reads by that number.
   */
  externalTransition(jobId: number, status: WorkflowStatus) {
    const job = this.jobs.find((candidate) => candidate.id === jobId);
    if (!job) throw new Error(`no such job ${jobId}`);
    job.status = status;
    this.history.push({ jobId, status });
    if (status === 'SAVED' || status === 'APPLIED') {
      this.applications.set(jobId, {
        jobId,
        status,
        updatedAt: new Date().toISOString(),
        appliedAt: status === 'APPLIED' ? new Date().toISOString() : null,
      });
    }
    // Every recorded mutation for this job is now stale: the durable state moved underneath it.
    for (const entry of this.ledger.values()) {
      if (entry.jobId === jobId) entry.superseded = true;
    }
  }

  /** Ingestion-equivalent: a new Review vacancy, with no Mini App revision advanced. */
  ingest(job: ServerJob) {
    this.jobs.push(job);
  }

  async route(route: Route) {
    const request = route.request();
    if (request.url().endsWith('/snapshot')) {
      if (this.heldSnapshotArrived) {
        const arrived = this.heldSnapshotArrived;
        this.heldSnapshotArrived = null;
        await new Promise<void>((resolve) => { this.heldSnapshot = resolve; arrived(); });
      }
      if (this.failSnapshots > 0) {
        this.failSnapshots -= 1;
        return route.abort('connectionreset');
      }
      this.snapshotReads += 1;
      return this.reply(route, this.snapshot());
    }
    if (request.url().endsWith('/undo')) return this.undo(route);

    const match = request.url().match(/\/jobs\/(\d+)\/workflow$/);
    if (!match) return this.reply(route, { category: 'JOB_NOT_FOUND' }, 404);
    return this.workflow(route, Number(match[1]));
  }

  private async workflow(route: Route, jobId: number) {
    const body = route.request().postDataJSON() as { status: WorkflowStatus; mutationId: string };
    this.deliveries.push({ mutationId: body.mutationId, kind: 'workflow' });

    if (this.heldWriteArrived) {
      const arrived = this.heldWriteArrived;
      this.heldWriteArrived = null;
      await new Promise<void>((resolve) => { this.heldWrite = resolve; arrived(); });
    }
    if (this.dropWrites > 0) {
      this.dropWrites -= 1;
      // Nothing applied: the server never saw this as a decision.
      return route.abort('connectionreset');
    }

    const job = this.jobs.find((candidate) => candidate.id === jobId);
    if (!job) return this.reply(route, { category: 'JOB_NOT_FOUND' }, 404);
    if (this.rejectNextWrite) {
      const refusal = this.rejectNextWrite;
      this.rejectNextWrite = null;
      // Deliberately leaves server state untouched, so a client that keeps its optimistic
      // edit is measurably out of step with the snapshot.
      return this.reply(route, { category: refusal.category, message: 'Refused' }, refusal.status);
    }

    const replay = this.ledger.get(body.mutationId);
    if (replay) {
      if (replay.jobId !== jobId || replay.status !== body.status) {
        return this.reply(route, { category: 'IDEMPOTENCY_CONFLICT' }, 409);
      }
      // No second write, no second history row, and no re-armed stale capability.
      return this.reply(route, {
        ...replay.outcome,
        replayed: true,
        undoToken: replay.superseded || replay.reversed ? null : replay.outcome.undoToken,
      });
    }

    const outcome = this.apply(jobId, body.status, body.mutationId);
    this.writes.push({ mutationId: body.mutationId, jobId, kind: 'workflow' });
    if (this.dropWritesAfterApplying > 0) {
      this.dropWritesAfterApplying -= 1;
      // Committed, then the response was lost. Only the mutation id can resolve this.
      return route.abort('connectionreset');
    }
    return this.reply(route, outcome);
  }

  private apply(jobId: number, status: WorkflowStatus, mutationId: string): MutationOutcome {
    const job = this.jobs.find((candidate) => candidate.id === jobId)!;
    const previous = job.status;
    const changed = previous !== status;
    const hadApplication = this.applications.has(jobId);
    job.status = status;

    let createdHistory = false;
    if (status === 'SAVED' || status === 'APPLIED') {
      const existing = this.applications.get(jobId);
      if (!existing || existing.status !== status) {
        this.history.push({ jobId, status });
        createdHistory = true;
      }
      this.applications.set(jobId, {
        jobId,
        status,
        updatedAt: new Date().toISOString(),
        appliedAt: status === 'APPLIED' ? (existing?.appliedAt ?? new Date().toISOString()) : null,
      });
    }

    this.revision += 1;
    for (const entry of this.ledger.values()) {
      if (entry.jobId === jobId) entry.superseded = true;
    }
    const outcome: MutationOutcome = {
      mutationId,
      mutationRevision: this.revision,
      replayed: false,
      jobId,
      status,
      changed,
      applicationStatus: this.applications.get(jobId)?.status ?? null,
      undoToken: changed ? `undo-${mutationId}` : null,
    };
    this.ledger.set(mutationId, {
      jobId,
      status,
      previous,
      outcome,
      superseded: false,
      reversed: false,
      createdHistory,
      createdApplication: !hadApplication && this.applications.has(jobId),
    });
    return outcome;
  }

  private async undo(route: Route) {
    const body = route.request().postDataJSON() as { mutationId: string; undoToken: string };
    this.deliveries.push({ mutationId: body.mutationId, kind: 'undo' });

    if (this.heldWriteArrived) {
      const arrived = this.heldWriteArrived;
      this.heldWriteArrived = null;
      await new Promise<void>((resolve) => { this.heldWrite = resolve; arrived(); });
    }
    if (this.dropWrites > 0) {
      this.dropWrites -= 1;
      return route.abort('connectionreset');
    }

    const replayed = this.reversals.get(body.mutationId);
    if (replayed) return this.reply(route, { ...replayed, replayed: true });

    const entry = [...this.ledger.values()]
      .find((row) => row.outcome.undoToken === body.undoToken);
    // Unknown, superseded and already-reversed are one answer: it cannot be undone now.
    if (!entry || entry.superseded || entry.reversed) {
      return this.reply(route, { category: 'UNDO_STALE', message: 'Too late' }, 409);
    }

    const job = this.jobs.find((candidate) => candidate.id === entry.jobId)!;
    job.status = entry.previous;
    if (entry.createdHistory) {
      // Removes the row this mutation appended, and no other.
      const index = this.history.findIndex(
        (row) => row.jobId === entry.jobId && row.status === entry.status,
      );
      if (index >= 0) this.history.splice(index, 1);
    }
    if (entry.createdApplication) this.applications.delete(entry.jobId);
    entry.reversed = true;
    this.revision += 1;
    this.writes.push({ mutationId: body.mutationId, jobId: entry.jobId, kind: 'undo' });

    const outcome: MutationOutcome = {
      mutationId: body.mutationId,
      mutationRevision: this.revision,
      replayed: false,
      jobId: entry.jobId,
      status: entry.previous,
      changed: true,
      applicationStatus: this.applications.get(entry.jobId)?.status ?? null,
      undoToken: null,
    };
    this.reversals.set(body.mutationId, outcome);
    return this.reply(route, outcome);
  }

  historyFor(jobId: number) {
    return this.history.filter((row) => row.jobId === jobId);
  }

  snapshot() {
    const review = this.jobs.filter((job) => job.status === 'UNREVIEWED');
    const saved = this.jobs.filter((job) => job.status === 'SAVED');
    const applicationItems = [...this.applications.values()].slice(0, 20).map((application) => {
      const job = this.jobs.find((candidate) => candidate.id === application.jobId)!;
      return {
        jobId: application.jobId,
        title: job.title,
        company: 'Synthetic Company',
        status: application.status,
        canonicalUrl: `https://example.test/jobs/${job.id}`,
        updatedAt: application.updatedAt,
        appliedAt: application.appliedAt,
        nextFollowUpDate: null,
        job: apiJob(job),
      };
    });
    const applicationCounts = {
      total: this.applications.size,
      saved: [...this.applications.values()].filter((value) => value.status === 'SAVED').length,
      applied: [...this.applications.values()].filter((value) => value.status === 'APPLIED').length,
      interview: 0,
      offer: 0,
      rejected: 0,
      withdrawn: 0,
    };
    return {
      // An as-of marker only. Out-of-band writers change everything below without moving it,
      // so a client that ordered reads by this number would drop real changes.
      mutationRevision: this.revision,
      reviewQueue: page(review.slice(0, 50).map(apiJob), review.length, 50),
      saved: page(saved.slice(0, 50).map(apiJob), saved.length, 50),
      applications: page(applicationItems, this.applications.size, 20),
      workflowCounts: {
        unreviewedMatch: review.length,
        unreviewedReview: 0,
        saved: saved.length,
        applied: this.jobs.filter((job) => job.status === 'APPLIED').length,
        dismissed: this.jobs.filter((job) => job.status === 'DISMISSED').length,
      },
      applicationCounts,
    };
  }

  private reply(route: Route, body: unknown, status = 200) {
    return route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body) });
  }
}

const page = <T>(items: T[], total: number, limit: number) => ({
  items,
  total,
  limit,
  truncated: total > items.length,
});

const apiJob = (job: ServerJob) => ({
  id: job.id,
  title: job.title,
  company: 'Synthetic Company',
  location: 'Bucharest, Romania',
  remoteType: 'HYBRID',
  seniority: 'JUNIOR',
  employmentType: 'Full-time',
  score: job.score,
  band: 'GOOD_MATCH',
  disposition: 'MATCH',
  workflowStatus: job.status,
  source: 'synthetic',
  publishedAt: '2026-08-01T12:00:00Z',
  canonicalUrl: `https://example.test/jobs/${job.id}`,
  strengths: ['Synthetic strength'],
  risks: [],
});

export async function context(browser: Browser, server: SyntheticServer): Promise<BrowserContext> {
  const result = await browser.newContext();
  await result.addInitScript((initData) => {
    (window as unknown as { Telegram: unknown }).Telegram = {
      WebApp: {
        initData,
        colorScheme: 'light',
        ready() {},
        expand() {},
        openLink() {},
        onEvent() {},
        offEvent() {},
        BackButton: { show() {}, hide() {}, onClick() {}, offClick() {} },
      },
    };
  }, SYNTHETIC_INIT_DATA);
  await result.route('**/api/mini-app/v1/**', (route) => server.route(route));
  return result;
}

export const review = (page: Page) =>
  page.getByRole('button', { name: /^Review(,|$)/ });

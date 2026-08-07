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

export class SyntheticServer {
  readonly jobs: ServerJob[];
  readonly applications = new Map<number, ServerApplication>();
  /** Refuses the next workflow write without applying it, the way a 409 from the API would. */
  private rejectNextWrite: { status: number; category: string } | null = null;

  constructor(jobs: ServerJob[]) {
    this.jobs = jobs;
  }

  rejectNext(category = 'INVALID_WORKFLOW', status = 409) {
    this.rejectNextWrite = { status, category };
  }

  async route(route: Route) {
    const request = route.request();
    if (request.url().endsWith('/snapshot')) return this.reply(route, this.snapshot());

    const match = request.url().match(/\/jobs\/(\d+)\/workflow$/);
    if (!match) return this.reply(route, { category: 'JOB_NOT_FOUND' }, 404);
    const job = this.jobs.find((candidate) => candidate.id === Number(match[1]));
    if (!job) return this.reply(route, { category: 'JOB_NOT_FOUND' }, 404);
    if (this.rejectNextWrite) {
      const refusal = this.rejectNextWrite;
      this.rejectNextWrite = null;
      // Deliberately leaves server state untouched, so a client that keeps its optimistic
      // edit is measurably out of step with the snapshot.
      return this.reply(route, { category: refusal.category, message: 'Refused' }, refusal.status);
    }
    const body = request.postDataJSON() as { status: WorkflowStatus };
    const changed = job.status !== body.status;
    job.status = body.status;
    if (body.status === 'SAVED' || body.status === 'APPLIED') {
      const previous = this.applications.get(job.id);
      this.applications.set(job.id, {
        jobId: job.id,
        status: body.status,
        updatedAt: new Date().toISOString(),
        appliedAt:
          body.status === 'APPLIED' ? (previous?.appliedAt ?? new Date().toISOString()) : null,
      });
    }
    return this.reply(route, {
      jobId: job.id,
      status: job.status,
      changed,
      updatedAt: new Date().toISOString(),
      snapshot: this.snapshot(),
    });
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

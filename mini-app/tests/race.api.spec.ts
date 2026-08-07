import { expect, test, type Page } from '@playwright/test';
import { SyntheticServer, context, review } from './synthetic';

/**
 * The P0-B race contract, in the browser.
 *
 * Every race is forced with a held request or a deliberately dropped response — never a sleep.
 * A race test that passes because a request happened to be slow proves nothing and fails on
 * someone else's machine.
 */

const queue = (count: number) =>
  Array.from({ length: count }, (_, index) => ({
    id: 8000 + index,
    title: `Race vacancy ${index + 1}`,
    score: 90 - index,
    status: 'UNREVIEWED' as const,
  }));

const toast = (page: Page) => page.getByRole('status');
const undoButton = (page: Page) => toast(page).getByRole('button', { name: 'Undo' });
const actions = (page: Page) => page.locator('.actions');

async function openReview(page: Page) {
  await page.goto('/');
  await review(page).click();
}

// ------------------------------------------------------------------- same-job ordering

/**
 * The one same-job sequence the review UI can actually produce: a decision advances the card,
 * and only Undo brings the reviewer back to the same vacancy. Three writes on one job, in issue
 * order, must settle on the last one.
 *
 * Pure ordering under concurrency is proven in consistency.unit.spec.ts, against the queue
 * itself — the UI cannot issue two concurrent writes for one job to begin with.
 */
test('Save, Undo then Apply on one job settle in issue order', async ({ browser }) => {
  const server = new SyntheticServer(queue(3));
  const page = await (await context(browser, server)).newPage();
  await openReview(page);

  await actions(page).getByRole('button', { name: 'Save' }).click();
  await expect.poll(() => server.jobs[0]!.status).toBe('SAVED');

  // Undo returns the reviewer to the same vacancy and reverses the Save on the server.
  await undoButton(page).click();
  await expect.poll(() => server.jobs[0]!.status).toBe('UNREVIEWED');
  await expect(page.getByRole('heading', { name: 'Race vacancy 1' })).toBeVisible();

  await actions(page).getByRole('button', { name: 'Applied' }).click();

  await expect.poll(() => server.jobs[0]!.status).toBe('APPLIED');
  // Every write in this test was for the same job, and the last one is what stands.
  expect(server.writes.map((write) => write.jobId)).toEqual([8000, 8000, 8000]);
  expect(server.applications.get(8000)?.status).toBe('APPLIED');
  expect(server.historyFor(8000)).toHaveLength(1);
});

test('a slow write on one job never blocks a different job', async ({ browser }) => {
  const server = new SyntheticServer(queue(3));
  const page = await (await context(browser, server)).newPage();
  await openReview(page);

  const held = server.holdNextWrite();
  await actions(page).getByRole('button', { name: 'Save' }).click();
  await held.arrived;

  // A different vacancy must proceed to completion while the first is still open (I2).
  await actions(page).getByRole('button', { name: 'Skip' }).click();
  await expect.poll(() => server.jobs[1]!.status).toBe('DISMISSED');
  expect(server.jobs[0]!.status).toBe('UNREVIEWED');

  held.release();
  await expect.poll(() => server.jobs[0]!.status).toBe('SAVED');
});

// --------------------------------------------------------- ambiguous timeout recovery

test('a committed mutation whose response was lost is resolved by its own id, once', async ({
  browser,
}) => {
  const server = new SyntheticServer(queue(2));
  const page = await (await context(browser, server)).newPage();
  await openReview(page);

  // Applied server-side, then the connection dies. A recovery GET could not tell whether this
  // committed; only re-sending the same mutation id can.
  server.dropNextResponseAfterApplying();
  await actions(page).getByRole('button', { name: 'Save' }).click();

  await expect.poll(() => server.jobs[0]!.status).toBe('SAVED');
  // Two deliveries, one logical mutation: one ledger entry, one history row, one application.
  await expect.poll(() => server.ledger.size).toBe(1);
  expect(server.historyFor(8000)).toHaveLength(1);
  expect(server.applications.size).toBe(1);
  // Both deliveries carried the same id, so the second was a replay and not a new decision.
  const ids = new Set(server.writes.map((write) => write.mutationId));
  expect(ids.size).toBe(1);
  // The client is not left claiming an unknown state.
  await expect(page.getByRole('alert')).toHaveCount(0);
  await expect(undoButton(page)).toBeVisible();
});

test('a mutation that never landed is executed exactly once by its retry', async ({ browser }) => {
  const server = new SyntheticServer(queue(2));
  const page = await (await context(browser, server)).newPage();
  await openReview(page);

  // The first delivery dies before the server applies anything, so the retry is a genuine
  // first execution rather than a replay.
  server.dropNextWrites(1);
  await actions(page).getByRole('button', { name: 'Save' }).click();

  await expect.poll(() => server.jobs[0]!.status).toBe('SAVED');
  expect(server.ledger.size).toBe(1);
  expect(server.historyFor(8000)).toHaveLength(1);
  await expect(page.getByRole('alert')).toHaveCount(0);
});

test('when the mutation and its resolution both fail the job says so and refuses more writes', async ({
  browser,
}) => {
  const server = new SyntheticServer(queue(3));
  const page = await (await context(browser, server)).newPage();
  await openReview(page);

  // Both the original and its same-id resolution die: nothing about this job is known.
  server.dropNextWrites(2);
  await actions(page).getByRole('button', { name: 'Save' }).click();

  // Neither the optimistic state nor a rollback is presented as confirmed (I9).
  await expect(page.getByRole('alert')).toContainText('Not sure this saved');
  expect(server.jobs[0]!.status).toBe('UNREVIEWED');
  expect(server.ledger.size).toBe(0);

  // That job accepts no further writes ...
  await page.getByRole('button', { name: /^Review/ }).click();
  const before = server.writes.length;
  await actions(page).getByRole('button', { name: 'Applied' }).click();
  await expect.poll(() => server.writes.length).toBe(before);

  // ... and the deterministic retry clears it once the server answers again.
  await page.getByRole('button', { name: 'Check again' }).click();
  await expect(page.getByRole('alert')).toHaveCount(0);
});

test('an unrelated job stays usable while another is unknown', async ({ browser }) => {
  const server = new SyntheticServer(queue(3));
  const page = await (await context(browser, server)).newPage();
  await openReview(page);

  server.dropNextWrites(2);
  await actions(page).getByRole('button', { name: 'Save' }).click();
  await expect(page.getByRole('alert')).toContainText('Not sure this saved');

  // The reviewer is held on the unresolved vacancy, but moving on is not blocked — only
  // writing to *that* job is. The next vacancy is a different job and must work (I2 + I9).
  await page.getByRole('button', { name: 'Next vacancy without deciding' }).click();
  await expect(page.getByRole('heading', { name: 'Race vacancy 2' })).toBeVisible();
  await actions(page).getByRole('button', { name: 'Skip' }).click();

  await expect.poll(() => server.jobs[1]!.status).toBe('DISMISSED');
  // And the unresolved job is still unresolved, not quietly assumed either way.
  expect(server.jobs[0]!.status).toBe('UNREVIEWED');
});

// ------------------------------------------------------------------ idempotency replay

test('a replayed mutation never re-arms an Undo a later action retired', async ({ browser }) => {
  const server = new SyntheticServer(queue(3));
  const page = await (await context(browser, server)).newPage();
  await openReview(page);

  await actions(page).getByRole('button', { name: 'Save' }).click();
  await expect.poll(() => server.jobs[0]!.status).toBe('SAVED');
  const first = [...server.ledger.values()][0]!;

  // The bot moves the same vacancy on, retiring that capability without touching the revision.
  server.externalTransition(8000, 'APPLIED');

  // Replaying the first mutation must report its own identity without resurrecting its undo.
  const replay = await page.evaluate(
    async ([id, token]) => {
      const response = await fetch(`/api/mini-app/v1/jobs/8000/workflow`, {
        method: 'PUT',
        headers: {
          'Content-Type': 'application/json',
          'X-Telegram-Init-Data': (window as unknown as {
            Telegram: { WebApp: { initData: string } };
          }).Telegram.WebApp.initData,
        },
        body: JSON.stringify({ status: 'SAVED', mutationId: id }),
      });
      return { status: response.status, body: await response.json(), token };
    },
    [first.outcome.mutationId, first.outcome.undoToken] as const,
  );

  expect(replay.status).toBe(200);
  expect(replay.body.replayed).toBe(true);
  expect(replay.body.mutationRevision).toBe(first.outcome.mutationRevision);
  // The capability is read live, so a replay cannot resurrect one a later writer retired.
  expect(replay.body.undoToken).toBeNull();
  // And the newer state stands: the replay regressed nothing and wrote nothing.
  expect(server.jobs[0]!.status).toBe('APPLIED');
  expect(server.ledger.size).toBe(1);
  expect(server.historyFor(8000)).toHaveLength(2);
});

test('the same mutation id with a different payload is refused', async ({ browser }) => {
  const server = new SyntheticServer(queue(2));
  const page = await (await context(browser, server)).newPage();
  await openReview(page);
  await actions(page).getByRole('button', { name: 'Save' }).click();
  await expect.poll(() => server.ledger.size).toBe(1);
  const original = [...server.ledger.values()][0]!;

  const conflict = await page.evaluate(async (id) => {
    const response = await fetch('/api/mini-app/v1/jobs/8000/workflow', {
      method: 'PUT',
      headers: {
        'Content-Type': 'application/json',
        'X-Telegram-Init-Data': (window as unknown as {
          Telegram: { WebApp: { initData: string } };
        }).Telegram.WebApp.initData,
      },
      body: JSON.stringify({ status: 'DISMISSED', mutationId: id }),
    });
    return { status: response.status, body: await response.json() };
  }, original.outcome.mutationId);

  expect(conflict.status).toBe(409);
  expect(conflict.body.category).toBe('IDEMPOTENCY_CONFLICT');
  expect(server.jobs[0]!.status).toBe('SAVED');
  expect(server.ledger.size).toBe(1);
});

// ------------------------------------------------------------------ read reconciliation

test('an out-of-band change is picked up even though the revision did not move', async ({
  browser,
}) => {
  const server = new SyntheticServer(queue(2));
  const page = await (await context(browser, server)).newPage();
  await page.goto('/');
  await expect(page.getByRole('button', { name: 'Review, 2 waiting' })).toBeVisible();
  const revisionBefore = server.revision;

  // The bot tracks a vacancy. No Mini App revision moves, so a client that ordered reads by
  // revision would discard this entirely.
  server.externalTransition(8001, 'SAVED');
  expect(server.revision).toBe(revisionBefore);

  // Any settled mutation requests reconciliation, which is where global state comes from.
  await review(page).click();
  await actions(page).getByRole('button', { name: 'Skip' }).click();

  await page.getByRole('button', { name: 'Saved', exact: true }).click();
  await expect(page.getByRole('button', { name: /Race vacancy 2/ })).toBeVisible();
});

test('an out-of-band change survives a later mutation carrying a higher revision', async ({
  browser,
}) => {
  const server = new SyntheticServer(queue(2));
  const page = await (await context(browser, server)).newPage();
  await openReview(page);

  // Ingestion adds a Review vacancy without advancing the Mini App revision ...
  server.ingest({ id: 8900, title: 'Ingested vacancy', score: 99, status: 'UNREVIEWED' });
  // ... and then a Mini App mutation commits with a strictly higher revision. If its reply
  // carried a global snapshot and the client trusted it because 2 > 1, the ingested vacancy
  // would vanish.
  await actions(page).getByRole('button', { name: 'Skip' }).click();
  await expect.poll(() => server.jobs[0]!.status).toBe('DISMISSED');

  // One vacancy dismissed, one still queued, plus the ingested one: the global projection kept
  // the out-of-band change instead of being replaced by the mutation's older view.
  await expect(page.getByRole('button', { name: 'Review, 2 waiting' })).toBeVisible();

  // The in-session queue is deliberately frozen so it cannot reshuffle under the reviewer, so
  // the new vacancy joins the queue once it is refilled rather than mid-review.
  await page.reload();
  await review(page).click();
  await expect(page.locator('.topbar')).toContainText('1 of 2');
  await page.getByRole('button', { name: 'Next vacancy without deciding' }).click();
  await expect(page.getByRole('heading', { name: 'Ingested vacancy' })).toBeVisible();
});

test('a slow read never overwrites the reconciliation that followed it', async ({ browser }) => {
  const server = new SyntheticServer(queue(2));
  const page = await (await context(browser, server)).newPage();
  await openReview(page);

  // Hold the read that this mutation triggers, so it is in flight while the world moves on.
  const heldRead = server.holdNextSnapshot();
  await actions(page).getByRole('button', { name: 'Save' }).click();
  await heldRead.arrived;

  // The world changes while that read is stuck mid-flight; its body is already stale.
  server.ingest({ id: 8901, title: 'Late ingested vacancy', score: 99, status: 'UNREVIEWED' });
  heldRead.release();

  // One vacancy saved, one still queued, plus the late one. The coalesced follow-up read is
  // what the UI settles on, so the stale body already in the air does not erase it.
  await expect(page.getByRole('button', { name: 'Review, 2 waiting' })).toBeVisible();
  await expect.poll(() => server.snapshotReads).toBeGreaterThan(1);
});

test('many settling mutations do not each trigger their own read', async ({ browser }) => {
  const server = new SyntheticServer(queue(4));
  const page = await (await context(browser, server)).newPage();
  await openReview(page);
  const readsAfterLoad = server.snapshotReads;

  for (const label of ['Skip', 'Save', 'Skip']) {
    await actions(page).getByRole('button', { name: label }).click();
  }
  await expect.poll(() => server.ledger.size).toBe(3);

  // Coalescing means the reads are bounded by the pipeline, not by the number of mutations.
  await expect.poll(() => server.snapshotReads).toBeLessThanOrEqual(readsAfterLoad + 3);
  expect(server.snapshotReads).toBeGreaterThan(readsAfterLoad);
});

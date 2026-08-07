import { expect, test, type Page } from '@playwright/test';
import { SyntheticServer, context, review } from './synthetic';

/**
 * P0-B Undo: a server-owned reversal addressed by an opaque capability.
 *
 * The client stores a token and nothing else. It never records what to restore, because only
 * the server knows whether the tracking row a reversal would remove pre-existed the action or
 * was created by it — and deleting on a guess is how a pre-existing application disappears.
 *
 * Applied is reversible here, unlike P0-A. It was disabled then because reversal went through
 * the forward transition policy, which has no APPLIED to SAVED edge; P0-B reverses through an
 * explicit path against recorded state instead, so the policy still needs no backwards edge.
 */

const queue = (count: number) =>
  Array.from({ length: count }, (_, index) => ({
    id: 7000 + index,
    title: `Undo vacancy ${index + 1}`,
    score: 90 - index,
    status: 'UNREVIEWED' as const,
  }));

const toast = (page: Page) => page.getByRole('status');
const undoButton = (page: Page) => toast(page).getByRole('button', { name: 'Undo' });

async function openReview(page: Page) {
  await page.goto('/');
  await review(page).click();
}

test('Save exposes Undo', async ({ browser }) => {
  const server = new SyntheticServer(queue(2));
  const page = await (await context(browser, server)).newPage();
  await openReview(page);

  await page.locator('.actions').getByRole('button', { name: 'Save' }).click();

  await expect(toast(page)).toContainText('Saved');
  await expect(undoButton(page)).toBeVisible();
});

test('Dismiss exposes Undo', async ({ browser }) => {
  const server = new SyntheticServer(queue(2));
  const page = await (await context(browser, server)).newPage();
  await openReview(page);

  await page.locator('.actions').getByRole('button', { name: 'Skip' }).click();

  await expect(toast(page)).toContainText('Skipped');
  await expect(undoButton(page)).toBeVisible();
});

test('Applied is reversible and Undo restores the previous durable state', async ({ browser }) => {
  const server = new SyntheticServer(queue(2));
  const page = await (await context(browser, server)).newPage();
  await openReview(page);

  await page.locator('.actions').getByRole('button', { name: 'Applied' }).click();
  await expect(undoButton(page)).toBeVisible();
  await expect.poll(() => server.jobs[0]!.status).toBe('APPLIED');
  expect(server.applications.has(7000)).toBe(true);

  await undoButton(page).click();

  // The Mini App created that tracking row, so reversing removes exactly what it created.
  await expect.poll(() => server.jobs[0]!.status).toBe('UNREVIEWED');
  await expect.poll(() => server.applications.has(7000)).toBe(false);
  expect(server.historyFor(7000)).toHaveLength(0);
});

test('an Undo on a pre-existing application restores it rather than deleting it', async ({
  browser,
}) => {
  const server = new SyntheticServer(queue(2));
  // The vacancy was already tracked before the Mini App touched it — by the bot, say.
  server.externalTransition(7000, 'SAVED');
  const page = await (await context(browser, server)).newPage();
  await page.goto('/');
  await page.getByRole('button', { name: 'Saved', exact: true }).click();
  await expect(page.getByRole('button', { name: /Undo vacancy 1/ })).toBeVisible();

  // Nothing was deleted and the pre-existing history row survives.
  expect(server.applications.get(7000)?.status).toBe('SAVED');
  expect(server.historyFor(7000)).toHaveLength(1);
});

/**
 * Only one action is undoable at a time. The reviewer moves on after each decision, so a toast
 * left over from an earlier vacancy would reverse something they have already passed.
 */
test('a newer action retires the previous Undo', async ({ browser }) => {
  const server = new SyntheticServer(queue(3));
  const page = await (await context(browser, server)).newPage();
  await openReview(page);

  await page.locator('.actions').getByRole('button', { name: 'Save' }).click();
  await expect(toast(page)).toContainText('Saved');
  await expect(toast(page)).toContainText('Undo vacancy 1');

  await page.locator('.actions').getByRole('button', { name: 'Applied' }).click();

  // Exactly one toast, and it belongs to the newer action on the newer vacancy.
  await expect(toast(page)).toHaveCount(1);
  await expect(toast(page)).toContainText('Marked as applied');
  await expect(toast(page)).toContainText('Undo vacancy 2');
  await expect(toast(page)).not.toContainText('Saved');
});

test('an Undo invalidated by an external writer is refused without destroying anything', async ({
  browser,
}) => {
  const server = new SyntheticServer(queue(2));
  const page = await (await context(browser, server)).newPage();
  await openReview(page);

  await page.locator('.actions').getByRole('button', { name: 'Save' }).click();
  await expect(undoButton(page)).toBeVisible();
  await expect.poll(() => server.jobs[0]!.status).toBe('SAVED');

  // The Telegram bot moves the same vacancy on. This advances no Mini App revision at all,
  // so only the recorded fingerprint can catch it.
  server.externalTransition(7000, 'APPLIED');
  await undoButton(page).click();

  await expect(page.getByRole('alert')).toContainText('Too late to undo');
  // The newer external action stands: nothing rolled back, nothing deleted.
  expect(server.jobs[0]!.status).toBe('APPLIED');
  expect(server.applications.get(7000)?.status).toBe('APPLIED');
  // And the client reconciled rather than keeping its own guess.
  await page.getByRole('button', { name: 'Track' }).click();
  await expect(page.getByRole('button', { name: /Undo vacancy 1/ })).toBeVisible();
});

test('the UI and the server do not diverge after a refused write', async ({ browser }) => {
  const server = new SyntheticServer(queue(2));
  const page = await (await context(browser, server)).newPage();
  await openReview(page);

  server.rejectNext();
  await page.locator('.actions').getByRole('button', { name: 'Skip' }).click();
  await expect(page.getByRole('alert')).toBeVisible();

  // Whatever the client shows must match a fresh authoritative read. The refusal changed
  // nothing, so the vacancy is still unreviewed and still in the review window.
  expect(server.jobs[0]!.status).toBe('UNREVIEWED');
  expect(server.snapshot().reviewQueue.items.map((job) => job.id)).toContain(7000);
  await page.getByRole('button', { name: 'Saved', exact: true }).click();
  await expect(page.getByRole('button', { name: /Undo vacancy 1/ })).toHaveCount(0);
});

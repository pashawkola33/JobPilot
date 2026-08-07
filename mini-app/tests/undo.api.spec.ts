import { expect, test, type Page } from '@playwright/test';
import { SyntheticServer, context, review } from './synthetic';

/**
 * P0-A Undo boundary.
 *
 * Applied is not reversible in P0-A: the application transition policy has no APPLIED to SAVED
 * edge, so offering Undo could only ever produce a rejected write and a UI that disagrees with
 * the server. Save and Dismiss stay undoable, and any Undo that is still possible must end with
 * the client showing authoritative state even when the write is refused.
 *
 * Deterministic Applied reversal belongs to P0-B.
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

test('Applied does not expose Undo', async ({ browser }) => {
  const server = new SyntheticServer(queue(2));
  const page = await (await context(browser, server)).newPage();
  await openReview(page);

  await page.locator('.actions').getByRole('button', { name: 'Applied' }).click();

  await expect(page.getByRole('heading', { name: 'Undo vacancy 2' })).toBeVisible();
  await expect(undoButton(page)).toHaveCount(0);
  // The decision still reached the server and is reflected authoritatively.
  await expect.poll(() => server.jobs[0].status).toBe('APPLIED');
});

test('selecting Applied clears a previously armed Undo', async ({ browser }) => {
  const server = new SyntheticServer(queue(3));
  const page = await (await context(browser, server)).newPage();
  await openReview(page);

  await page.locator('.actions').getByRole('button', { name: 'Save' }).click();
  await expect(undoButton(page)).toBeVisible();

  await page.locator('.actions').getByRole('button', { name: 'Applied' }).click();

  await expect(undoButton(page)).toHaveCount(0);
  await expect(toast(page)).toHaveCount(0);
});

test('a rejected Undo reconciles the UI from authoritative server state', async ({ browser }) => {
  const server = new SyntheticServer(queue(2));
  const page = await (await context(browser, server)).newPage();
  await openReview(page);

  await page.locator('.actions').getByRole('button', { name: 'Save' }).click();
  await expect(undoButton(page)).toBeVisible();
  await expect.poll(() => server.jobs[0].status).toBe('SAVED');

  server.rejectNext();
  await undoButton(page).click();

  // The typed failure is shown ...
  await expect(page.getByRole('alert')).toContainText('Change was rejected');
  // ... and the optimistic rollback is gone: the server still has it saved, so the client does.
  expect(server.jobs[0].status).toBe('SAVED');
  await page.getByRole('button', { name: 'Saved', exact: true }).click();
  await expect(page.getByRole('button', { name: /Undo vacancy 1/ })).toBeVisible();
});

test('the UI and the server do not diverge after a failed Undo', async ({ browser }) => {
  const server = new SyntheticServer(queue(2));
  const page = await (await context(browser, server)).newPage();
  await openReview(page);

  await page.locator('.actions').getByRole('button', { name: 'Skip' }).click();
  await expect(undoButton(page)).toBeVisible();
  await expect.poll(() => server.jobs[0].status).toBe('DISMISSED');

  server.rejectNext();
  await undoButton(page).click();
  await expect(page.getByRole('alert')).toBeVisible();

  // Whatever the client is now showing must match a fresh authoritative read. A dismissed
  // vacancy is in neither the review window nor Saved, so it must appear in neither.
  const snapshot = server.snapshot();
  expect(snapshot.reviewQueue.items.map((job) => job.id)).not.toContain(7000);
  expect(snapshot.saved.items.map((job) => job.id)).not.toContain(7000);
  await expect(page.getByRole('heading', { name: 'Undo vacancy 1' })).toHaveCount(0);
  await page.getByRole('button', { name: 'Saved', exact: true }).click();
  await expect(page.getByRole('button', { name: /Undo vacancy 1/ })).toHaveCount(0);
});

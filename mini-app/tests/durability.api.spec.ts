import { expect, test } from '@playwright/test';
import { SyntheticServer, context, review } from './synthetic';

for (const total of [49, 50, 51]) {
  test(`${total} unreviewed vacancies expose the bounded window and authoritative count`, async ({ browser }) => {
    const server = new SyntheticServer(Array.from({ length: total }, (_, index) => ({
      id: 4000 + index,
      title: `Boundary vacancy ${index + 1}`,
      score: 100 - index,
      status: 'UNREVIEWED' as const,
    })));
    const current = await context(browser, server);
    const page = await current.newPage();
    await page.goto('/');

    await expect(page.getByRole('button', { name: `Review, ${total} waiting` })).toBeVisible();
    await review(page).click();
    await expect(page.locator('.topbar')).toContainText(`1 of ${Math.min(total, 50)}`);
    await expect(page.getByLabel(`1 of ${Math.min(total, 50)} loaded; ${total} waiting`)).toBeVisible();
    if (total === 51) await expect(page.locator('.topbar')).toContainText('51 waiting');

    await current.close();
  });
}

test('exhausting a bounded review window loads the remaining durable vacancy', async ({ browser }) => {
  const server = new SyntheticServer(Array.from({ length: 51 }, (_, index) => ({
    id: 5000 + index,
    title: `Batch vacancy ${index + 1}`,
    score: 100 - index,
    status: 'UNREVIEWED' as const,
  })));
  const current = await context(browser, server);
  const page = await current.newPage();
  await page.goto('/');
  await review(page).click();

  for (let index = 0; index < 50; index += 1) {
    await expect(page.getByRole('heading', { name: `Batch vacancy ${index + 1}` })).toBeVisible();
    await expect(page.getByLabel(`${index + 1} of 50 loaded; ${51 - index} waiting`)).toBeVisible();
    await expect(page.getByRole('button', { name: `Review, ${51 - index} waiting` })).toBeVisible();
    await Promise.all([
      page.waitForResponse((response) => response.url().endsWith('/workflow')),
      page.locator('.actions').getByRole('button', { name: 'Skip' }).click(),
    ]);
  }

  await expect(page.getByRole('heading', { name: 'More vacancies are ready' })).toBeVisible();
  await expect(page.getByRole('heading', { name: 'Queue cleared' })).toHaveCount(0);
  await expect(page.getByRole('button', { name: 'Review, 1 waiting' })).toBeVisible();
  await page.getByRole('button', { name: 'Load next batch' }).click();

  await expect(page.getByRole('heading', { name: 'Batch vacancy 51' })).toBeVisible();
  await expect(page.locator('.topbar')).toContainText('1 of 1');
  await expect(page.getByLabel('1 of 1 loaded; 1 waiting')).toBeVisible();
  await expect(page.getByRole('button', { name: 'Review, 1 waiting' })).toBeVisible();
  await Promise.all([
    page.waitForResponse((response) => response.url().endsWith('/workflow')),
    page.locator('.actions').getByRole('button', { name: 'Skip' }).click(),
  ]);

  await expect(page.getByRole('heading', { name: 'Queue cleared' })).toBeVisible();
  await expect(page.getByRole('heading', { name: 'More vacancies are ready' })).toHaveCount(0);
  await expect(page.getByRole('button', { name: 'Review', exact: true })).toBeVisible();
  await current.close();
});

test('Save survives reload and a separate browser context', async ({ browser }) => {
  const server = new SyntheticServer([
    { id: 1001, title: 'Durable saved vacancy', score: 91, status: 'UNREVIEWED' },
  ]);
  const first = await context(browser, server);
  const pageA = await first.newPage();
  await pageA.goto('/');
  await review(pageA).click();
  await pageA.locator('.actions').getByRole('button', { name: 'Save' }).click();

  await pageA.reload();
  await pageA.getByRole('button', { name: 'Saved', exact: true }).click();
  await expect(pageA.getByRole('button', { name: /Durable saved vacancy/ })).toBeVisible();

  const second = await context(browser, server);
  const pageB = await second.newPage();
  await pageB.goto('/');
  await pageB.getByRole('button', { name: 'Saved', exact: true }).click();
  await expect(pageB.getByRole('button', { name: /Durable saved vacancy/ })).toBeVisible();
  await pageB.getByRole('button', { name: 'Track' }).click();
  await expect(pageB.getByRole('button', { name: /Durable saved vacancy/ })).toBeVisible();

  await first.close();
  await second.close();
});

test('Applied survives reload and a separate browser context', async ({ browser }) => {
  const server = new SyntheticServer([
    { id: 1002, title: 'Durable applied vacancy', score: 89, status: 'UNREVIEWED' },
  ]);
  const first = await context(browser, server);
  const pageA = await first.newPage();
  await pageA.goto('/');
  await review(pageA).click();
  await pageA.locator('.actions').getByRole('button', { name: 'Applied' }).click();
  await pageA.reload();
  await pageA.getByRole('button', { name: 'Track' }).click();
  await expect(pageA.getByRole('button', { name: /Durable applied vacancy/ })).toBeVisible();

  const second = await context(browser, server);
  const pageB = await second.newPage();
  await pageB.goto('/');
  await pageB.getByRole('button', { name: 'Track' }).click();
  await expect(pageB.getByRole('button', { name: /Durable applied vacancy/ })).toBeVisible();

  await first.close();
  await second.close();
});

test('a saved vacancy stays visible beside fifty-one unreviewed jobs', async ({ browser }) => {
  const jobs: ServerJob[] = [
    { id: 2000, title: 'Saved outside the old window', score: 1, status: 'SAVED' },
    ...Array.from({ length: 51 }, (_, index) => ({
      id: 2100 + index,
      title: `Unreviewed ${index}`,
      score: 100 - index,
      status: 'UNREVIEWED' as const,
    })),
  ];
  const server = new SyntheticServer(jobs);
  server.applications.set(2000, {
    jobId: 2000,
    status: 'SAVED',
    updatedAt: new Date().toISOString(),
    appliedAt: null,
  });
  const current = await context(browser, server);
  const page = await current.newPage();
  await page.goto('/');

  await expect(page.getByRole('heading', { name: /vacancies waiting/ })).toContainText('51');
  await page.getByRole('button', { name: 'Saved', exact: true }).click();
  await expect(page.getByRole('button', { name: /Saved outside the old window/ })).toBeVisible();
  await expect(page.locator('.topbar')).toContainText('1');
  await current.close();
});

test('bounded application rows retain authoritative counters', async ({ browser }) => {
  const jobs = Array.from({ length: 21 }, (_, index) => ({
    id: 3000 + index,
    title: `Tracked ${index}`,
    score: 80,
    status: 'APPLIED' as const,
  }));
  const server = new SyntheticServer(jobs);
  for (const job of jobs) {
    server.applications.set(job.id, {
      jobId: job.id,
      status: 'APPLIED',
      updatedAt: new Date().toISOString(),
      appliedAt: new Date().toISOString(),
    });
  }
  const current = await context(browser, server);
  const page = await current.newPage();
  await page.goto('/');
  await page.getByRole('button', { name: 'Track' }).click();

  await expect(page.locator('.topbar')).toContainText('21');
  await expect(page.getByRole('button', { name: /^All/ })).toContainText('21');
  await expect(page.getByText('Showing 20 of 21 applications')).toBeVisible();
  await current.close();
});

import { confirmedProfile } from './searchProfileFixture';
import { expect, test, type Page, type Route } from '@playwright/test';
import { SyntheticServer } from './synthetic';

const token = (id: string) => id.repeat(43);
const server = () => new SyntheticServer([{ id: 8000, title: 'Shared engineering vacancy', score: 0, status: 'UNREVIEWED' }]);

async function boundary(page: Page, launch: string, accounts: Record<string, SyntheticServer>) {
  await page.addInitScript((data) => {
    (window as unknown as { Telegram: unknown }).Telegram = { WebApp: {
      initData: data, colorScheme: 'light', ready() {}, expand() {}, openLink() {}, onEvent() {}, offEvent() {},
      BackButton: { show() {}, hide() {}, onClick() {}, offClick() {} },
    } };
  }, launch);
  const used = new Set<string>();
  const deliveries: string[] = [];
  await page.route('**/api/mini-app/v1/**', () => { throw new Error('Beta must never use v1'); });
  await page.route('**/api/mini-app/v2/**', async (route) => {
    const request = route.request();
    const path = new URL(request.url()).pathname;
    if (path.endsWith('/auth/telegram')) {
      const data = request.headers()['x-telegram-init-data'];
      if (used.has(data)) return route.fulfill({ status: 409, json: { category: 'AUTH_LAUNCH_REPLAYED' } });
      used.add(data);
      const id = data.startsWith('launch-b') ? 'b' : 'a';
      return route.fulfill({ json: { accessToken: token(id), expiresAt: new Date(Date.now() + 900000).toISOString() } });
    }
    const auth = request.headers().authorization;
    const id = Object.keys(accounts).find((key) => auth === `Bearer ${token(key)}`);
    expect(id).toBeDefined();
    expect(request.headers()['x-telegram-init-data']).toBeUndefined();
    deliveries.push(`${id}:${request.method()}:${path}`);
    if (path.endsWith('/me/search-profile')) return route.fulfill({ json: confirmedProfile() });
    if (path.endsWith('/me')) return route.fulfill({ json: {
      accountId: id, legacyOwner: false, onboardingRequired: false, accountState: 'READY',
      sessionExpiresAt: new Date(Date.now() + 900000).toISOString(),
    } });
    const candidate = accounts[id!];
    if (path.endsWith('/snapshot')) {
      const snapshot = candidate.snapshot();
      const neutral = (job: typeof snapshot.reviewQueue.items[number]) => ({ ...job, score: 0, band: null, disposition: 'REVIEW', strengths: [], risks: [] });
      return route.fulfill({ json: { ...snapshot,
        reviewQueue: { ...snapshot.reviewQueue, items: snapshot.reviewQueue.items.map(neutral) },
        saved: { ...snapshot.saved, items: snapshot.saved.items.map(neutral) },
        applications: { ...snapshot.applications, items: snapshot.applications.items.map((application) => ({ ...application, job: neutral(application.job) })) },
        workflowCounts: { ...snapshot.workflowCounts, unreviewedMatch: 0, unreviewedReview: snapshot.reviewQueue.total },
      } });
    }
    return candidate.route(route);
  });
  return deliveries;
}

async function reopen(page: Page, launch: string) {
  await page.evaluate((data) => {
    (window as unknown as { Telegram: { WebApp: { initData: string } } }).Telegram.WebApp.initData = data;
    window.dispatchEvent(new Event('focus'));
  }, launch);
}

test('two beta accounts independently save, dismiss, apply and undo the shared vacancy', async ({ page, browser }) => {
  const a = server(); const b = server();
  const otherContext = await browser.newContext(); const other = await otherContext.newPage();
  await boundary(page, 'launch-a', { a }); await boundary(other, 'launch-b', { b });
  await page.goto('/'); await other.goto(page.url());
  await expect(page.getByRole('heading', { name: 'Shared vacancies' })).toBeVisible();
  await expect(other.getByRole('heading', { name: 'Shared engineering vacancy' })).toBeVisible();
  await expect(page.locator('body')).not.toContainText(/Good match|Excellent match|Synthetic strength|Match score|0\/100/);
  await page.getByRole('button', { name: 'Save', exact: true }).click();
  await expect(page.getByRole('button', { name: 'Undo', exact: true })).toBeVisible();
  await page.getByRole('button', { name: 'Saved', exact: true }).click();
  await expect(page.getByRole('heading', { name: 'Shared engineering vacancy' })).toBeVisible();
  await expect(other.getByRole('button', { name: 'Save', exact: true })).toBeVisible();
  await other.getByRole('button', { name: 'Dismiss', exact: true }).click();
  await expect(other.getByRole('button', { name: 'Undo', exact: true })).toBeVisible();
  expect(a.jobs[0].status).toBe('SAVED'); expect(b.jobs[0].status).toBe('DISMISSED');
  await page.getByRole('button', { name: 'Mark applied', exact: true }).click();
  await page.getByRole('button', { name: 'Applications', exact: true }).click();
  await expect(page.getByText('APPLIED', { exact: true })).toBeVisible();
  expect(a.applications.get(8000)?.status).toBe('APPLIED'); expect(b.applications.size).toBe(0);
  await other.getByRole('button', { name: 'Undo', exact: true }).click();
  await expect(other.getByRole('button', { name: 'Save', exact: true })).toBeVisible();
  expect(a.jobs[0].status).toBe('APPLIED'); expect(b.jobs[0].status).toBe('UNREVIEWED');
  await expect(page.locator('body')).not.toContainText(/Good match|Match score|0\/100/);
  expect(await page.evaluate(() => [localStorage.length, sessionStorage.length])).toEqual([0, 0]);
  await otherContext.close();
});

test('spent-launch reload requires reopening and a fresh launch restores private saved state', async ({ page }) => {
  const a = server(); await boundary(page, 'launch-a', { a });
  await page.goto('/'); await page.getByRole('button', { name: 'Save', exact: true }).click();
  await expect(page.getByRole('button', { name: 'Undo', exact: true })).toBeVisible();
  await page.reload(); await expect(page.getByText('Reopen JobPilot in Telegram')).toBeVisible();
  await reopen(page, 'launch-a-fresh');
  await expect(page.getByRole('heading', { name: 'Shared vacancies' })).toBeVisible();
  await page.getByRole('button', { name: 'Saved', exact: true }).click();
  await expect(page.getByRole('heading', { name: 'Shared engineering vacancy' })).toBeVisible();
  await expect(page.getByRole('button', { name: 'Undo', exact: true })).toHaveCount(0);
});

for (const completion of ['success', 'forbidden', 'network'] as const) {
  test(`held beta ${completion} response cannot mutate, invalidate or retry into a replacement account`, async ({ page }) => {
    const a = server(); const b = server(); const deliveries = await boundary(page, 'launch-a', { a, b });
    let release!: () => void; const released = new Promise<void>((resolve) => { release = resolve; });
    let arrived!: () => void; const pending = new Promise<void>((resolve) => { arrived = resolve; });
    let writes = 0;
    await page.route('**/api/mini-app/v2/jobs/*/workflow', async (route: Route) => {
      writes++; expect(route.request().headers().authorization).toBe(`Bearer ${token('a')}`);
      arrived(); await released;
      if (completion === 'forbidden') return route.fulfill({ status: 403, json: { category: 'FORBIDDEN' } });
      if (completion === 'network') return route.abort('failed');
      return a.route(route);
    });
    await page.goto('/'); await page.getByRole('button', { name: 'Save', exact: true }).click(); await pending;
    await reopen(page, 'launch-b');
    await expect(page.getByRole('button', { name: 'Save', exact: true })).toBeVisible();
    const before = deliveries.length;
    const completed = completion === 'network'
      ? page.waitForEvent('requestfailed', { predicate: (r) => r.method() === 'PUT' })
      : page.waitForResponse((r) => r.request().method() === 'PUT');
    release(); await completed;
    await page.evaluate(() => new Promise<void>((resolve) => requestAnimationFrame(() => requestAnimationFrame(() => resolve()))));
    await expect(page.getByRole('button', { name: 'Save', exact: true })).toBeVisible();
    await expect(page.getByRole('button', { name: 'Undo', exact: true })).toHaveCount(0);
    await expect(page.getByText('Reopen JobPilot in Telegram')).toHaveCount(0);
    expect(writes).toBe(1); expect(deliveries.length).toBe(before); expect(b.jobs[0].status).toBe('UNREVIEWED');
  });
}

test('saved beta vacancy actions wait for the in-flight mutation verdict', async ({ page }) => {
  const a = server(); a.externalTransition(8000, 'SAVED');
  await boundary(page, 'launch-a', { a });
  await page.goto('/'); await page.getByRole('button', { name: 'Saved', exact: true }).click();
  const held = a.holdNextWrite();
  await page.getByRole('button', { name: 'Mark applied', exact: true }).click();
  await held.arrived;
  try {
    await expect(page.getByRole('button', { name: 'Dismiss', exact: true })).toBeDisabled();
    await expect(page.getByRole('button', { name: 'Mark applied', exact: true })).toBeDisabled();
  } finally { held.release(); }
});

test('an ambiguously applied saved vacancy remains recoverable after leaving the saved projection', async ({ page }) => {
  const a = server(); a.externalTransition(8000, 'SAVED');
  await boundary(page, 'launch-a', { a });
  a.dropNextResponseAfterApplying();
  const mutations: string[] = [];
  await page.route('**/api/mini-app/v2/jobs/*/workflow', async (route) => {
    mutations.push(route.request().postDataJSON().mutationId as string);
    if (mutations.length === 2) return route.abort('failed');
    return route.fallback();
  });
  await page.goto('/'); await page.getByRole('button', { name: 'Saved', exact: true }).click();
  await page.getByRole('button', { name: 'Mark applied', exact: true }).click();
  await expect(page.getByText('No saved vacancies yet.', { exact: true })).toBeVisible();
  await page.getByRole('button', { name: 'Resolve change', exact: true }).click();
  await expect(page.getByRole('button', { name: 'Resolve change', exact: true })).toHaveCount(0);
  expect(mutations).toHaveLength(3); expect(new Set(mutations).size).toBe(1);
  expect(a.jobs[0].status).toBe('APPLIED');
  expect(a.historyFor(8000).filter((entry) => entry.status === 'APPLIED')).toHaveLength(1);
  await page.getByRole('button', { name: 'Applications', exact: true }).click();
  await expect(page.getByText('APPLIED', { exact: true })).toBeVisible();
});

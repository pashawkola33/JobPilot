import { confirmedProfile } from './searchProfileFixture';
import { expect, test, type Page } from '@playwright/test';
import { SyntheticServer, review } from './synthetic';

const TOKEN = 'a'.repeat(43);
const emptyBetaSnapshot = () => {
  const snapshot = new SyntheticServer([]).snapshot();
  return { ...snapshot, workflowCounts: { ...snapshot.workflowCounts, unreviewedMatch: 0, unreviewedReview: 0 } };
};
async function telegram(page: Page) {
  await page.addInitScript(() => {
    (window as unknown as { Telegram: unknown }).Telegram = { WebApp: {
      initData: 'synthetic-launch-a', colorScheme: 'light', ready() {}, expand() {},
      openLink() {}, onEvent() {}, offEvent() {},
      BackButton: { show() {}, hide() {}, onClick() {}, offClick() {} },
    } };
  });
}
const account = (id = '101', legacyOwner = false, expiresAt = new Date(Date.now() + 900_000).toISOString()) => ({
  accountId: id, accountState: 'READY', onboardingRequired: false, legacyOwner, sessionExpiresAt: expiresAt,
});

test('new beta account initializes once and never requests legacy private data', async ({ page }) => {
  await telegram(page); let exchanges = 0; const legacy: string[] = [];
  await page.route('**/api/mini-app/v1/**', async (r) => { legacy.push(r.request().url()); await r.fulfill({ status: 403 }); });
  await page.route('**/api/mini-app/v2/**', async (r) => {
    const req = r.request();
    expect(req.url()).not.toContain('synthetic-launch');
    if (req.url().endsWith('/auth/telegram')) {
      exchanges++; expect(req.method()).toBe('POST');
      expect(req.headers()['x-telegram-init-data']).toBe('synthetic-launch-a');
      return r.fulfill({ json: { accessToken: TOKEN, expiresAt: new Date(Date.now() + 900_000).toISOString() } });
    }
    expect(req.headers().authorization).toBe(`Bearer ${TOKEN}`);
    expect(req.headers()['x-telegram-init-data']).toBeUndefined();
    if (req.url().endsWith('/me/search-profile')) return r.fulfill({ json: confirmedProfile() });
    return r.fulfill({ json: req.url().endsWith('/me') ? account() : emptyBetaSnapshot() });
  });
  await page.goto('/');
  await expect(page.getByRole('heading', { name: 'Shared vacancies' })).toBeVisible();
  expect(exchanges).toBe(1); expect(legacy).toEqual([]);
  await expect(page.getByRole('button', { name: /^Review/ })).toHaveCount(0);
  expect(await page.evaluate(() => [localStorage.length, sessionStorage.length])).toEqual([0, 0]);
  await expect(page.locator('body')).not.toContainText(TOKEN);
});

test('replayed launch asks for reopening without retrying or falling back to v1', async ({ page }) => {
  await telegram(page); let exchanges = 0; let legacy = 0;
  await page.route('**/api/mini-app/v1/**', async (r) => { legacy++; await r.fulfill({ status: 403 }); });
  await page.route('**/api/mini-app/v2/**', async (r) => {
    exchanges++; await r.fulfill({ status: 409, json: { category: 'AUTH_LAUNCH_REPLAYED' } });
  });
  await page.goto('/');
  await expect(page.getByText('Reopen JobPilot in Telegram')).toBeVisible();
  expect(exchanges).toBe(1); expect(legacy).toBe(0);
});

test('session expiry removes account content without reusing the spent launch', async ({ page }) => {
  await telegram(page); let exchanges = 0;
  const expires = new Date(Date.now() + 3000).toISOString();
  await page.route('**/api/mini-app/v2/**', async (r) => {
    if (r.request().url().endsWith('/auth/telegram')) {
      exchanges++; return r.fulfill({ json: { accessToken: TOKEN, expiresAt: expires } });
    }
    if (r.request().url().endsWith('/me/search-profile')) return r.fulfill({ json: confirmedProfile() });
    return r.fulfill({ json: r.request().url().endsWith('/me') ? account('101', false, expires) : emptyBetaSnapshot() });
  });
  await page.goto('/');
  await expect(page.getByRole('heading', { name: 'Shared vacancies' })).toBeVisible();
  await expect(page.getByText('Reopen JobPilot in Telegram')).toBeVisible({ timeout: 6000 });
  await expect(page.getByRole('heading', { name: 'Shared vacancies' })).toHaveCount(0);
  expect(exchanges).toBe(1);
});

test('a fresh Telegram launch replaces the account and uses no previous session', async ({ page }) => {
  await telegram(page); const seen: string[] = []; let second = false;
  await page.route('**/api/mini-app/v2/**', async (r) => {
    if (r.request().url().endsWith('/auth/telegram')) {
      seen.push(r.request().headers()['x-telegram-init-data']);
      second = seen.length === 2;
      return r.fulfill({ json: { accessToken: (second ? 'b' : 'a').repeat(43), expiresAt: new Date(Date.now() + 900_000).toISOString() } });
    }
    expect(r.request().headers().authorization).toBe(`Bearer ${(second ? 'b' : 'a').repeat(43)}`);
    if (r.request().url().endsWith('/me/search-profile')) return r.fulfill({ json: confirmedProfile() });
    return r.fulfill({ json: r.request().url().endsWith('/me') ? account(second ? '202' : '101') : emptyBetaSnapshot() });
  });
  await page.goto('/');
  await expect(page.getByRole('heading', { name: 'Shared vacancies' })).toBeVisible();
  await page.evaluate(() => {
    (window as unknown as { Telegram: { WebApp: { initData: string } } }).Telegram.WebApp.initData = 'synthetic-launch-b';
    window.dispatchEvent(new Event('focus'));
  });
  await expect.poll(() => seen).toEqual(['synthetic-launch-a', 'synthetic-launch-b']);
  await expect(page.getByRole('heading', { name: 'Shared vacancies' })).toBeVisible();
});

for (const completion of ['success', 'forbidden', 'network'] as const) {
  test(`an old workspace ${completion} cannot affect a new launch`, async ({ page }) => {
    await telegram(page);
    const server = new SyntheticServer([{ id: 8000, title: 'Owner vacancy', score: 90, status: 'UNREVIEWED' }]);
    let second = false;
    let reads = 0;
    let writes = 0;
    let arrived!: () => void;
    const pendingWrite = new Promise<void>((resolve) => { arrived = resolve; });
    let release!: () => void;
    const released = new Promise<void>((resolve) => { release = resolve; });
    await page.route('**/api/mini-app/v2/**', async (r) => {
      if (r.request().url().endsWith('/auth/telegram')) {
        second = r.request().headers()['x-telegram-init-data'] === 'synthetic-launch-b';
        return r.fulfill({ json: { accessToken: (second ? 'b' : 'a').repeat(43) } });
      }
      // The network case opens a fresh owner workspace: an old retry must not borrow it.
      if (r.request().url().endsWith('/me/search-profile')) return r.fulfill({ json: confirmedProfile() });
    return r.fulfill({ json: r.request().url().endsWith('/me') ? account(second ? '202' : '101', !second || completion === 'network') : emptyBetaSnapshot() });
    });
    await page.route('**/api/mini-app/v1/**', async (r) => {
      if (r.request().method() === 'PUT') {
        writes++;
        if (writes === 1) {
          arrived(); await released;
          if (completion === 'forbidden') return r.fulfill({ status: 403, json: { category: 'FORBIDDEN' } });
          if (completion === 'network') return r.abort('failed');
        }
      } else reads++;
      return server.route(r);
    });
    await page.goto('/');
    await review(page).click();
    await page.locator('.actions').getByRole('button', { name: 'Save' }).click();
    await pendingWrite;
    const previousReads = reads;
    const nextAccount = page.waitForResponse((r) => r.url().endsWith('/v2/me'));
    await page.evaluate(() => {
      (window as unknown as { Telegram: { WebApp: { initData: string } } }).Telegram.WebApp.initData = 'synthetic-launch-b';
      window.dispatchEvent(new Event('focus'));
    });
    await nextAccount;
    const current = completion === 'network' ? review(page) : page.getByRole('heading', { name: 'Shared vacancies' });
    await expect(current).toBeVisible();
    if (completion === 'network') await expect.poll(() => reads).toBeGreaterThan(previousReads);
    await page.evaluate(() => new Promise<void>((resolve) => requestAnimationFrame(() => requestAnimationFrame(() => resolve()))));
    const currentReads = reads;
    const completed = completion === 'network'
      ? page.waitForEvent('requestfailed', { predicate: (r) => r.method() === 'PUT' })
      : page.waitForResponse((r) => r.request().method() === 'PUT');
    release(); await completed;
    // Drain response handlers, recovery and reconciliation before checking the new workspace.
    await page.evaluate(() => new Promise<void>((resolve) => requestAnimationFrame(() => requestAnimationFrame(() => resolve()))));
    await expect(current).toBeVisible();
    expect(writes).toBe(1);
    expect(reads).toBe(currentReads);
    await expect(page.getByText('Reopen JobPilot in Telegram')).toHaveCount(0);
  });
}

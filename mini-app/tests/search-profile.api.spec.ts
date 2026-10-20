import { test, expect, type Page } from '@playwright/test';
import { SyntheticServer } from './synthetic';

import { confirmedProfile } from './searchProfileFixture';
type View = ReturnType<typeof confirmedProfile>;
async function setup(page: Page, initial: View | null = null, legacyOwner = false) {
  await page.addInitScript(() => {
    (window as unknown as { Telegram: unknown }).Telegram = { WebApp: { initData: 'launch-a', ready() {}, expand() {},
      colorScheme: 'light', onEvent() {}, offEvent() {}, BackButton: { show() {}, hide() {}, onClick() {}, offClick() {} } } };
  });
  const saved: Record<string, View | null> = { a: initial, b: null };
  const writes: { account: string; body: { expectedRevision: number; requestId: string; profile: View['profile'] } }[] = [];
  const server = new SyntheticServer([]);
  if (legacyOwner) await page.route('**/api/mini-app/v1/**', (r) => server.route(r));
  await page.route('**/api/mini-app/v2/**', async (r) => {
    const req = r.request(); const path = new URL(req.url()).pathname;
    if (path.endsWith('/auth/telegram')) {
      const id = req.headers()['x-telegram-init-data'].startsWith('launch-b') ? 'b' : 'a';
      return r.fulfill({ json: { accessToken: id.repeat(43), expiresAt: new Date(Date.now() + 900000).toISOString() } });
    }
    const id = req.headers().authorization === `Bearer ${'b'.repeat(43)}` ? 'b' : 'a';
    if (path.endsWith('/me')) return r.fulfill({ json: { accountId: id, legacyOwner,
      onboardingRequired: !saved[id], accountState: saved[id] ? 'READY' : 'ONBOARDING_REQUIRED',
      sessionExpiresAt: new Date(Date.now() + 900000).toISOString() } });
    if (path.endsWith('/me/search-profile')) {
      if (req.method() === 'PUT') {
        const body = req.postDataJSON(); writes.push({ account: id, body });
        saved[id] = { ...confirmedProfile(), activeRevision: (saved[id]?.activeRevision ?? 0) + 1, profile: body.profile };
      }
      return r.fulfill({ json: saved[id] ?? { activeRevision: 0, completionStatus: 'NOT_STARTED',
        missingRequiredFields: ['desiredRoles', 'targetRegions', 'workFormats'], createdAt: null,
        profile: { ...confirmedProfile().profile, desiredTitles: [], targetRegions: [], workFormats: [] } } });
    }
    return server.route(r);
  });
  return { saved, writes };
}
async function review(page: Page, title = 'Backend engineer') {
  await page.getByLabel('Desired job titles').fill(title);
  await page.getByRole('button', { name: 'Next', exact: true }).click();
  await page.getByLabel(/^Technical skills/).fill('Java, SQL');
  await page.getByRole('button', { name: 'Next', exact: true }).click();
  await page.getByLabel('Target countries or regions').fill('Romania, Europe');
  await page.getByLabel('Remote', { exact: true }).check();
  await page.getByRole('button', { name: 'Next', exact: true }).click();
  await page.getByRole('button', { name: 'Review preferences', exact: true }).click();
}
async function switchAccount(page: Page) {
  await page.evaluate(() => {
    (window as unknown as { Telegram: { WebApp: { initData: string } } }).Telegram.WebApp.initData = 'launch-b';
    window.dispatchEvent(new Event('focus'));
  });
}

test('onboarding saves explicit preferences once and fresh reload restores editable server state', async ({ page }, testInfo) => {
  const state = await setup(page); await page.goto('/');
  await expect(page.getByRole('heading', { name: 'Your search preferences' })).toBeVisible();
  await expect(page.getByText('Shared vacancies', { exact: true })).toHaveCount(0);
  await review(page);
  await page.screenshot({ path: testInfo.outputPath('onboarding-review.png') });
  await expect(page.getByText('Backend engineer', { exact: true })).toBeVisible();
  await expect(page.getByText('Unknown', { exact: true }).first()).toBeVisible();
  await page.getByRole('button', { name: 'Confirm and save', exact: true }).dblclick();
  await expect(page.getByRole('heading', { name: 'Shared vacancies' })).toBeVisible();
  expect(state.writes).toHaveLength(1);
  expect(state.saved.a?.profile.workAuthorization).toBe('UNKNOWN');
  expect(state.saved.a?.profile.languages).toEqual([]);
  expect(await page.evaluate(() => [localStorage.length, sessionStorage.length])).toEqual([0, 0]);
  await page.reload();
  await expect(page.getByRole('heading', { name: 'Shared vacancies' })).toBeVisible();
  await page.getByRole('button', { name: 'Edit search preferences' }).click();
  await expect(page.getByLabel('Desired job titles')).toHaveValue('Backend engineer');
  await page.getByLabel('Desired job titles').fill('Platform engineer');
  await page.getByRole('button', { name: 'Next', exact: true }).click();
  await page.getByRole('button', { name: 'Back', exact: true }).click();
  await expect(page.getByLabel('Desired job titles')).toHaveValue('Platform engineer');
});

test('validation retains a draft and conflict requires explicit review of latest server state', async ({ page }) => {
  const state = await setup(page); await page.goto('/'); await review(page);
  await page.route('**/api/mini-app/v2/me/search-profile', async (r) => {
    if (r.request().method() === 'PUT') return r.fulfill({ status: 409, json: { category: 'SEARCH_PROFILE_CONFLICT' } });
    return r.fallback();
  });
  await page.getByRole('button', { name: 'Confirm and save', exact: true }).click();
  await expect(page.getByRole('alert')).toContainText('changed');
  await expect(page.getByRole('button', { name: 'Confirm and save', exact: true })).toBeDisabled();
  await page.getByRole('button', { name: 'Back', exact: true }).click();
  await page.getByRole('button', { name: 'Back', exact: true }).click();
  await page.getByRole('button', { name: 'Back', exact: true }).click();
  await page.getByRole('button', { name: 'Back', exact: true }).click();
  await expect(page.getByLabel('Desired job titles')).toHaveValue('Backend engineer');
  await review(page, 'My edited draft');
  await expect(page.getByRole('button', { name: 'Confirm and save', exact: true })).toBeDisabled();
  await expect(page.getByRole('button', { name: 'Load latest saved preferences' })).toBeVisible();
  state.saved.a = { ...confirmedProfile(), activeRevision: 2,
    profile: { ...confirmedProfile().profile, desiredTitles: ['Other session title'] } };
  await page.getByRole('button', { name: 'Load latest saved preferences' }).click();
  await expect(page.getByLabel('Desired job titles')).toHaveValue('Other session title');
});

for (const completion of ['success', 'forbidden', 'network'] as const) {
  test(`old profile save ${completion} cannot cross account generations`, async ({ page }) => {
    const state = await setup(page); let release!: () => void; const held = new Promise<void>((r) => { release = r; });
    let arrived!: () => void; const pending = new Promise<void>((r) => { arrived = r; }); let writes = 0;
    await page.route('**/api/mini-app/v2/me/search-profile', async (r) => {
      if (r.request().method() !== 'PUT') return r.fallback();
      writes++; expect(r.request().headers().authorization).toBe(`Bearer ${'a'.repeat(43)}`);
      arrived(); await held;
      if (completion === 'forbidden') return r.fulfill({ status: 403, json: { category: 'UNAUTHENTICATED' } });
      if (completion === 'network') return r.abort('failed');
      return r.fulfill({ json: confirmedProfile() });
    });
    await page.goto('/'); await review(page); await page.getByRole('button', { name: 'Confirm and save', exact: true }).click();
    await pending; await switchAccount(page);
    await expect(page.getByLabel('Desired job titles')).toHaveValue('');
    const completed = completion === 'network'
      ? page.waitForEvent('requestfailed', { predicate: (r) => r.method() === 'PUT' })
      : page.waitForResponse((r) => r.request().method() === 'PUT');
    release(); await completed;
    await page.evaluate(() => new Promise<void>((resolve) => requestAnimationFrame(() => requestAnimationFrame(() => resolve()))));
    await expect(page.getByLabel('Desired job titles')).toHaveValue('');
    await expect(page.getByText('Reopen JobPilot in Telegram')).toHaveCount(0);
    expect(writes).toBe(1); expect(state.saved.b).toBeNull();
  });
}

test('expired profile request clears private editor and requires a fresh Telegram launch', async ({ page }) => {
  await setup(page); await page.goto('/'); await review(page);
  await page.route('**/api/mini-app/v2/me/search-profile', async (r) => r.request().method() === 'PUT'
    ? r.fulfill({ status: 401, json: { category: 'UNAUTHENTICATED' } }) : r.fallback());
  await page.getByRole('button', { name: 'Confirm and save', exact: true }).click();
  await expect(page.getByText('Reopen JobPilot in Telegram')).toBeVisible();
  await expect(page.getByRole('heading', { name: 'Review your preferences' })).toHaveCount(0);
});

test('ambiguous save keeps its request id and draft for safe manual retry', async ({ page }) => {
  const state = await setup(page); await page.goto('/'); await review(page); const keys: string[] = [];
  await page.route('**/api/mini-app/v2/me/search-profile', async (r) => {
    if (r.request().method() !== 'PUT') return r.fallback();
    keys.push(r.request().postDataJSON().requestId);
    if (keys.length === 1) return r.abort('failed');
    return r.fallback();
  });
  await page.getByRole('button', { name: 'Confirm and save', exact: true }).click();
  await expect(page.getByRole('alert')).toContainText('not confirmed');
  await page.getByRole('button', { name: 'Confirm and save', exact: true }).click();
  await expect(page.getByRole('heading', { name: 'Shared vacancies' })).toBeVisible();
  expect(keys).toHaveLength(2); expect(new Set(keys).size).toBe(1); expect(state.writes).toHaveLength(1);
});

test('required choices and incomplete salary retain editable answers with clear errors', async ({ page }) => {
  await setup(page); await page.goto('/');
  await page.getByRole('button', { name: 'Next', exact: true }).click();
  await expect(page.getByRole('alert')).toContainText('at least one desired');
  await page.getByLabel('Desired job titles').fill('Engineer');
  await page.getByRole('button', { name: 'Next', exact: true }).click();
  await page.getByRole('button', { name: 'Next', exact: true }).click();
  await page.getByRole('button', { name: 'Next', exact: true }).click();
  await expect(page.getByRole('alert')).toContainText('target country');
  await page.getByLabel('Target countries or regions').fill('Europe');
  await page.getByLabel('Remote', { exact: true }).check();
  await page.getByRole('button', { name: 'Next', exact: true }).click();
  await page.getByLabel('Salary amount', { exact: true }).fill('3000');
  await page.getByRole('button', { name: 'Review preferences', exact: true }).click();
  await expect(page.getByRole('alert')).toContainText('Salary needs');
  await expect(page.getByLabel('Salary amount', { exact: true })).toHaveValue('3000');
  await page.getByRole('button', { name: 'Add language' }).click();
  await page.getByLabel('Language name', { exact: true }).fill('English');
  await expect(page.getByLabel('Proficiency', { exact: true })).toHaveValue('UNKNOWN');
});

test('legacy owner enters existing workspace without a profile and can edit using a v2 session', async ({ page }) => {
  await setup(page, null, true); await page.goto('/');
  await expect(page.getByRole('button', { name: 'Edit search preferences' })).toBeVisible();
  await expect(page.getByRole('heading', { name: 'Your search preferences' })).toHaveCount(0);
  await page.getByRole('button', { name: 'Edit search preferences' }).click();
  await expect(page.getByLabel('Desired job titles')).toHaveValue('');
  await page.getByRole('button', { name: 'Cancel editing' }).click();
  await expect(page.getByRole('button', { name: 'Edit search preferences' })).toBeVisible();
});

for (const completion of ['success', 'forbidden', 'network'] as const) {
  test(`late profile load ${completion} cannot expose the previous account`, async ({ page }) => {
    await setup(page); let release!: () => void; const held = new Promise<void>((r) => { release = r; });
    let arrived!: () => void; const pending = new Promise<void>((r) => { arrived = r; });
    await page.route('**/api/mini-app/v2/me/search-profile', async (r) => {
      if (r.request().headers().authorization !== `Bearer ${'a'.repeat(43)}`) return r.fallback();
      arrived(); await held;
      if (completion === 'forbidden') return r.fulfill({ status: 403 });
      if (completion === 'network') return r.abort('failed');
      return r.fulfill({ json: confirmedProfile() });
    });
    await page.goto('/'); await pending; await switchAccount(page);
    await expect(page.getByLabel('Desired job titles')).toHaveValue('');
    const completed = completion === 'network'
      ? page.waitForEvent('requestfailed', { predicate: (r) => r.url().endsWith('/me/search-profile') })
      : page.waitForResponse((r) => r.url().endsWith('/me/search-profile'));
    release(); await completed;
    await page.evaluate(() => new Promise<void>((resolve) => requestAnimationFrame(() => requestAnimationFrame(() => resolve()))));
    await expect(page.getByLabel('Desired job titles')).toHaveValue('');
    await expect(page.getByText('Reopen JobPilot in Telegram')).toHaveCount(0);
  });
}


test('country authorization and salary basis are explicit saved statements', async ({ page }) => {
  const state = await setup(page); await page.goto('/');
  await page.getByLabel('Desired job titles').fill('Marketing assistant');
  await page.getByRole('button', { name: 'Next', exact: true }).click();
  await page.getByRole('button', { name: 'Next', exact: true }).click();
  await page.getByLabel('Target countries or regions').fill('Romania');
  await page.getByLabel('Remote', { exact: true }).check();
  await page.getByRole('button', { name: 'Next', exact: true }).click();
  await page.getByRole('button', { name: 'Add country authorization' }).click();
  await page.getByLabel('Authorization country 1', { exact: true }).fill('ro');
  await page.getByLabel('Authorization status 1').selectOption('AUTHORIZED');
  await page.getByLabel('Salary basis', { exact: true }).selectOption('GROSS');
  await page.getByLabel('Salary amount', { exact: true }).fill('3000');
  await page.getByLabel('Salary currency', { exact: true }).fill('eur');
  await page.getByLabel('Salary period', { exact: true }).selectOption('MONTH');
  await page.getByRole('button', { name: 'Review preferences', exact: true }).click();
  await page.getByRole('button', { name: 'Confirm and save', exact: true }).click();
  await expect(page.getByRole('heading', { name: 'Shared vacancies' })).toBeVisible();
  expect(state.writes[0].body.profile.authorizationByCountry).toEqual([{ country: 'RO', status: 'AUTHORIZED' }]);
  expect(state.writes[0].body.profile.salary).toEqual({ amount: 3000, currency: 'EUR', period: 'MONTH', basis: 'GROSS' });
});

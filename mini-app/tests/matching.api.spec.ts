import { expect, test, type Page } from '@playwright/test';
import { confirmedProfile } from './searchProfileFixture';
import { SyntheticServer } from './synthetic';

const token = (id: string) => id.repeat(43);
const vacancy = (id = 8001, title = 'Personalized vacancy') => ({ id, title, company: 'Example', location: 'Romania', workFormat: 'REMOTE', employmentType: 'FULL_TIME', canonicalUrl: 'https://example.org/jobs/one', source: 'fixture', publishedAt: new Date().toISOString(), workflowStatus: 'UNREVIEWED' });
const state = { activeProfileRevision: 1, status: 'PROCESSING', extractorVersion: 'facts-1', scorerVersion: 'compatibility-1', publicationRevision: 1, catalogueRevision: 1, backfillAfterJobId: 0, backfillHighWaterJobId: 1, pendingChanges: 1, processingEnabled: true };
const item = (title = 'Personalized vacancy', eligibility = 'UNKNOWN') => ({ vacancy: vacancy(8001, title), match: { eligibility, compatibilityScore: 96, recommendationScore: eligibility === 'INELIGIBLE' ? null : 96, evidenceCoverage: 42, factors: { role: 35, skills: 35, experience: 15, preferences: 11 }, reasons: [{ code: 'WORK_AUTHORIZATION_UNKNOWN', dimension: 'AUTHORIZATION', status: 'UNKNOWN', explanation: 'Country-specific authorization is unconfirmed', evidence: 'Romania authorization required', candidateField: 'authorizationByCountry' }] }, computation: { searchProfileVersion: 1, jobRevision: 1, extractorVersion: 'facts-1', scorerVersion: 'compatibility-1', computedAt: new Date().toISOString() } });
async function setup(page: Page, response: () => object = () => ({ items: [item()], nextCursor: null, state })) {
  const server = new SyntheticServer([{ id: 8000, title: 'Neutral first vacancy', score: 0, status: 'UNREVIEWED' }, { id: 8001, title: 'Personalized vacancy', score: 0, status: 'UNREVIEWED' }]);
  await page.addInitScript(() => { (window as any).Telegram = { WebApp: { initData: 'launch-a', colorScheme: 'light', ready() {}, expand() {}, openLink() {}, onEvent() {}, offEvent() {}, BackButton: { show() {}, hide() {}, onClick() {}, offClick() {} } } }; });
  await page.route('**/api/mini-app/v2/**', async route => {
    const path = new URL(route.request().url()).pathname;
    if (path.endsWith('/auth/telegram')) return route.fulfill({ json: { accessToken: token(route.request().headers()['x-telegram-init-data'].startsWith('launch-b') ? 'b' : 'a'), expiresAt: new Date(Date.now() + 900000).toISOString() } });
    if (path.endsWith('/me')) return route.fulfill({ json: { accountId: route.request().headers().authorization === `Bearer ${token('b')}` ? 'b' : 'a', accountState: 'READY', onboardingRequired: false, legacyOwner: false, sessionExpiresAt: new Date(Date.now() + 900000).toISOString() } });
    if (path.endsWith('/me/search-profile')) return route.fulfill({ json: confirmedProfile() });
    if (path.endsWith('/matches')) return route.fulfill({ json: response() });
    if (path === '/api/mini-app/v2/jobs/8001') return route.fulfill({ json: { ...item(), facts: { description: 'Shared vacancy description.' }, state } });
    return server.route(route);
  });
  return server;
}
async function recommended(page: Page) { await page.goto('/'); await page.getByRole('button', { name: 'Recommended', exact: true }).click(); }

test('high compatibility stays visibly UNKNOWN with coverage and shared private workflow queue', async ({ page }) => {
  const server = await setup(page); await recommended(page);
  await expect(page.getByText('96% compatibility', { exact: true })).toBeVisible();
  await expect(page.getByText('Evidence coverage: 42%', { exact: true })).toBeVisible();
  await expect(page.getByText('Eligibility unknown', { exact: true })).toBeVisible();
  await expect(page.getByText(/not a hiring probability/)).toBeVisible();
  await page.getByRole('button', { name: 'Vacancy details', exact: true }).click();
  await expect(page.getByText('Shared vacancy description.')).toBeVisible();
  await page.getByRole('button', { name: 'Save', exact: true }).click();
  await expect.poll(() => server.jobs.find(x => x.id === 8001)?.status).toBe('SAVED');
  await page.getByRole('button', { name: 'Catalogue', exact: true }).click();
  await expect(page.getByRole('heading', { name: 'Neutral first vacancy' })).toBeVisible();
});
test('processing with no valid match never invents a percentage', async ({ page }) => {
  await setup(page, () => ({ items: [], nextCursor: null, state })); await recommended(page);
  await expect(page.getByText(/Matching is processing/)).toBeVisible();
  await expect(page.locator('body')).not.toContainText(/\d+% compatibility/);
});
test('ineligible result shows blocker without recommendation percentage', async ({ page }) => {
  await setup(page, () => ({ items: [item('Blocked vacancy', 'INELIGIBLE')], nextCursor: null, state })); await recommended(page);
  await expect(page.getByText('Ineligible', { exact: true })).toBeVisible();
  await expect(page.locator('body')).not.toContainText('96% compatibility');
});
for (const outcome of ['success', '401', 'network'] as const) test(`old matching ${outcome} cannot paint or expire replacement account`, async ({ page }) => {
  await setup(page);
  let release!: () => void; const wait = new Promise<void>(resolve => { release = resolve; }); let entered!: () => void; const started = new Promise<void>(resolve => { entered = resolve; });
  await page.route('**/api/mini-app/v2/matches*', async route => {
    if (route.request().headers().authorization === `Bearer ${token('a')}`) {
      entered(); await wait;
      if (outcome === 'network') return route.abort('failed');
      if (outcome === '401') return route.fulfill({ status: 401, json: { category: 'UNAUTHENTICATED' } });
      return route.fulfill({ json: { items: [item('Private A recommendation')], nextCursor: null, state } });
    }
    return route.fulfill({ json: { items: [item('Private B recommendation')], nextCursor: null, state } });
  });
  await recommended(page); await started;
  await page.evaluate(() => { (window as any).Telegram.WebApp.initData = 'launch-b'; window.dispatchEvent(new Event('focus')); });
  await expect(page.getByRole('button', { name: 'Recommended', exact: true })).toBeVisible();
  await page.getByRole('button', { name: 'Recommended', exact: true }).click();
  await expect(page.getByRole('heading', { name: 'Private B recommendation' })).toBeVisible();
  release(); await page.waitForTimeout(100);
  await expect(page.getByRole('heading', { name: 'Private B recommendation' })).toBeVisible();
  await expect(page.locator('body')).not.toContainText('Private A recommendation');
  await expect(page.getByText('Reopen JobPilot in Telegram')).toHaveCount(0);
});


test('pagination replaces one bounded page and stale results clear compatibility', async ({ page }) => {
  await setup(page, () => ({ items: [item('First page vacancy')], nextCursor: 'first-page', state }));
  await page.route('**/api/mini-app/v2/matches*', async route => {
    const cursor = new URL(route.request().url()).searchParams.get('cursor');
    if (cursor === 'stale-page') return route.fulfill({ status: 409, json: { category: 'MATCH_PAGE_STALE' } });
    return route.fulfill({ json: { items: [item(cursor ? 'Second page vacancy' : 'First page vacancy')], nextCursor: cursor ? 'stale-page' : 'first-page', state } });
  });
  await recommended(page);
  await page.getByRole('button', { name: 'Next recommendations' }).click();
  await expect(page.getByRole('heading', { name: 'Second page vacancy' })).toBeVisible();
  await expect(page.getByRole('heading', { name: 'First page vacancy' })).toHaveCount(0);
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
  await page.getByRole('button', { name: 'Next recommendations' }).click();
  await expect(page.getByText('Recommendations changed. Reload to continue.')).toBeVisible();
  await expect(page.locator('body')).not.toContainText('96% compatibility');
  await page.getByRole('button', { name: 'Refresh recommendations' }).click();
  await expect(page.getByRole('heading', { name: 'First page vacancy' })).toBeVisible();
});

import { expect, test, type Page, type Route } from '@playwright/test';
import { confirmedProfile } from './searchProfileFixture';
import { SyntheticServer } from './synthetic';

const token = (id: string) => id.repeat(43);
const defaults = () => ({ revision: 0, preferences: { enabled: false, paused: false, immediate: true, digest: false, minimumScore: 85, timezone: 'UTC', quietStart: null as string | null, quietEnd: null as string | null }, connectionStatus: 'DISCONNECTED', pauseReasons: ['DISABLED_BY_USER', 'CONNECT_BOT'], legacyManaged: false, eligibilityPolicy: 'ELIGIBLE_ONLY' });
const active = () => ({ ...defaults(), revision: 1, preferences: { ...defaults().preferences, enabled: true }, connectionStatus: 'CONNECTED', pauseReasons: [] });
const activation = () => ({ activationUrl: `https://t.me/example_jobpilot_bot?start=notify_${'x'.repeat(43)}`, expiresAt: new Date(Date.now() + 600000).toISOString() });
type View = ReturnType<typeof defaults>;
async function setup(page: Page, initial = defaults(), options: { expires?: number; owner?: boolean; intercept?: (route: Route) => Promise<boolean> } = {}) {
  let view = initial;
  const writes: Record<string, unknown>[] = [];
  const calls: string[] = [];
  const server = new SyntheticServer([{ id: 8000, title: 'Neutral first vacancy', score: 0, status: 'UNREVIEWED' }]);
  await page.addInitScript(() => { (window as any).Telegram = { WebApp: { initData: 'launch-a', colorScheme: 'light', ready() {}, expand() {}, openLink() {}, onEvent() {}, offEvent() {}, BackButton: { show() {}, hide() {}, onClick() {}, offClick() {} } } }; });
  await page.route('**/api/mini-app/v2/**', async route => {
    const path = new URL(route.request().url()).pathname;
    if (path.endsWith('/auth/telegram')) return route.fulfill({ json: { accessToken: token(route.request().headers()['x-telegram-init-data'].startsWith('launch-b') ? 'b' : 'a'), expiresAt: new Date(Date.now() + 900000).toISOString() } });
    if (path.endsWith('/me')) return route.fulfill({ json: { accountId: route.request().headers().authorization === `Bearer ${token('b')}` ? 'b' : 'a', accountState: 'READY', onboardingRequired: false, legacyOwner: options.owner ?? false, sessionExpiresAt: new Date(Date.now() + (options.expires ?? 900000)).toISOString() } });
    if (path.endsWith('/me/search-profile')) return route.fulfill({ json: confirmedProfile() });
    if (path.includes('/me/notifications')) {
      calls.push(`${route.request().method()} ${path}`);
      if (route.request().method() === 'PUT') writes.push(route.request().postDataJSON());
      if (options.intercept && await options.intercept(route)) return;
      if (path.endsWith('/activation')) return route.fulfill({ json: activation() });
      if (path.endsWith('/connection')) view = { ...view, revision: view.revision + 1, preferences: { ...view.preferences, enabled: false }, connectionStatus: 'DISCONNECTED', pauseReasons: ['DISABLED_BY_USER', 'CONNECT_BOT'] };
      if (route.request().method() === 'PUT') {
        const { expectedRevision, requestId, ...preferences } = route.request().postDataJSON();
        expect(expectedRevision).toBe(view.revision); expect(typeof requestId).toBe('string');
        view = { ...view, revision: view.revision + 1, preferences, pauseReasons: preferences.enabled ? [] : ['DISABLED_BY_USER'] };
      }
      return route.fulfill({ json: view });
    }
    return server.route(route);
  });
  return { writes, calls, set: (next: View) => { view = next; } };
}
async function settings(page: Page) { await page.goto('/'); await page.getByRole('button', { name: 'Settings', exact: true }).click(); await expect(page.getByRole('heading', { name: 'Notifications', exact: true })).toBeVisible(); }
const save = (page: Page) => page.getByRole('button', { name: 'Save notification settings', exact: true });
async function switchAccount(page: Page) { await page.evaluate(() => { (window as any).Telegram.WebApp.initData = 'launch-b'; window.dispatchEvent(new Event('focus')); }); await page.getByRole('button', { name: 'Settings', exact: true }).click(); }

test('default opted out; lazy settings, eligible new-only copy, collapsed advanced controls and 09:00 digest', async ({ page }) => {
  const api = await setup(page); await page.goto('/'); await expect(page.getByRole('heading', { name: 'Neutral first vacancy' })).toBeVisible(); expect(api.calls).toEqual([]);
  await page.getByRole('button', { name: 'Settings', exact: true }).click();
  await expect(page.getByRole('checkbox', { name: 'Allow Telegram notifications', exact: true })).not.toBeChecked();
  await expect(page.getByText('Notifications are off.', { exact: true })).toBeVisible();
  await expect(page.getByText(/Only eligible, newly discovered vacancies/)).toBeVisible();
  await expect(page.getByText(/All matches remain available in the Mini App/)).toBeVisible();
  await expect(page.getByLabel('Daily digest at 09:00 local time', { exact: true })).not.toBeChecked();
  await expect(page.getByText('Saved timezone: UTC. Timezone changes apply only when you save.', { exact: true })).toBeVisible();
  await expect(page.getByLabel('Minimum score', { exact: true })).not.toBeVisible();
  await page.screenshot({ path: test.info().outputPath('notifications-default.png'), fullPage: true });
  await page.getByText('Advanced notification settings', { exact: true }).click();
  await expect(page.getByLabel('Minimum score', { exact: true })).toHaveValue('85');
  await expect(page.getByLabel('Timezone', { exact: true })).toHaveValue('UTC');
  expect(api.writes).toEqual([]);
});

test('deliberate consent, safe activation link, explicit refresh, pause, disable and disconnect', async ({ page }) => {
  const api = await setup(page); await settings(page);
  await expect(page.getByRole('button', { name: 'Prepare bot connection', exact: true })).toBeDisabled();
  await page.getByLabel('Allow Telegram notifications', { exact: true }).check(); await save(page).click();
  await expect(page.getByText('Notification settings saved.', { exact: true })).toBeVisible();
  expect(api.writes[0]).toEqual({ expectedRevision: 0, requestId: expect.any(String), enabled: true, paused: false, immediate: true, digest: false, minimumScore: 85, timezone: 'UTC', quietStart: null, quietEnd: null });
  await expect(page.getByRole('link', { name: 'Open bot to connect', exact: true })).toHaveCount(0);
  await page.getByRole('button', { name: 'Prepare bot connection', exact: true }).click();
  await expect(page.getByRole('link', { name: 'Open bot to connect', exact: true })).toHaveAttribute('href', activation().activationUrl);
  const count = api.calls.length; await page.waitForTimeout(100); expect(api.calls).toHaveLength(count);
  api.set(active()); await page.getByRole('button', { name: 'Refresh notification status', exact: true }).click();
  await expect(page.getByText('Telegram connected.', { exact: true })).toBeVisible();
  await expect(page.getByRole('link', { name: 'Open bot to connect', exact: true })).toHaveCount(0);
  await page.getByLabel('Pause notifications', { exact: true }).check(); await save(page).click();
  expect(api.writes[1].paused).toBe(true);
  await page.getByLabel('Allow Telegram notifications', { exact: true }).uncheck(); await save(page).click(); expect(api.writes[2].enabled).toBe(false);
  await page.getByRole('button', { name: 'Disconnect Telegram', exact: true }).click();
  await expect(page.getByText('Telegram disconnected.', { exact: true })).toBeVisible();
  await expect(page.getByLabel('Allow Telegram notifications', { exact: true })).not.toBeChecked();
});

for (const failure of ['network', '500', 'malformed'] as const) test(`ambiguous ${failure} save retains draft and request identity without automatic retry`, async ({ page }) => {
  let attempt = 0;
  const api = await setup(page, defaults(), { intercept: async route => {
    if (route.request().method() !== 'PUT' || ++attempt > 1) return false;
    if (failure === 'network') await route.abort('failed'); else await route.fulfill({ status: failure === '500' ? 500 : 200, json: { broken: true } }); return true;
  } });
  await settings(page); await page.getByLabel('Allow Telegram notifications', { exact: true }).check(); await save(page).click();
  await expect(page.getByRole('alert')).toContainText('Save not confirmed');
  await expect(page.getByLabel('Allow Telegram notifications', { exact: true })).toBeChecked();
  await page.waitForTimeout(100); expect(api.writes).toHaveLength(1);
  await save(page).click(); await expect(page.getByText('Notification settings saved.', { exact: true })).toBeVisible();
  expect(api.writes).toHaveLength(2); expect(api.writes[1]).toEqual(api.writes[0]);
});

test('conflict blocks stale draft until explicit current-state reload', async ({ page }) => {
  const api = await setup(page, defaults(), { intercept: async route => { if (route.request().method() !== 'PUT') return false; await route.fulfill({ status: 409, json: { category: 'CONFLICT' } }); return true; } });
  await settings(page); await page.getByLabel('Allow Telegram notifications', { exact: true }).check(); await save(page).click();
  await expect(page.getByRole('alert')).toContainText('Settings changed'); await expect(save(page)).toBeDisabled();
  api.set({ ...defaults(), revision: 4, preferences: { ...defaults().preferences, minimumScore: 91 } });
  await page.getByRole('button', { name: 'Refresh notification status', exact: true }).click();
  await expect(page.getByLabel('Allow Telegram notifications', { exact: true })).not.toBeChecked();
  await page.getByText('Advanced notification settings', { exact: true }).click(); await expect(page.getByLabel('Minimum score', { exact: true })).toHaveValue('91');
  await expect(save(page)).toBeEnabled();
});

test('explicit refresh discards stale edits; changed payload receives a new request identity', async ({ page }) => {
  const api = await setup(page, defaults(), { intercept: async route => { if (route.request().method() !== 'PUT') return false; await route.abort('failed'); return true; } });
  await settings(page); await page.getByLabel('Allow Telegram notifications', { exact: true }).check(); await save(page).click(); await expect(page.getByRole('alert')).toBeVisible();
  await page.getByLabel('Daily digest at 09:00 local time', { exact: true }).check(); await save(page).click(); await expect.poll(() => api.writes.length).toBe(2);
  expect(api.writes[1].requestId).not.toBe(api.writes[0].requestId);
  await page.getByRole('button', { name: 'Refresh notification status', exact: true }).click(); await expect(page.getByLabel('Allow Telegram notifications', { exact: true })).not.toBeChecked();
});

test('advanced values validate before writes and timezone is only changed by deliberate save', async ({ page }) => {
  await page.emulateMedia({ reducedMotion: 'reduce' });
  const api = await setup(page); await settings(page); await page.getByText('Advanced notification settings', { exact: true }).click();
  await page.getByLabel('Quiet hours start', { exact: true }).fill('22:00'); await save(page).click(); await expect(page.getByRole('alert')).toContainText('both quiet-hour times'); expect(api.writes).toHaveLength(0);
  await page.getByLabel('Quiet hours end', { exact: true }).fill('22:00'); await save(page).click(); await expect(page.getByRole('alert')).toContainText('different');
  await page.getByLabel('Quiet hours end', { exact: true }).fill('07:00'); await page.getByLabel('Timezone', { exact: true }).fill('Europe/Bucharest'); expect(api.writes).toHaveLength(0);
  await page.getByLabel('Minimum score', { exact: true }).fill('90'); await save(page).click(); await expect(page.getByText('Notification settings saved.', { exact: true })).toBeVisible();
  expect(api.writes[0]).toMatchObject({ timezone: 'Europe/Bucharest', quietStart: '22:00', quietEnd: '07:00', minimumScore: 90 });
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
  await expect(page.getByLabel('Timezone', { exact: true })).toHaveAccessibleName('Timezone');
  expect(await page.evaluate(() => [localStorage.length, sessionStorage.length])).toEqual([0, 0]);
  await page.screenshot({ path: test.info().outputPath('notifications-advanced.png'), fullPage: true });
});

test('enabled consent needs a delivery mode and server validation preserves draft', async ({ page }) => {
  const api = await setup(page, defaults(), { intercept: async route => { if (route.request().method() !== 'PUT') return false; await route.fulfill({ status: 400, json: { category: 'VALIDATION' } }); return true; } });
  await settings(page); await page.getByLabel('Allow Telegram notifications', { exact: true }).check(); await page.getByLabel('Immediate notifications', { exact: true }).uncheck(); await save(page).click();
  await expect(page.getByRole('alert')).toContainText('Choose immediate notifications or the daily digest'); expect(api.writes).toHaveLength(0);
  await page.getByLabel('Daily digest at 09:00 local time', { exact: true }).check(); await save(page).click(); await expect(page.getByRole('alert')).toContainText('Check your notification settings');
  await expect(page.getByLabel('Daily digest at 09:00 local time', { exact: true })).toBeChecked();
});

for (const [reason, copy] of Object.entries({ DISABLED_BY_USER: 'Notifications are off.', PAUSED: 'Notifications are paused.', CONNECT_BOT: 'Connect the bot to receive notifications.', BLOCKED: 'Unblock the bot in Telegram, then reconnect.', UNAVAILABLE: 'Telegram delivery is unavailable. Reconnect to try again.', INCOMPLETE_PROFILE: 'Complete your search preferences to receive notifications.', BETA_CLOSED: 'Notifications are unavailable while beta access is closed.', SYSTEM_DISABLED: 'Notifications are temporarily unavailable.', RECOVERY: 'Notifications are unavailable during maintenance.', DISPATCH_DISARMED: 'Delivery is awaiting a service check.', UNCERTAIN_HELD: 'A previous delivery could not be confirmed and will not be resent automatically. This does not necessarily stop future notifications.', CONSENT_REVOKED: 'Permission was revoked. Save your consent again to resume.', LEGACY_MANAGED: 'Your existing owner alerts are managed by the original settings.' })) test(`safe status copy for ${reason}`, async ({ page }) => {
  await setup(page, { ...active(), pauseReasons: [reason] }); await settings(page); await expect(page.getByText(copy, { exact: true })).toBeVisible(); await expect(page.locator('body')).not.toContainText(reason);
});

for (const url of ['javascript:alert(1)', 'https://example.org/?start=notify_x', 'https://t.me/other/path?start=notify_x', `https://t.me/example_bot?start=notify_${'x'.repeat(43)}&next=evil`]) test(`reject unsafe activation destination ${url.split(':')[0]} ${url.length}`, async ({ page }) => {
  await setup(page, active(), { intercept: async route => { if (!route.request().url().endsWith('/activation')) return false; await route.fulfill({ json: { ...activation(), activationUrl: url } }); return true; } });
  await settings(page); await page.getByRole('button', { name: 'Prepare bot connection', exact: true }).click(); await expect(page.getByRole('alert')).toContainText('Bot connection could not be prepared'); await expect(page.getByRole('link', { name: 'Open bot to connect', exact: true })).toHaveCount(0);
});

for (const operation of ['read', 'save', 'activation', 'disconnect'] as const) for (const outcome of ['success', '401', '403', 'network'] as const) test(`old account ${operation} ${outcome} cannot paint or expire replacement account`, async ({ page }) => {
  let armed = operation === 'read'; let release!: () => void; const held = new Promise<void>(resolve => { release = resolve; }); let entered!: () => void; const started = new Promise<void>(resolve => { entered = resolve; });
  await setup(page, active(), { intercept: async route => {
    const request = route.request(); const path = new URL(request.url()).pathname;
    const target = operation === 'read' ? request.method() === 'GET' : operation === 'save' ? request.method() === 'PUT' : path.endsWith(operation === 'activation' ? '/activation' : '/connection');
    if (!armed || !target || request.headers().authorization !== `Bearer ${token('a')}`) return false;
    entered(); await held;
    if (outcome === 'network') await route.abort('failed'); else if (outcome !== 'success') await route.fulfill({ status: Number(outcome), json: { category: 'UNAUTHENTICATED' } });
    else await route.fulfill({ json: operation === 'activation' ? activation() : { ...active(), preferences: { ...active().preferences, timezone: 'Asia/Tokyo' } } }); return true;
  } });
  await page.goto('/'); await page.getByRole('button', { name: 'Settings', exact: true }).click();
  if (operation !== 'read') {
    await expect(page.getByRole('heading', { name: 'Notifications', exact: true })).toBeVisible(); armed = true;
    if (operation === 'save') { await page.getByLabel('Pause notifications', { exact: true }).check(); await save(page).click(); }
    else await page.getByRole('button', { name: operation === 'activation' ? 'Prepare bot connection' : 'Disconnect Telegram', exact: true }).click();
  }
  await started; await switchAccount(page); await expect(page.getByText('Telegram connected.', { exact: true })).toBeVisible(); release(); await page.waitForTimeout(100);
  await expect(page.getByText('Telegram connected.', { exact: true })).toBeVisible(); await expect(page.getByText('Reopen JobPilot in Telegram')).toHaveCount(0); await expect(page.getByRole('link', { name: 'Open bot to connect', exact: true })).toHaveCount(0);
  await page.getByText('Advanced notification settings', { exact: true }).click(); await expect(page.getByLabel('Timezone', { exact: true })).toHaveValue('UTC');
});

for (const status of [401, 403]) test(`current settings ${status} clears authenticated workspace`, async ({ page }) => {
  await setup(page, defaults(), { intercept: async route => { await route.fulfill({ status, json: { category: 'UNAUTHENTICATED' } }); return true; } });
  await page.goto('/'); await page.getByRole('button', { name: 'Settings', exact: true }).click(); await expect(page.getByText('Reopen JobPilot in Telegram')).toBeVisible(); await expect(page.getByLabel('Allow Telegram notifications', { exact: true })).toHaveCount(0);
});

test('session expiry removes draft and activation link without a save', async ({ page }) => {
  const api = await setup(page, active(), { expires: 2000 }); await settings(page); await page.getByRole('button', { name: 'Prepare bot connection', exact: true }).click(); await expect(page.getByRole('link', { name: 'Open bot to connect', exact: true })).toBeVisible();
  await expect(page.getByText('Reopen JobPilot in Telegram')).toBeVisible({ timeout: 6000 }); await expect(page.getByRole('link', { name: 'Open bot to connect', exact: true })).toHaveCount(0); expect(api.writes).toHaveLength(0);
});

test('unknown status reason is safe plain language, including object-property names', async ({ page }) => {
  await setup(page, { ...active(), pauseReasons: ['FUTURE_SAFE_REASON', '__proto__'] }); await settings(page);
  await expect(page.getByText('Some notifications are temporarily unavailable.', { exact: true })).toBeVisible();
  await expect(page.locator('body')).not.toContainText('FUTURE_SAFE_REASON'); await expect(page.locator('body')).not.toContainText('__proto__');
});

test('native Telegram handoff happens only on deliberate connection-link tap', async ({ page }) => {
  await setup(page, active()); await settings(page);
  await page.evaluate(() => { (window as any).notificationHandoffs = []; (window as any).Telegram.WebApp.openTelegramLink = (url: string) => (window as any).notificationHandoffs.push(url); });
  await page.getByRole('button', { name: 'Prepare bot connection', exact: true }).click();
  await expect(page.getByRole('link', { name: 'Open bot to connect', exact: true })).toBeVisible();
  expect(await page.evaluate(() => (window as any).notificationHandoffs)).toEqual([]);
  await page.getByRole('link', { name: 'Open bot to connect', exact: true }).click();
  expect(await page.evaluate(() => (window as any).notificationHandoffs)).toEqual([activation().activationUrl]);
});

test.describe('device timezone confirmation', () => {
  test.use({ timezoneId: 'Europe/Bucharest' });
  test('detected timezone only enters draft on choice and persists on save', async ({ page }) => {
    const api = await setup(page); await settings(page); await page.getByText('Advanced notification settings', { exact: true }).click();
    await expect(page.getByLabel('Timezone', { exact: true })).toHaveValue('UTC'); expect(api.writes).toHaveLength(0);
    await page.getByRole('button', { name: 'Use device timezone (Europe/Bucharest)', exact: true }).click();
    await expect(page.getByLabel('Timezone', { exact: true })).toHaveValue('Europe/Bucharest'); expect(api.writes).toHaveLength(0);
    await save(page).click(); await expect(page.getByText('Notification settings saved.', { exact: true })).toBeVisible(); expect(api.writes[0].timezone).toBe('Europe/Bucharest');
  });
});

for (const outcome of ['success', 'broken-json'] as const) test(`old settings body ${outcome} cannot affect replacement account`, async ({ page }) => {
  await setup(page, active()); await page.goto('/');
  await page.evaluate(({ bearer, broken }) => {
    const original = window.fetch;
    (window as any).releaseNotificationBody = null; (window as any).notificationBodyStarted = false;
    window.fetch = async (...args) => {
      const response = await original(...args);
      const headers = new Headers(args[1]?.headers);
      if (String(args[0]).endsWith('/me/notifications') && headers.get('Authorization') === bearer) {
        const json = response.json.bind(response);
        response.json = async () => {
          (window as any).notificationBodyStarted = true;
          await new Promise<void>(resolve => { (window as any).releaseNotificationBody = resolve; });
          if (broken) throw new Error('synthetic body failure');
          return { ...(await json()), preferences: { ...activePreferences(), timezone: 'Asia/Tokyo' } };
        };
      }
      return response;
    };
    function activePreferences() { return { enabled: true, paused: false, immediate: true, digest: false, minimumScore: 85, timezone: 'UTC', quietStart: null, quietEnd: null }; }
  }, { bearer: `Bearer ${token('a')}`, broken: outcome === 'broken-json' });
  await page.getByRole('button', { name: 'Settings', exact: true }).click(); await expect.poll(() => page.evaluate(() => (window as any).notificationBodyStarted)).toBe(true);
  await switchAccount(page); await expect(page.getByText('Telegram connected.', { exact: true })).toBeVisible();
  await page.evaluate(() => (window as any).releaseNotificationBody()); await page.waitForTimeout(100);
  await expect(page.getByText('Reopen JobPilot in Telegram')).toHaveCount(0); await page.getByText('Advanced notification settings', { exact: true }).click(); await expect(page.getByLabel('Timezone', { exact: true })).toHaveValue('UTC');
});

test('failed initial settings read offers explicit reload and never silently retries', async ({ page }) => {
  let failed = true;
  const api = await setup(page, defaults(), { intercept: async route => { if (!failed) return false; await route.abort('failed'); return true; } });
  await settings(page); await expect(page.getByRole('alert')).toContainText('could not be loaded'); const count = api.calls.length; await page.waitForTimeout(100); expect(api.calls).toHaveLength(count);
  failed = false; await page.getByRole('button', { name: 'Refresh notification status', exact: true }).click(); await expect(page.getByLabel('Allow Telegram notifications', { exact: true })).not.toBeChecked();
});

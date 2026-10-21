// Opt-in real browser -> API -> PostgreSQL rehearsal. No HTTP responses are stubbed.
import { createHmac, randomUUID } from 'node:crypto';
import { chromium, expect } from '@playwright/test';

const backend = new URL(process.argv[2]);
if (backend.protocol !== 'http:' || backend.hostname !== '127.0.0.1') {
  throw new Error('This synthetic rehearsal accepts only a loopback disposable backend.');
}
const BOT = '123456:test-bot-token-not-a-real-secret';
function launch(user) {
  const fields = { auth_date: String(Math.floor(Date.now() / 1000)), query_id: randomUUID(), user: JSON.stringify({ id: user }) };
  const data = Object.entries(fields).sort(([a], [b]) => a.localeCompare(b)).map(([key, value]) => `${key}=${value}`).join('\n');
  const secret = createHmac('sha256', 'WebAppData').update(BOT).digest();
  return new URLSearchParams({ ...fields, hash: createHmac('sha256', secret).update(data).digest('hex') }).toString();
}
const base = new URL('/mini-app/', backend).href;
const browser = await chromium.launch();
try {
  async function account(id) {
    const context = await browser.newContext({ viewport: { width: 390, height: 844 } });
    await context.route('https://telegram.org/js/telegram-web-app.js*', route => route.abort());
    await context.addInitScript((data) => {
      window.__telegramLinks = [];
      window.Telegram = { WebApp: { initData: data, colorScheme: 'light', ready() {}, expand() {}, openLink() {}, openTelegramLink(url) { window.__telegramLinks.push(url); },
        onEvent() {}, offEvent() {}, BackButton: { show() {}, hide() {}, onClick() {}, offClick() {} } } };
    }, launch(id));
    const page = await context.newPage();
    await page.goto(base);
    await expect(page.getByRole('heading', { name: 'Your search preferences' })).toBeVisible();
    await page.getByLabel('Desired job titles').fill(id === 9002 ? 'Browser backend engineer' : 'Browser product designer');
    await page.getByRole('button', { name: 'Next', exact: true }).click();
    await page.getByRole('button', { name: 'Next', exact: true }).click();
    await page.getByLabel('Target countries or regions').fill('Romania');
    await page.getByLabel('Remote', { exact: true }).check();
    await page.getByRole('button', { name: 'Next', exact: true }).click();
    await page.getByRole('button', { name: 'Review preferences', exact: true }).click();
    await page.getByRole('button', { name: 'Confirm and save', exact: true }).click();
    await expect(page.getByRole('heading', { name: 'Shared vacancies' })).toBeVisible();
    await expect(page.getByRole('heading', { name: 'Browser shared vacancy' })).toBeVisible();
    return page;
  }
  const a = await account(9002), b = await account(9003);
  await a.getByRole('button', { name: 'Save', exact: true }).click();
  await expect(a.getByText('Saved: 1 · Applied: 0 · Dismissed: 0', { exact: true })).toBeVisible();
  await expect(b.getByRole('button', { name: 'Save', exact: true })).toBeVisible();
  await b.getByRole('button', { name: 'Dismiss', exact: true }).click();
  await expect(b.getByText('Saved: 0 · Applied: 0 · Dismissed: 1', { exact: true })).toBeVisible();
  await a.getByRole('button', { name: 'Saved', exact: true }).click();
  await a.getByRole('button', { name: 'Mark applied', exact: true }).click();
  await expect(a.getByText('Saved: 0 · Applied: 1 · Dismissed: 0', { exact: true })).toBeVisible();
  await a.getByRole('button', { name: 'Undo', exact: true }).click();
  await expect(a.getByText('Saved: 1 · Applied: 0 · Dismissed: 0', { exact: true })).toBeVisible();
  await b.getByRole('button', { name: 'Refresh vacancies', exact: true }).click();
  await expect(b.getByText('Saved: 0 · Applied: 0 · Dismissed: 1', { exact: true })).toBeVisible();
  await b.getByRole('button', { name: 'Applications', exact: true }).click();
  await expect(b.getByText('No applications yet.', { exact: true })).toBeVisible();
  await a.evaluate((data) => { window.Telegram.WebApp.initData = data; window.dispatchEvent(new Event('focus')); }, launch(9002));
  await expect(a.getByText('Saved: 1 · Applied: 0 · Dismissed: 0', { exact: true })).toBeVisible();
  await a.getByRole('button', { name: 'Saved', exact: true }).click();
  await expect(a.getByRole('heading', { name: 'Browser shared vacancy' })).toBeVisible();
  await a.getByRole('button', { name: 'Edit search preferences' }).click();
  await expect(a.getByLabel('Desired job titles')).toHaveValue('Browser backend engineer');
  await a.getByRole('button', { name: 'Cancel editing' }).click();
  await a.reload();
  await expect(a.getByText('Reopen JobPilot in Telegram')).toBeVisible();
  await a.evaluate((data) => { window.Telegram.WebApp.initData = data; window.dispatchEvent(new Event('focus')); }, launch(9002));
  await expect(a.getByRole('heading', { name: 'Shared vacancies' })).toBeVisible();
  await a.getByRole('button', { name: 'Edit search preferences' }).click();
  await expect(a.getByLabel('Desired job titles')).toHaveValue('Browser backend engineer');
  await a.getByRole('button', { name: 'Cancel editing' }).click();
  for (const page of [a, b]) {
    await expect(page.getByRole('img', { name: /Match score/ })).toHaveCount(0);
    expect(await page.evaluate(() => [localStorage.length, sessionStorage.length])).toEqual([0, 0]);
  }
  for (const page of [a, b]) {
    await page.getByRole('button', { name: 'Recommended', exact: true }).click();
    await expect(async () => {
      await page.getByRole('button', { name: 'Refresh recommendations', exact: true }).click();
      await expect(page.getByRole('heading', { name: 'Browser shared vacancy' })).toBeVisible();
    }).toPass({ timeout: 30000, intervals: [500, 1000] });
    await expect(page.getByText(/not a hiring probability/)).toBeVisible();
    await expect(page.getByText(/Evidence coverage: \d+%/)).toBeVisible();
    await expect(page.getByText('Eligibility unknown', { exact: true })).toBeVisible();
    await expect(page.getByText(/\d+% compatibility/)).toBeVisible();
    await page.getByRole('button', { name: 'Vacancy details', exact: true }).click();
    await expect(page.locator('.matching-description')).toBeVisible();
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
  }
  if (process.argv[3]) await a.screenshot({ path: process.argv[3], fullPage: true });
  async function settings(page) {
    await page.getByRole('button', { name: 'Settings', exact: true }).click();
    await expect(page.getByRole('heading', { name: 'Notifications', exact: true })).toBeVisible();
    await expect(page.getByRole('button', { name: 'Save notification settings' })).toBeEnabled();
  }
  async function save(page) {
    await page.getByRole('button', { name: 'Save notification settings', exact: true }).click();
    await expect(page.getByText('Notification settings saved.', { exact: true })).toBeVisible();
  }
  async function freshReload(page, user) {
    await page.reload();
    await expect(page.getByText('Reopen JobPilot in Telegram')).toBeVisible();
    await page.evaluate((data) => { window.Telegram.WebApp.initData = data; window.dispatchEvent(new Event('focus')); }, launch(user));
    await expect(page.getByRole('button', { name: 'Settings', exact: true })).toBeVisible();
    await settings(page);
  }
  await settings(a); await settings(b);
  for (const page of [a,b]) {
    await expect(page.getByLabel('Allow Telegram notifications', { exact: true })).not.toBeChecked();
    await expect(page.getByLabel('Minimum score', { exact: true })).not.toBeVisible();
    await expect(page.getByText(/Saved timezone: UTC/)).toBeVisible();
  }
  await a.getByLabel('Allow Telegram notifications', { exact: true }).check();
  await a.getByLabel('Daily digest at 09:00 local time', { exact: true }).check();
  await a.getByText('Advanced notification settings', { exact: true }).click();
  await a.getByLabel('Minimum score', { exact: true }).fill('91');
  await a.getByLabel('Timezone', { exact: true }).fill('Europe/Bucharest');
  await a.getByLabel('Quiet hours start', { exact: true }).fill('22:30');
  await a.getByLabel('Quiet hours end', { exact: true }).fill('07:15');
  await save(a);
  expect(await a.evaluate(() => window.__telegramLinks)).toEqual([]);
  await expect(a.getByRole('link', { name: 'Open bot to connect' })).toHaveCount(0);
  await a.getByRole('button', { name: 'Prepare bot connection', exact: true }).click();
  await expect(a.getByRole('link', { name: 'Open bot to connect' })).toHaveAttribute('href', /^https:\/\/t\.me\/JobPilotTestBot\?start=notify_[A-Za-z0-9_-]{43}$/);
  expect(await a.evaluate(() => window.__telegramLinks)).toEqual([]);
  await a.getByRole('link', { name: 'Open bot to connect' }).click();
  expect(await a.evaluate(() => window.__telegramLinks.length)).toBe(1);
  expect(a.url()).toBe(base);
  await b.getByRole('button', { name: 'Refresh notification status', exact: true }).click();
  await expect(b.getByLabel('Allow Telegram notifications', { exact: true })).not.toBeChecked();
  await b.getByLabel('Allow Telegram notifications', { exact: true }).check();
  await b.getByLabel('Immediate notifications', { exact: true }).uncheck();
  await b.getByLabel('Daily digest at 09:00 local time', { exact: true }).check();
  await b.getByText('Advanced notification settings', { exact: true }).click();
  await b.getByLabel('Minimum score', { exact: true }).fill('87');
  await b.getByLabel('Timezone', { exact: true }).fill('Asia/Kathmandu');
  await save(b);
  await freshReload(a,9002);
  await expect(a.getByLabel('Allow Telegram notifications', { exact: true })).toBeChecked();
  await expect(a.getByText(/Saved timezone: Europe\/Bucharest/)).toBeVisible();
  await expect(a.getByLabel('Minimum score', { exact: true })).not.toBeVisible();
  await a.getByText('Advanced notification settings', { exact: true }).click();
  await expect(a.getByLabel('Minimum score', { exact: true })).toHaveValue('91');
  await expect(a.getByLabel('Timezone', { exact: true })).toHaveValue('Europe/Bucharest');
  await expect(a.getByLabel('Quiet hours start', { exact: true })).toHaveValue('22:30');
  await expect(a.getByLabel('Quiet hours end', { exact: true })).toHaveValue('07:15');
  await a.getByLabel('Pause notifications', { exact: true }).check(); await save(a);
  await a.getByLabel('Pause notifications', { exact: true }).uncheck(); await save(a);
  await a.getByRole('button', { name: 'Disconnect Telegram', exact: true }).click();
  await expect(a.getByLabel('Allow Telegram notifications', { exact: true })).not.toBeChecked();
  await freshReload(a,9002);
  await expect(a.getByLabel('Allow Telegram notifications', { exact: true })).not.toBeChecked();
  await b.getByRole('button', { name: 'Refresh notification status', exact: true }).click();
  await expect(b.getByLabel('Allow Telegram notifications', { exact: true })).toBeChecked();
  await expect(b.getByLabel('Immediate notifications', { exact: true })).not.toBeChecked();
  await expect(b.getByLabel('Timezone', { exact: true })).toHaveValue('Asia/Kathmandu');
  for (const page of [a,b]) {
    expect(await page.evaluate(() => [localStorage.length, sessionStorage.length])).toEqual([0,0]);
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
  }
  if(process.argv[3]) await a.screenshot({ path: process.argv[3].replace(/[^/]+$/, 'notification-settings-mobile.png'), fullPage: true });
  console.log('PASS: packaged image assets → real notification API → PostgreSQL; opt-out defaults, advanced preferences, deliberate connection tap, pause/resume/disconnect, reload, two-account isolation and mobile/storage bounds.');
  console.log('PASS: real Recommended browser → authenticated API → persisted matching → owned workflow, UNKNOWN/coverage, detail and mobile bounds.');
  console.log('PASS: two real browser accounts independently save/dismiss/apply/undo one PostgreSQL vacancy and restore their own state after a fresh launch.');
} finally {
  await browser.close();
}

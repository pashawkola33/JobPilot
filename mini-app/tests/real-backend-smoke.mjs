// Opt-in real browser -> API -> PostgreSQL rehearsal. No HTTP responses are stubbed.
import { createHmac, randomUUID } from 'node:crypto';
import { createServer } from 'vite';
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
process.env.VITE_JOBPILOT_MODE = 'api';
const server = await createServer({ server: { host: '127.0.0.1', port: 0, proxy: { '/api': backend.origin } } });
await server.listen();
const base = `http://127.0.0.1:${server.httpServer.address().port}`;
const browser = await chromium.launch();
try {
  async function account(id) {
    const context = await browser.newContext({ viewport: { width: 390, height: 844 } });
    await context.addInitScript((data) => {
      window.Telegram = { WebApp: { initData: data, colorScheme: 'light', ready() {}, expand() {}, openLink() {},
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
  console.log('PASS: real Recommended browser → authenticated API → persisted matching → owned workflow, UNKNOWN/coverage, detail and mobile bounds.');
  console.log('PASS: two real browser accounts independently save/dismiss/apply/undo one PostgreSQL vacancy and restore their own state after a fresh launch.');
} finally {
  await browser.close();
  await server.close();
}

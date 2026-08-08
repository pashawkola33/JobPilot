import { expect, test, type Page } from '@playwright/test';
import {
  emulateTelegram,
  openDetailsSheet,
  readSheetGeometry,
  readSwipeCalls,
  scrollSheetToEnd,
} from './telegram';

/**
 * Telegram iOS reads a vertical drag anywhere in the Mini App as "minimize me", so it
 * competes with the details sheet's own scrolling: on a real iPhone, trying to scroll the
 * sheet minimizes the app instead. Bot API 7.7 exists for exactly this — the host hands the
 * gesture back while `disableVerticalSwipes()` is in force, and the Telegram header still
 * minimizes, so the user is never trapped.
 *
 * The rule this file pins is narrow and symmetric: **disabled exactly while the sheet is
 * open, restored on every path out of it.** The host exposes no readable flag, so the
 * ordered call log the emulator records is the observable — a leak is a missing `enable`.
 */

const HOME_INDICATOR = 34;
const TELEGRAM_HEADER = 103;

const visible = (page: Page) => page.viewportSize()!.height - 200;

const telegram = (page: Page, verticalSwipes?: 'unsupported') =>
  emulateTelegram(page, {
    visible: visible(page),
    contentSafeAreaTop: TELEGRAM_HEADER,
    safeAreaBottom: HOME_INDICATOR,
    ...(verticalSwipes ? { verticalSwipes } : {}),
  });

/** Reopens an already-loaded review screen's sheet, without a navigation that would reset the log. */
async function reopenDetails(page: Page) {
  await page.getByRole('button', { name: 'Full details' }).click();
  await page.waitForSelector('dialog.sheet[open]');
  await page.waitForTimeout(350);
}

test('opening the details sheet disables the host vertical swipe, once', async ({ page }) => {
  await telegram(page);
  await openDetailsSheet(page);

  await expect(page.getByRole('dialog')).toBeVisible();
  // Exactly one call, and nothing has handed the gesture back while the sheet is up.
  expect(await readSwipeCalls(page)).toEqual(['disable']);
});

test('Close details restores the host vertical swipe', async ({ page }) => {
  await telegram(page);
  await openDetailsSheet(page);

  await page.getByRole('button', { name: 'Close details' }).click();
  await expect(page.getByRole('dialog')).not.toBeVisible();

  expect(await readSwipeCalls(page)).toEqual(['disable', 'enable']);
});

/**
 * Esc closes the <dialog> through the platform rather than through our close button, so the
 * selected job goes null by a path the button never touches. The restore has to ride on the
 * sheet being open, not on any one control being pressed.
 */
test('a close the button never triggered still restores the swipe', async ({ page }) => {
  await telegram(page);
  await openDetailsSheet(page);

  await page.keyboard.press('Escape');
  await expect(page.getByRole('dialog')).not.toBeVisible();

  expect(await readSwipeCalls(page)).toEqual(['disable', 'enable']);
});

/** The last path out: the component goes away while the sheet is still open. */
test('unmounting while the sheet is open restores the swipe', async ({ page }) => {
  await telegram(page);
  await openDetailsSheet(page);
  expect(await readSwipeCalls(page)).toEqual(['disable']);

  await page.evaluate(() => (window as unknown as { __unmountApp(): void }).__unmountApp());

  expect(await readSwipeCalls(page)).toEqual(['disable', 'enable']);
});

/**
 * The point of disabling the host gesture is that ours starts working — so the sheet's own
 * scrolling and its actions must be unaffected by it. No custom gesture code is involved:
 * `.sheet__body` is an ordinary scroll container and stays one.
 */
test('the sheet still scrolls internally while the host swipe is disabled', async ({ page }) => {
  await telegram(page);
  await openDetailsSheet(page);

  const before = await readSheetGeometry(page);
  expect(before.bodyMaxScroll).toBeGreaterThan(0);
  expect(before.bodyScrollTop).toBe(0);

  await scrollSheetToEnd(page);

  const after = await readSheetGeometry(page);
  expect(after.bodyScrollTop).toBe(after.bodyMaxScroll);
  // The sheet itself did not move, and the gesture is still ours for the whole scroll.
  expect(after.sheet.top).toBe(before.sheet.top);
  expect(await readSwipeCalls(page)).toEqual(['disable']);
});

test('the final action stays reachable and clickable with the host swipe disabled', async ({
  page,
}) => {
  await telegram(page);
  await openDetailsSheet(page);
  await scrollSheetToEnd(page);

  const sheet = await readSheetGeometry(page);
  expect(sheet.lastAction.bottom).toBeLessThanOrEqual(sheet.visible);

  const action = page.getByRole('dialog').getByRole('link', { name: 'Open vacancy' });
  await expect(action).toBeVisible();
  await action.click();
  // Telegram opens the link itself, so the click is consumed rather than navigating away —
  // the sheet is still up, and still holding the gesture.
  await expect(page.getByRole('dialog')).toBeVisible();
  expect(await readSwipeCalls(page)).toEqual(['disable']);
});

/** Before Bot API 7.7 the host has neither method. Feature detection, not a version check. */
test('an older Telegram client without swipe control is a silent no-op', async ({ page }) => {
  const errors: string[] = [];
  page.on('pageerror', (error) => errors.push(error.message));

  await telegram(page, 'unsupported');
  await openDetailsSheet(page);

  await expect(page.getByRole('dialog')).toBeVisible();
  await scrollSheetToEnd(page);
  expect((await readSheetGeometry(page)).bodyScrollTop).toBeGreaterThan(0);

  await page.getByRole('button', { name: 'Close details' }).click();
  await expect(page.getByRole('dialog')).not.toBeVisible();

  expect(await readSwipeCalls(page)).toEqual([]);
  expect(errors).toEqual([]);
});

/**
 * The state that matters is the host's, and it survives our component. Two cycles must leave
 * it exactly where one did: strictly alternating, never twice in a row.
 */
test('reopening alternates disable and enable with nothing left behind', async ({ page }) => {
  await telegram(page);
  await openDetailsSheet(page);
  await page.getByRole('button', { name: 'Close details' }).click();
  await expect(page.getByRole('dialog')).not.toBeVisible();

  await reopenDetails(page);
  await expect(page.getByRole('dialog')).toBeVisible();
  expect(await readSwipeCalls(page)).toEqual(['disable', 'enable', 'disable']);

  await page.getByRole('button', { name: 'Close details' }).click();
  await expect(page.getByRole('dialog')).not.toBeVisible();
  expect(await readSwipeCalls(page)).toEqual(['disable', 'enable', 'disable', 'enable']);
});

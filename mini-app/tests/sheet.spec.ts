import { expect, test } from '@playwright/test';
import {
  emulateTelegram,
  openDetailsSheet,
  readSheetGeometry,
  scrollSheetToEnd,
  setStableViewport,
} from './telegram';

/**
 * The details sheet is a modal <dialog>, so it lives in the top layer and its
 * containing block is the WebView viewport, not `.app`. On Telegram iOS the WebView
 * is taller than the visible area, so anything the sheet sizes in `dvh` overshoots
 * and the bottom of the sheet — including its final action — ends up off screen.
 *
 * Every assertion here is against `viewportStableHeight`, which is what the user can
 * actually see, rather than `innerHeight`.
 */

/** How much of the WebView Telegram keeps off screen. 0 is every non-iOS client. */
const OFFSCREEN = [0, 34, 90, 200] as const;

const TELEGRAM_HEADER = 103;
const HOME_INDICATOR = 34;

const innerHeight = (page: import('@playwright/test').Page) => page.viewportSize()!.height;

for (const offscreen of OFFSCREEN) {
  test(`sheet stays inside the visible viewport with ${offscreen}px off screen`, async ({
    page,
  }) => {
    await emulateTelegram(page, {
      visible: innerHeight(page) - offscreen,
      contentSafeAreaTop: offscreen > 0 ? TELEGRAM_HEADER : 0,
      safeAreaBottom: HOME_INDICATOR,
    });
    await openDetailsSheet(page);

    const sheet = await readSheetGeometry(page);

    expect(sheet.visible).toBe(innerHeight(page) - offscreen);
    expect(sheet.sheet.bottom).toBeLessThanOrEqual(sheet.visible);
    expect(sheet.sheet.top).toBeGreaterThanOrEqual(sheet.safeTop);
    // The grip and the header ride with the sheet, so they clear the host header too.
    expect(sheet.grip.top).toBeGreaterThanOrEqual(sheet.safeTop);
    expect(sheet.head.bottom).toBeLessThanOrEqual(sheet.visible);
  });
}

test('the close control is reachable and closes the sheet', async ({ page }) => {
  await emulateTelegram(page, {
    visible: innerHeight(page) - 200,
    contentSafeAreaTop: TELEGRAM_HEADER,
    safeAreaBottom: HOME_INDICATOR,
  });
  await openDetailsSheet(page);

  const sheet = await readSheetGeometry(page);
  expect(sheet.close.top).toBeGreaterThanOrEqual(sheet.safeTop);
  expect(sheet.close.bottom).toBeLessThanOrEqual(sheet.visible);
  expect(sheet.closeClickable).toBe(true);

  await page.getByRole('button', { name: 'Close details' }).click();
  await expect(page.getByRole('dialog')).not.toBeVisible();
});

test('long content scrolls inside the sheet body', async ({ page }) => {
  await emulateTelegram(page, { visible: innerHeight(page) - 200, safeAreaBottom: HOME_INDICATOR });
  await openDetailsSheet(page);

  const before = await readSheetGeometry(page);
  expect(before.bodyMaxScroll).toBeGreaterThan(0);
  expect(before.bodyScrollTop).toBe(0);

  await scrollSheetToEnd(page);

  const after = await readSheetGeometry(page);
  expect(after.bodyScrollTop).toBe(after.bodyMaxScroll);
  // The sheet itself does not move when its content does.
  expect(after.sheet.top).toBe(before.sheet.top);
  expect(after.sheet.bottom).toBe(before.sheet.bottom);
});

test('the final action can be scrolled into view', async ({ page }) => {
  await emulateTelegram(page, {
    visible: innerHeight(page) - 200,
    contentSafeAreaTop: TELEGRAM_HEADER,
    safeAreaBottom: HOME_INDICATOR,
  });
  await openDetailsSheet(page);
  await scrollSheetToEnd(page);

  const sheet = await readSheetGeometry(page);
  expect(sheet.lastAction.bottom).toBeLessThanOrEqual(sheet.visible);
  await expect(page.getByRole('dialog').getByRole('link', { name: 'Open vacancy' })).toBeVisible();
});

test('the background does not scroll while the sheet is open', async ({ page }) => {
  await emulateTelegram(page, { visible: innerHeight(page) - 200, safeAreaBottom: HOME_INDICATOR });
  await openDetailsSheet(page);

  const before = await readSheetGeometry(page);
  expect(before.docScrollTop).toBe(0);
  expect(before.docScrollHeight).toBe(before.innerHeight);

  await page.mouse.move(10, 10);
  await page.mouse.wheel(0, 600);
  await page.waitForTimeout(120);

  const after = await readSheetGeometry(page);
  expect(after.docScrollTop).toBe(0);
  expect(after.docScrollHeight).toBe(after.innerHeight);
});

test('the bottom safe area is padded inside the sheet body', async ({ page }) => {
  await emulateTelegram(page, { visible: innerHeight(page) - 90, safeAreaBottom: HOME_INDICATOR });
  await openDetailsSheet(page);

  const sheet = await readSheetGeometry(page);
  expect(sheet.safeBottom).toBe(HOME_INDICATOR);
  expect(sheet.body.paddingBottom).toBeGreaterThanOrEqual(HOME_INDICATOR);
  expect(sheet.body.bottom).toBeLessThanOrEqual(sheet.visible);
});

test('a viewport change resizes the open sheet', async ({ page }) => {
  const full = innerHeight(page);
  await emulateTelegram(page, { visible: full, safeAreaBottom: HOME_INDICATOR });
  await openDetailsSheet(page);

  const expanded = await readSheetGeometry(page);
  expect(expanded.sheet.bottom).toBeLessThanOrEqual(expanded.visible);

  await setStableViewport(page, full - 250);

  const collapsed = await readSheetGeometry(page);
  expect(collapsed.visible).toBe(full - 250);
  expect(collapsed.sheet.bottom).toBeLessThanOrEqual(collapsed.visible);
  expect(collapsed.sheet.height).toBeLessThan(expanded.sheet.height);
});

test('reopening the sheet starts at the top with no stale transform', async ({ page }) => {
  await emulateTelegram(page, { visible: innerHeight(page) - 90, safeAreaBottom: HOME_INDICATOR });
  await openDetailsSheet(page);
  await scrollSheetToEnd(page);
  expect((await readSheetGeometry(page)).bodyScrollTop).toBeGreaterThan(0);

  await page.getByRole('button', { name: 'Close details' }).click();
  await expect(page.getByRole('dialog')).not.toBeVisible();

  await page.getByRole('button', { name: 'Full details' }).click();
  await page.waitForSelector('dialog.sheet[open]');
  await page.waitForTimeout(350);

  const reopened = await readSheetGeometry(page);
  expect(reopened.bodyScrollTop).toBe(0);
  expect(reopened.sheet.bottom).toBeLessThanOrEqual(reopened.visible);
  const translate = await page
    .locator('dialog.sheet')
    .evaluate((node) => getComputedStyle(node).translate);
  expect(translate).toBe('0px');
});

test('dark and light themes produce identical geometry', async ({ page }) => {
  await emulateTelegram(page, {
    visible: innerHeight(page) - 200,
    contentSafeAreaTop: TELEGRAM_HEADER,
    safeAreaBottom: HOME_INDICATOR,
  });

  /** Switches the app's own theme, then measures the sheet it renders. */
  const geometryFor = async (theme: 'Dark' | 'Light') => {
    await page.goto('/');
    await page.getByRole('button', { name: 'Settings' }).click();
    await page.getByRole('button', { name: theme, exact: true }).click();
    await expect(page.locator('html')).toHaveAttribute('data-theme', theme.toLowerCase());
    await openDetailsSheet(page);
    const sheet = await readSheetGeometry(page);
    return { sheet: sheet.sheet, head: sheet.head, close: sheet.close, body: sheet.body };
  };

  expect(await geometryFor('Light')).toEqual(await geometryFor('Dark'));
});

test('a short viewport on a small device still contains the sheet', async ({ page }) => {
  await page.setViewportSize({ width: 375, height: 667 });
  await emulateTelegram(page, {
    visible: 460,
    contentSafeAreaTop: 56,
    safeAreaBottom: HOME_INDICATOR,
  });
  await openDetailsSheet(page);

  const before = await readSheetGeometry(page);
  expect(before.sheet.bottom).toBeLessThanOrEqual(460);
  expect(before.sheet.top).toBeGreaterThanOrEqual(56);
  expect(before.closeClickable).toBe(true);

  await scrollSheetToEnd(page);
  expect((await readSheetGeometry(page)).lastAction.bottom).toBeLessThanOrEqual(460);
});

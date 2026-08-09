import { expect, test, type Page } from '@playwright/test';
import {
  centreOf,
  emulateTelegram,
  fingerDrag,
  fingerDragCancelled,
  fingerScroll,
  hasTouch,
  openDetailsSheet,
  pressTelegramBack,
  readSheetGeometry,
  readSwipeCalls,
  setStableViewport,
} from './telegram';

/**
 * The details sheet as a real bottom sheet: two snap heights, dragged by its handle.
 *
 * Why the previous geometry tests could not catch what a real iPhone did — worth stating,
 * because it is the same trap twice:
 *
 * 1. They asserted **containment only** (`sheet.bottom <= visible`, `bodyMaxScroll > 0`).
 *    A sheet 193px tall with a 60px scroll port satisfies every one of those inequalities.
 *    Nothing anywhere placed a *lower* bound on how much sheet the user actually gets.
 * 2. The suite is Chromium-only — playwright.config.ts documents that the WebKit build
 *    segfaults on this machine — and the collapse is WebKit-specific intrinsic sizing.
 *
 * So this file asserts lower bounds and snap identity, both of which fail loudly in *any*
 * engine if the sheet shrink-wraps again.
 */

const TELEGRAM_HEADER = 103;
const HOME_INDICATOR = 34;

/** The same arithmetic app.css does, so a drifting stylesheet is a failing test. */
const snapHeights = (visible: number, safeTop: number) => {
  const max = visible - safeTop - 8;
  return {
    max,
    collapsed: Math.min(visible * 0.42, max),
    expanded: Math.min(visible * 0.9, max),
  };
};

const visibleFor = (page: Page) => page.viewportSize()!.height - 200;

async function openInTelegram(page: Page, safeTop = TELEGRAM_HEADER) {
  await emulateTelegram(page, {
    visible: visibleFor(page),
    contentSafeAreaTop: safeTop,
    safeAreaBottom: HOME_INDICATOR,
  });
  await openDetailsSheet(page);
}

/** Drags the handle by `dy` — negative is upward — with a real finger or pointer. */
async function dragHandle(page: Page, dy: number) {
  await fingerDrag(page, await centreOf(page, '.sheet__handle'), dy);
}

async function expand(page: Page) {
  await dragHandle(page, -200);
  expect((await readSheetGeometry(page)).snap).toBe('expanded');
}

/** What a drag leaves on the element: both must be gone once the gesture is over. */
const dragState = (page: Page) =>
  page.$eval('dialog.sheet', (element) => ({
    inlineHeight: (element as HTMLElement).style.getPropertyValue('--sheet-drag'),
    dragging: (element as HTMLElement).dataset.dragging ?? null,
  }));

// ------------------------------------------------------------------ snap points

test('the sheet opens at the collapsed snap point', async ({ page }) => {
  await openInTelegram(page);

  const sheet = await readSheetGeometry(page);
  const { collapsed } = snapHeights(sheet.visible, sheet.safeTop);

  expect(sheet.snap).toBe('collapsed');
  expect(sheet.sheet.height).toBeCloseTo(collapsed, 0);
  // Bottom-aligned inside what Telegram actually shows, with the header on screen.
  expect(sheet.sheet.bottom).toBeLessThanOrEqual(sheet.visible);
  expect(sheet.head.bottom).toBeLessThanOrEqual(sheet.visible);
  expect(sheet.closeClickable).toBe(true);
  // 40–45% of the visible viewport, not a strip.
  expect(sheet.sheet.height / sheet.visible).toBeGreaterThan(0.4);
  expect(sheet.sheet.height / sheet.visible).toBeLessThan(0.45);
});

/**
 * The regression that made this P0: with no explicit height the body was left at the size of
 * its own padding. This is the lower bound the old suite never had.
 */
test('the collapsed sheet has a usable body, not a padding-sized strip', async ({ page }) => {
  await openInTelegram(page);

  const sheet = await readSheetGeometry(page);
  expect(sheet.body.clientHeight).toBeGreaterThan(120);
  // Most of the sheet is content rather than chrome.
  expect(sheet.body.clientHeight).toBeGreaterThan(sheet.sheet.height * 0.4);
  // And it is bigger than the padding alone, which is exactly what the phone was showing.
  expect(sheet.body.clientHeight).toBeGreaterThan(sheet.body.paddingBottom * 1.5);
});

test('dragging the handle up expands the sheet', async ({ page }) => {
  await openInTelegram(page);
  const before = await readSheetGeometry(page);

  await dragHandle(page, -200);

  const after = await readSheetGeometry(page);
  const { expanded } = snapHeights(after.visible, after.safeTop);
  expect(after.snap).toBe('expanded');
  expect(after.sheet.height).toBeCloseTo(expanded, 0);
  expect(after.sheet.height).toBeGreaterThan(before.sheet.height);
  expect(after.body.clientHeight).toBeGreaterThan(before.body.clientHeight);
});

test('the expanded sheet respects the visible viewport and the safe top', async ({ page }) => {
  await openInTelegram(page);
  await expand(page);

  const sheet = await readSheetGeometry(page);
  const { expanded } = snapHeights(sheet.visible, sheet.safeTop);
  expect(sheet.sheet.height).toBeCloseTo(expanded, 0);
  expect(sheet.sheet.bottom).toBeLessThanOrEqual(sheet.visible);
  // Clears Telegram's own header rather than sliding under it.
  expect(sheet.sheet.top).toBeGreaterThanOrEqual(sheet.safeTop);
  expect(sheet.grip.top).toBeGreaterThanOrEqual(sheet.safeTop);
});

test('with no host header the expanded snap reaches ~90% of the visible viewport', async ({
  page,
}) => {
  await openInTelegram(page, 0);
  await expand(page);

  const sheet = await readSheetGeometry(page);
  const ratio = sheet.sheet.height / sheet.visible;
  expect(ratio).toBeGreaterThan(0.88);
  expect(ratio).toBeLessThanOrEqual(0.92);
});

test('dragging the handle down collapses an expanded sheet', async ({ page }) => {
  await openInTelegram(page);
  await expand(page);

  await dragHandle(page, 200);

  const sheet = await readSheetGeometry(page);
  const { collapsed } = snapHeights(sheet.visible, sheet.safeTop);
  expect(sheet.snap).toBe('collapsed');
  expect(sheet.sheet.height).toBeCloseTo(collapsed, 0);
  await expect(page.getByRole('dialog')).toBeVisible();
});

test('dragging down from the collapsed snap dismisses the sheet', async ({ page }) => {
  await openInTelegram(page);
  expect((await readSheetGeometry(page)).snap).toBe('collapsed');

  await dragHandle(page, 220);

  await expect(page.getByRole('dialog')).not.toBeVisible();
});

// ----------------------------------------------------------- cancelled drags

/**
 * A cancelled pointer is not a completed gesture.
 *
 * `pointerup` says the user let go where they meant to, so the 64px rule reads intent from
 * the distance. `pointercancel` says the browser or the OS took the pointer away mid-gesture
 * — a system sheet, an incoming call, a palm. Committing on the distance it happened to reach
 * would expand, collapse or even dismiss the sheet on something the user never finished, so
 * the only correct answer is to spring back to where the drag began.
 *
 * These need a genuine browser-issued `pointercancel`, which only a touchscreen produces.
 */
test('a cancelled upward drag leaves a collapsed sheet collapsed', async ({ page }) => {
  test.skip(!(await hasTouch(page)), 'a genuine pointercancel needs a touchscreen');
  await openInTelegram(page);
  const before = await readSheetGeometry(page);

  const midDrag = await fingerDragCancelled(page, await centreOf(page, '.sheet__handle'), -200);
  // The drag really was past the commit threshold when it was cancelled.
  expect(midDrag - before.sheet.height).toBeGreaterThan(64);

  const after = await readSheetGeometry(page);
  expect(after.snap).toBe('collapsed');
  expect(after.sheet.height).toBeCloseTo(before.sheet.height, 0);
  await expect(page.getByRole('dialog')).toBeVisible();
});

test('a cancelled downward drag never dismisses the sheet', async ({ page }) => {
  test.skip(!(await hasTouch(page)), 'a genuine pointercancel needs a touchscreen');
  await openInTelegram(page);
  const before = await readSheetGeometry(page);

  const midDrag = await fingerDragCancelled(page, await centreOf(page, '.sheet__handle'), 220);
  expect(before.sheet.height - midDrag).toBeGreaterThan(64);

  // Far enough to have dismissed on a real release, and it is still here.
  await expect(page.getByRole('dialog')).toBeVisible();
  const after = await readSheetGeometry(page);
  expect(after.snap).toBe('collapsed');
  expect(after.sheet.height).toBeCloseTo(before.sheet.height, 0);
});

test('a cancelled downward drag leaves an expanded sheet expanded', async ({ page }) => {
  test.skip(!(await hasTouch(page)), 'a genuine pointercancel needs a touchscreen');
  await openInTelegram(page);
  await expand(page);
  const before = await readSheetGeometry(page);

  const midDrag = await fingerDragCancelled(page, await centreOf(page, '.sheet__handle'), 200);
  expect(before.sheet.height - midDrag).toBeGreaterThan(64);

  const after = await readSheetGeometry(page);
  expect(after.snap).toBe('expanded');
  expect(after.sheet.height).toBeCloseTo(before.sheet.height, 0);
});

test('a cancelled drag leaves no drag state behind', async ({ page }) => {
  test.skip(!(await hasTouch(page)), 'a genuine pointercancel needs a touchscreen');
  await openInTelegram(page);
  await fingerDragCancelled(page, await centreOf(page, '.sheet__handle'), -200);

  // The inline height and the dragging flag are both gone, so the snap ratio is back in
  // charge of the height and the transition is live again.
  expect(await dragState(page)).toEqual({ inlineHeight: '', dragging: null });

  // And the sheet still works: a completed drag after a cancelled one commits normally.
  await dragHandle(page, -200);
  expect((await readSheetGeometry(page)).snap).toBe('expanded');
});

// ------------------------------------------------------------- body scrolling

test('the body scrolls with a finger while the sheet stays expanded', async ({ page }) => {
  await openInTelegram(page);
  await expand(page);

  const before = await readSheetGeometry(page);
  expect(before.bodyMaxScroll).toBeGreaterThan(0);
  expect(before.bodyScrollTop).toBe(0);

  await fingerScroll(page, '.sheet__body', 200);

  const after = await readSheetGeometry(page);
  expect(after.bodyScrollTop).toBeGreaterThan(0);
  // The gesture moved the content, not the sheet.
  expect(after.snap).toBe('expanded');
  expect(after.sheet.height).toBeCloseTo(before.sheet.height, 0);
});

/**
 * The arbitration rule, stated as a test: a gesture inside the body is the body's, whatever
 * its scroll offset. There is no scrollTop-watching handover to get wrong because the sheet
 * is only ever dragged by its handle.
 */
test('scrolling on inside the body never collapses the sheet', async ({ page }) => {
  await openInTelegram(page);
  await expand(page);

  await fingerScroll(page, '.sheet__body', 200);
  const scrolled = await readSheetGeometry(page);
  expect(scrolled.bodyScrollTop).toBeGreaterThan(0);

  // Already scrolled, now pull the other way — the sheet must not take this as a collapse.
  await fingerScroll(page, '.sheet__body', -120);
  const back = await readSheetGeometry(page);
  expect(back.snap).toBe('expanded');
  expect(back.sheet.height).toBeCloseTo(scrolled.sheet.height, 0);
  expect(back.bodyScrollTop).toBeLessThan(scrolled.bodyScrollTop);
});

test('the final action is reachable by finger and clickable', async ({ page }) => {
  await openInTelegram(page);
  await expand(page);

  for (let pass = 0; pass < 8; pass += 1) {
    await fingerScroll(page, '.sheet__body', 400);
    if ((await readSheetGeometry(page)).bodyScrollTop >= (await readSheetGeometry(page)).bodyMaxScroll - 2) break;
  }

  const sheet = await readSheetGeometry(page);
  expect(sheet.lastAction.bottom).toBeLessThanOrEqual(sheet.visible);

  const action = page.getByRole('dialog').getByRole('link', { name: 'Open vacancy' });
  await expect(action).toBeVisible();
  await action.click();
  await expect(page.getByRole('dialog')).toBeVisible();
});

// ------------------------------------------------------------------ dismissal

test('the close button closes the sheet', async ({ page }) => {
  await openInTelegram(page);
  await page.getByRole('button', { name: 'Close details' }).click();
  await expect(page.getByRole('dialog')).not.toBeVisible();
});

test('the backdrop closes the sheet', async ({ page }) => {
  await openInTelegram(page);
  const sheet = (await readSheetGeometry(page)).sheet;
  // Well above the sheet's top edge: that is backdrop, not surface.
  await page.mouse.click(page.viewportSize()!.width / 2, Math.max(4, sheet.top - 60));
  await expect(page.getByRole('dialog')).not.toBeVisible();
});

test('Escape closes the sheet outside Telegram', async ({ page }) => {
  await openDetailsSheet(page);
  await expect(page.getByRole('dialog')).toBeVisible();

  await page.keyboard.press('Escape');
  await expect(page.getByRole('dialog')).not.toBeVisible();
});

test('the Telegram back control closes the sheet', async ({ page }) => {
  await openInTelegram(page);
  await pressTelegramBack(page);
  await expect(page.getByRole('dialog')).not.toBeVisible();
});

// ------------------------------------------------- host gesture, reopen, viewport

test('dragging does not disturb the host vertical-swipe lifecycle', async ({ page }) => {
  await openInTelegram(page);
  expect(await readSwipeCalls(page)).toEqual(['disable']);

  await dragHandle(page, -200);
  await dragHandle(page, 200);
  // Snapping is ours; the host lock is held for the whole time the sheet is up.
  expect(await readSwipeCalls(page)).toEqual(['disable']);

  await page.getByRole('button', { name: 'Close details' }).click();
  await expect(page.getByRole('dialog')).not.toBeVisible();
  expect(await readSwipeCalls(page)).toEqual(['disable', 'enable']);
});

test('reopening starts collapsed and at the top of the body', async ({ page }) => {
  await openInTelegram(page);
  await expand(page);
  await fingerScroll(page, '.sheet__body', 200);
  expect((await readSheetGeometry(page)).bodyScrollTop).toBeGreaterThan(0);

  await page.getByRole('button', { name: 'Close details' }).click();
  await expect(page.getByRole('dialog')).not.toBeVisible();

  await page.getByRole('button', { name: 'Full details' }).click();
  await page.waitForSelector('dialog.sheet[open]');
  await page.waitForTimeout(400);

  const reopened = await readSheetGeometry(page);
  expect(reopened.snap).toBe('collapsed');
  expect(reopened.bodyScrollTop).toBe(0);
  expect(reopened.sheet.height).toBeCloseTo(
    snapHeights(reopened.visible, reopened.safeTop).collapsed,
    0,
  );
});

test('a host viewport change recomputes both snap points', async ({ page }) => {
  await openInTelegram(page);
  const shorter = visibleFor(page) - 180;

  await setStableViewport(page, shorter);
  const collapsed = await readSheetGeometry(page);
  expect(collapsed.visible).toBe(shorter);
  expect(collapsed.sheet.height).toBeCloseTo(snapHeights(shorter, collapsed.safeTop).collapsed, 0);
  expect(collapsed.sheet.bottom).toBeLessThanOrEqual(shorter);

  await expand(page);
  const expanded = await readSheetGeometry(page);
  expect(expanded.sheet.height).toBeCloseTo(snapHeights(shorter, expanded.safeTop).expanded, 0);
  expect(expanded.sheet.bottom).toBeLessThanOrEqual(shorter);
  expect(expanded.sheet.top).toBeGreaterThanOrEqual(expanded.safeTop);
});

// ------------------------------------------------------------ device envelopes

test('a small iPhone viewport stays usable at both snap points', async ({ page }) => {
  await page.setViewportSize({ width: 375, height: 667 });
  await emulateTelegram(page, { visible: 460, contentSafeAreaTop: 56, safeAreaBottom: HOME_INDICATOR });
  await openDetailsSheet(page);

  const collapsed = await readSheetGeometry(page);
  expect(collapsed.snap).toBe('collapsed');
  expect(collapsed.sheet.bottom).toBeLessThanOrEqual(460);
  expect(collapsed.body.clientHeight).toBeGreaterThan(60);
  expect(collapsed.closeClickable).toBe(true);

  await expand(page);
  const expanded = await readSheetGeometry(page);
  expect(expanded.sheet.top).toBeGreaterThanOrEqual(56);
  expect(expanded.sheet.bottom).toBeLessThanOrEqual(460);
  expect(expanded.body.clientHeight).toBeGreaterThan(collapsed.body.clientHeight);
});

test('a desktop browser with no Telegram host is still sensible', async ({ page }) => {
  await page.setViewportSize({ width: 1024, height: 768 });
  await openDetailsSheet(page);

  const collapsed = await readSheetGeometry(page);
  expect(collapsed.snap).toBe('collapsed');
  expect(collapsed.sheet.height).toBeCloseTo(768 * 0.42, 0);
  expect(collapsed.sheet.bottom).toBeLessThanOrEqual(768);
  expect(collapsed.body.clientHeight).toBeGreaterThan(120);

  await expand(page);
  const expanded = await readSheetGeometry(page);
  expect(expanded.sheet.height).toBeCloseTo(768 * 0.9, 0);
  expect(expanded.sheet.top).toBeGreaterThanOrEqual(0);
});

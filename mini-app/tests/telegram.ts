import type { Page } from '@playwright/test';

/**
 * Emulates the Telegram host for viewport-geometry tests.
 *
 * Telegram iOS keeps the WebView at its maximum height and slides it, so the lower
 * part of the Mini App sits outside the screen area (core.telegram.org/bots/webapps).
 * `window.innerHeight` — and therefore every `dvh` — is the whole WebView, while
 * `viewportStableHeight` is only the part the user can see. Everything that has to
 * stay on screen must size against the latter.
 *
 * The real telegram-web-app.js mirrors those values into custom properties on <html>;
 * this helper writes the same ones, so the app under test is exercised through exactly
 * the contract it ships against.
 */
export interface TelegramViewport {
  /** `viewportStableHeight`: the visible slice of the WebView, in CSS pixels. */
  visible: number;
  /** Telegram's own header overlay, `contentSafeAreaInset.top`. */
  contentSafeAreaTop?: number;
  /** The home indicator, `safeAreaInset.bottom`. */
  safeAreaBottom?: number;
  scheme?: 'light' | 'dark';
  /**
   * `'unsupported'` emulates a Telegram client older than Bot API 7.7, which has no
   * swipe control at all. The app must degrade to a no-op there rather than throw.
   */
  verticalSwipes?: 'supported' | 'unsupported';
}

/** Geometry of the open sheet, measured against what Telegram says is visible. */
export interface SheetGeometry {
  innerHeight: number;
  visible: number;
  safeTop: number;
  safeBottom: number;
  sheet: { top: number; bottom: number; height: number };
  grip: { top: number; bottom: number };
  head: { top: number; bottom: number };
  close: { top: number; bottom: number };
  closeClickable: boolean;
  /** Which snap point the sheet is resting at, straight off the element. */
  snap: string | null;
  /** The drag region: grip plus header, the only place a sheet drag may start. */
  handle: { top: number; bottom: number; height: number };
  body: { top: number; bottom: number; paddingBottom: number; clientHeight: number };
  bodyScrollTop: number;
  bodyMaxScroll: number;
  lastAction: { top: number; bottom: number };
  docScrollTop: number;
  docScrollHeight: number;
}

/**
 * Installs the fake host before any page script runs, so it must be called before
 * `page.goto`. Playwright init scripts run earlier than the real SDK's blocking
 * <head> script — early enough that <html> does not exist yet — so the custom
 * properties are queued and flushed once it does.
 */
export async function emulateTelegram(page: Page, viewport: TelegramViewport): Promise<void> {
  await page.addInitScript(
    ({ visible, contentSafeAreaTop, safeAreaBottom, scheme, verticalSwipes }) => {
      let queued: [string, string][] = [];
      const setVar = (name: string, value: string) => {
        const root = document.documentElement;
        if (root) root.style.setProperty('--tg-' + name, value);
        else queued.push([name, value]);
      };
      const flush = () => {
        if (!document.documentElement) return void setTimeout(flush, 0);
        for (const [name, value] of queued) {
          document.documentElement.style.setProperty('--tg-' + name, value);
        }
        queued = [];
      };
      setTimeout(flush, 0);

      const handlers: Record<string, (() => void)[]> = {};
      let current = visible;
      const applyViewport = (height: number, stable: boolean) => {
        current = height;
        setVar('viewport-height', height + 'px');
        if (stable) setVar('viewport-stable-height', height + 'px');
        for (const handler of handlers.viewportChanged ?? []) handler();
      };

      setVar('content-safe-area-inset-top', (contentSafeAreaTop ?? 0) + 'px');
      setVar('safe-area-inset-bottom', (safeAreaBottom ?? 0) + 'px');

      /**
       * Every host swipe call in order. The host has no readable "are swipes disabled"
       * flag, so the sequence *is* the observable: a leak is a missing 'enable', and a
       * double disable is a 'disable' with no 'enable' between the two.
       */
      const swipes: string[] = [];
      (window as unknown as { __tgSwipes: string[] }).__tgSwipes = swipes;
      // Bot API 7.7 added these. An older client has neither, and must not be called.
      const swipeControl = verticalSwipes === 'unsupported'
        ? {}
        : {
          disableVerticalSwipes() {
            swipes.push('disable');
          },
          enableVerticalSwipes() {
            swipes.push('enable');
          },
        };

      (window as unknown as { Telegram: unknown }).Telegram = {
        WebApp: {
          ...swipeControl,
          version: '8.0',
          platform: 'ios',
          colorScheme: scheme ?? 'dark',
          initData: 'auth_date=1&hash=test',
          isExpanded: true,
          get viewportHeight() {
            return current;
          },
          get viewportStableHeight() {
            return current;
          },
          ready() {},
          expand() {},
          openLink() {},
          onEvent(event: string, handler: () => void) {
            (handlers[event] ??= []).push(handler);
          },
          offEvent(event: string, handler: () => void) {
            handlers[event] = (handlers[event] ?? []).filter((each) => each !== handler);
          },
          BackButton: {
            show() {},
            hide() {},
            onClick(handler: () => void) {
              (handlers.back ??= []).push(handler);
            },
            offClick(handler: () => void) {
              handlers.back = (handlers.back ?? []).filter((each) => each !== handler);
            },
          },
          HapticFeedback: { impactOccurred() {}, notificationOccurred() {} },
          /** Test hook: drives a viewport change the way the host would. */
          __setViewport: applyViewport,
          /** Test hook: presses Telegram's own back control. */
          __pressBack() {
            for (const handler of handlers.back ?? []) handler();
          },
        },
      };
      applyViewport(visible, true);
    },
    viewport,
  );
}

/** Moves the emulated host to a new stable height, as a pull or an expand would. */
export async function setStableViewport(page: Page, height: number): Promise<void> {
  await page.evaluate((next) => {
    (
      window as unknown as { Telegram: { WebApp: { __setViewport(h: number, s: boolean): void } } }
    ).Telegram.WebApp.__setViewport(next, true);
  }, height);
  // The custom property reaches layout in a frame, but the snap height it feeds transitions
  // over 260ms — so a shorter wait would measure the sheet mid-resize.
  await page.waitForTimeout(400);
}

/** Opens the details sheet on the review screen with every disclosure expanded, so the body overflows. */
export async function openDetailsSheet(page: Page): Promise<void> {
  await page.goto('/');
  await page.getByRole('button', { name: /^Review(,|$)/ }).click();
  await page.getByRole('button', { name: 'Full details' }).click();
  await page.waitForSelector('dialog.sheet[open]');
  await page.$$eval('dialog.sheet details', (list) =>
    list.forEach((node) => ((node as HTMLDetailsElement).open = true)),
  );
  // The enter transition is 220ms; measure only once it has settled.
  await page.waitForTimeout(350);
}

export async function readSheetGeometry(page: Page): Promise<SheetGeometry> {
  return page.evaluate(() => {
    const round = (value: number) => Math.round(value * 10) / 10;
    const box = (element: Element | null) => {
      const rect = (element as HTMLElement).getBoundingClientRect();
      return { top: round(rect.top), bottom: round(rect.bottom), height: round(rect.height) };
    };
    const root = getComputedStyle(document.documentElement);
    const custom = (name: string) => parseFloat(root.getPropertyValue(name)) || 0;

    const stable = root.getPropertyValue('--tg-viewport-stable-height').trim();
    const visible = stable ? parseFloat(stable) : window.innerHeight;
    const safeTop = custom('--tg-content-safe-area-inset-top');
    const safeBottom = custom('--tg-safe-area-inset-bottom');

    const sheet = document.querySelector('dialog.sheet')!;
    const body = document.querySelector('.sheet__body') as HTMLElement;
    const close = document.querySelector('.sheet__close')!;
    const lastAction = document.querySelector('.sheet__body .btn--open')!;

    const closeRect = close.getBoundingClientRect();
    const hit = document.elementFromPoint(
      closeRect.left + closeRect.width / 2,
      closeRect.top + closeRect.height / 2,
    );

    return {
      innerHeight: window.innerHeight,
      visible,
      safeTop,
      safeBottom,
      sheet: box(sheet),
      grip: box(document.querySelector('.sheet__grip')),
      head: box(document.querySelector('.sheet__head')),
      close: box(close),
      closeClickable:
        closeRect.top >= safeTop &&
        closeRect.bottom <= visible &&
        hit !== null &&
        (close === hit || close.contains(hit)),
      snap: (sheet as HTMLElement).dataset.snap ?? null,
      handle: box(document.querySelector('.sheet__handle')),
      body: {
        ...box(body),
        paddingBottom: parseFloat(getComputedStyle(body).paddingBottom),
        clientHeight: body.clientHeight,
      },
      bodyScrollTop: Math.round(body.scrollTop),
      bodyMaxScroll: body.scrollHeight - body.clientHeight,
      lastAction: box(lastAction),
      docScrollTop: Math.round(document.scrollingElement!.scrollTop),
      docScrollHeight: document.scrollingElement!.scrollHeight,
    };
  });
}

/** Presses Telegram's native back control, the way the host would. */
export async function pressTelegramBack(page: Page): Promise<void> {
  await page.evaluate(() => {
    (window as unknown as { Telegram: { WebApp: { __pressBack(): void } } }).Telegram.WebApp
      .__pressBack();
  });
}

/** Whether this project emulates a touchscreen, so a gesture can be a real finger. */
export const hasTouch = (page: Page): Promise<boolean> =>
  page.evaluate(() => 'ontouchstart' in window || navigator.maxTouchPoints > 0);

/**
 * Drags from a point, with a real finger where the device has one and a real pointer where
 * it does not.
 *
 * Both go through the browser's own input pipeline and arrive as ordinary pointer events —
 * unlike assigning `scrollTop` or firing `wheel`, which would prove only that a property is
 * writable. `dy` is positive downward, as screen coordinates are.
 */
export async function fingerDrag(
  page: Page,
  from: { x: number; y: number },
  dy: number,
  steps = 12,
): Promise<void> {
  const at = (step: number) => from.y + (dy * step) / steps;

  if (await hasTouch(page)) {
    const session = await page.context().newCDPSession(page);
    const touch = (type: 'touchStart' | 'touchMove' | 'touchEnd', y: number) =>
      session.send('Input.dispatchTouchEvent', {
        type,
        touchPoints: type === 'touchEnd' ? [] : [{ id: 0, x: from.x, y }],
      });
    await touch('touchStart', from.y);
    for (let step = 1; step <= steps; step += 1) await touch('touchMove', at(step));
    await touch('touchEnd', at(steps));
    await session.detach();
  } else {
    await page.mouse.move(from.x, from.y);
    await page.mouse.down();
    for (let step = 1; step <= steps; step += 1) await page.mouse.move(from.x, at(step));
    await page.mouse.up();
  }
  // The snap transition is 260ms; let it land before anything is measured.
  await page.waitForTimeout(400);
}

/**
 * Drags the handle and then has the *browser* cancel the sequence, as an OS-level gesture or
 * an incoming call does — a `touchCancel`, which Chromium turns into a real `pointercancel`.
 *
 * Only a touchscreen can produce a genuine one, so callers must skip where there is none;
 * dispatching a synthetic event instead would be testing the test.
 *
 * Returns the sheet's height measured while the finger is still down, so a caller can prove
 * the drag actually moved something before it was cancelled.
 */
export async function fingerDragCancelled(
  page: Page,
  from: { x: number; y: number },
  dy: number,
  steps = 12,
): Promise<number> {
  const session = await page.context().newCDPSession(page);
  let stamp = Date.now() / 1000;
  const send = (type: 'touchStart' | 'touchMove' | 'touchCancel', y: number) => {
    stamp += 0.016;
    return session.send('Input.dispatchTouchEvent', {
      type,
      timestamp: stamp,
      touchPoints: type === 'touchCancel' ? [] : [{ id: 0, x: from.x, y }],
    });
  };

  await send('touchStart', from.y);
  for (let step = 1; step <= steps; step += 1) await send('touchMove', from.y + (dy * step) / steps);
  const midDrag = await page.$eval('dialog.sheet', (el) => el.getBoundingClientRect().height);
  await send('touchCancel', from.y + dy);
  await session.detach();
  // The spring back is the same 260ms transition a snap uses.
  await page.waitForTimeout(400);
  return midDrag;
}

/**
 * Scrolls a scroll container with a real gesture. `dy` is positive to scroll the content
 * down, i.e. to increase scrollTop.
 *
 * Two genuine touch paths, because they are not equally available everywhere. The first is a
 * paced touch drag: the browser's own gesture recogniser turns it into a scroll, exactly as
 * it does for a finger on glass. The second is Chromium's synthetic gesture controller with
 * the fling suppressed — headless Linux drops the fling the first path relies on, which is
 * how CI caught this and a Mac never would. Neither is a `wheel` or a `scrollTop` write.
 */
export async function fingerScroll(page: Page, selector: string, dy: number): Promise<void> {
  const offset = () => page.$eval(selector, (element) => element.scrollTop);
  const at = await centreOf(page, selector);
  const touch = await hasTouch(page);
  const before = await offset();
  const session = await page.context().newCDPSession(page);

  if (touch) {
    const steps = 16;
    // Distinct, increasing timestamps: with every event at the same instant the recogniser
    // sees infinite velocity and declines to call it a scroll.
    let stamp = Date.now() / 1000;
    const send = (type: 'touchStart' | 'touchMove' | 'touchEnd', y: number) => {
      stamp += 0.016;
      return session.send('Input.dispatchTouchEvent', {
        type,
        timestamp: stamp,
        touchPoints: type === 'touchEnd' ? [] : [{ id: 0, x: at.x, y }],
      });
    };
    // A finger travelling up scrolls the content down.
    await send('touchStart', at.y);
    for (let step = 1; step <= steps; step += 1) await send('touchMove', at.y - (dy * step) / steps);
    await send('touchEnd', at.y - dy);
    await page.waitForTimeout(300);
  }

  if (!touch || (await offset()) === before) {
    await session.send('Input.synthesizeScrollGesture', {
      x: at.x,
      y: at.y,
      xDistance: 0,
      yDistance: -dy,
      gestureSourceType: touch ? 'touch' : 'mouse',
      preventFling: true,
      speed: 1000,
    });
    await page.waitForTimeout(250);
  }
  await session.detach();
}

/** The centre of an element, for a gesture that has to start somewhere specific. */
export async function centreOf(page: Page, selector: string): Promise<{ x: number; y: number }> {
  const box = await page.locator(selector).boundingBox();
  if (!box) throw new Error(`no box for ${selector}`);
  return { x: box.x + box.width / 2, y: box.y + box.height / 2 };
}

/** Every `disable`/`enable` the app has asked the host for, in order, since page load. */
export function readSwipeCalls(page: Page): Promise<string[]> {
  return page.evaluate(
    () => (window as unknown as { __tgSwipes?: string[] }).__tgSwipes ?? [],
  );
}

/** Scrolls the sheet's own container to its end, the furthest a user can get. */
export async function scrollSheetToEnd(page: Page): Promise<void> {
  await page.$eval('.sheet__body', (element) => {
    element.scrollTop = element.scrollHeight;
  });
  await page.waitForTimeout(120);
}

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
  body: { top: number; bottom: number; paddingBottom: number };
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
          BackButton: { show() {}, hide() {}, onClick() {}, offClick() {} },
          HapticFeedback: { impactOccurred() {}, notificationOccurred() {} },
          /** Test hook: drives a viewport change the way the host would. */
          __setViewport: applyViewport,
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
  // One frame for the custom property to reach layout.
  await page.waitForTimeout(120);
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
      body: {
        ...box(body),
        paddingBottom: parseFloat(getComputedStyle(body).paddingBottom),
      },
      bodyScrollTop: Math.round(body.scrollTop),
      bodyMaxScroll: body.scrollHeight - body.clientHeight,
      lastAction: box(lastAction),
      docScrollTop: Math.round(document.scrollingElement!.scrollTop),
      docScrollHeight: document.scrollingElement!.scrollHeight,
    };
  });
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

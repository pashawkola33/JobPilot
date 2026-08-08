/**
 * The only place that touches `window.Telegram`.
 *
 * Outside Telegram every method is a safe no-op and `available` is false, so the app
 * runs unchanged in a plain browser. The host SDK sets the `--tg-theme-*` and
 * `--tg-*-safe-area-inset-*` custom properties itself, which is why nothing here
 * writes CSS variables.
 */

type ColorScheme = 'light' | 'dark';
type HapticKind = 'light' | 'medium' | 'success' | 'warning';

interface TelegramWebApp {
  ready(): void;
  expand(): void;
  colorScheme: ColorScheme;
  /**
   * The raw, still-signed query string. `initDataUnsafe` is deliberately not declared:
   * client-parsed identity carries no proof and must never reach an authorization
   * decision, so nothing in this app can reach for it by accident.
   */
  initData: string;
  openLink(url: string): void;
  onEvent(event: 'themeChanged', handler: () => void): void;
  offEvent(event: 'themeChanged', handler: () => void): void;
  BackButton: {
    show(): void;
    hide(): void;
    onClick(handler: () => void): void;
    offClick(handler: () => void): void;
  };
  HapticFeedback?: {
    impactOccurred(style: 'light' | 'medium' | 'heavy'): void;
    notificationOccurred(type: 'error' | 'success' | 'warning'): void;
  };
  /** Bot API 7.7 and later; absent on older clients, hence optional. */
  disableVerticalSwipes?(): void;
  enableVerticalSwipes?(): void;
}

const webApp: TelegramWebApp | undefined = (
  window as unknown as { Telegram?: { WebApp?: TelegramWebApp } }
).Telegram?.WebApp;

export const available = webApp !== undefined;

/**
 * The signed launch payload, passed through untouched for the server to verify.
 * Empty outside Telegram, and empty when Telegram supplies no launch data — both mean
 * the app cannot authenticate, which the caller must treat as a hard stop.
 *
 * Read on each call rather than cached: Telegram reissues initData when the app is
 * reopened, and a stale copy would expire against the server's auth-age window.
 */
export function initData(): string {
  return webApp?.initData ?? '';
}

export function ready(): void {
  webApp?.ready();
  webApp?.expand();
}

/** Telegram's own scheme when hosted, the OS preference otherwise. */
export function colorScheme(): ColorScheme {
  if (webApp) return webApp.colorScheme;
  return window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light';
}

/** Subscribes to whichever theme signal this environment provides. Returns an unsubscribe. */
export function onColorSchemeChange(handler: () => void): () => void {
  if (webApp) {
    webApp.onEvent('themeChanged', handler);
    return () => webApp.offEvent('themeChanged', handler);
  }
  const query = window.matchMedia('(prefers-color-scheme: dark)');
  query.addEventListener('change', handler);
  return () => query.removeEventListener('change', handler);
}

/** Telegram opens links in its own browser; elsewhere the anchor's default is fine. */
export function openLink(url: string): boolean {
  if (!webApp) return false;
  webApp.openLink(url);
  return true;
}

export function haptic(kind: HapticKind): void {
  const feedback = webApp?.HapticFeedback;
  if (!feedback) return;
  if (kind === 'success' || kind === 'warning') feedback.notificationOccurred(kind);
  else feedback.impactOccurred(kind);
}

/**
 * Hands the vertical gesture to the app for as long as the returned restore is unused.
 *
 * Telegram iOS reads a vertical drag anywhere in the Mini App as "minimize me", which a
 * scrollable overlay of our own cannot win — the app minimizes instead of the content
 * scrolling. Telegram's answer is this pair, and it deliberately leaves the header gesture
 * alive, so the user can still minimize or close while it is in force.
 *
 * Both halves are feature-detected together: a client that could disable but not enable
 * would be a one-way trip, so it gets the no-op instead.
 */
export function holdVerticalSwipes(): () => void {
  const disable = webApp?.disableVerticalSwipes;
  const enable = webApp?.enableVerticalSwipes;
  if (!disable || !enable) return () => {};
  disable.call(webApp);
  return () => enable.call(webApp);
}

/** Passing null hides Telegram's native back button. */
export function setBackButton(handler: (() => void) | null): () => void {
  const button = webApp?.BackButton;
  if (!button) return () => {};
  if (!handler) {
    button.hide();
    return () => {};
  }
  button.onClick(handler);
  button.show();
  return () => {
    button.offClick(handler);
    button.hide();
  };
}

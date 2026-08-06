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
}

const webApp: TelegramWebApp | undefined = (
  window as unknown as { Telegram?: { WebApp?: TelegramWebApp } }
).Telegram?.WebApp;

export const available = webApp !== undefined;

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

import { defineConfig } from '@playwright/test';

const PORT = 5179;

/**
 * Telegram on iOS runs a WKWebView, so WebKit is the truest engine to test against.
 * Playwright's WebKit build segfaults on this machine (Darwin 25), so the mobile
 * projects use Chromium with touch emulation at the two iPhone widths instead.
 * Re-add `browserName: 'webkit'` once that build is usable.
 */
const mobile = (width: number, height: number) => ({
  browserName: 'chromium' as const,
  viewport: { width, height },
  deviceScaleFactor: 3,
  isMobile: true,
  hasTouch: true,
});

export default defineConfig({
  testDir: './tests',
  fullyParallel: true,
  reporter: process.env.CI ? 'github' : 'list',
  use: {
    baseURL: `http://localhost:${PORT}`,
    trace: 'on-first-retry',
  },
  projects: [
    { name: 'mobile-390', use: mobile(390, 844) },
    { name: 'mobile-430', use: mobile(430, 932) },
    { name: 'desktop-1024', use: { viewport: { width: 1024, height: 768 } } },
  ],
  webServer: {
    command: `npx vite --port ${PORT} --strictPort`,
    url: `http://localhost:${PORT}`,
    reuseExistingServer: !process.env.CI,
  },
});

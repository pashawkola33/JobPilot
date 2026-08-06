import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

/**
 * The Telegram host SDK, injected into production builds only. The dev server and the
 * Playwright suite stay offline and fake `window.Telegram` themselves; a real script tag
 * would overwrite that fake, since it loads after Playwright's init script.
 *
 * It is a classic, blocking script in <head>, so it runs before the deferred module that
 * reads `window.Telegram.WebApp` in src/lib/telegram.ts.
 */
const telegramSdk = {
  name: 'jobpilot:telegram-sdk',
  apply: 'build',
  transformIndexHtml: () => [
    {
      tag: 'script',
      attrs: { src: 'https://telegram.org/js/telegram-web-app.js' },
      injectTo: 'head-prepend',
    },
  ],
} as const

// https://vite.dev/config/
export default defineConfig(({ command }) => ({
  plugins: [react(), telegramSdk],
  // Spring Boot serves the built app under /mini-app/ (see MiniAppWebConfig). Absolute
  // rather than relative, so a request to /mini-app without the trailing slash still
  // resolves its assets. The dev server keeps the root, which is what the tests target.
  base: command === 'build' ? '/mini-app/' : '/',
}))

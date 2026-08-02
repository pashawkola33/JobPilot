/**
 * Worker entry point. Loads and validates configuration fail-closed, resolves
 * an already-installed CloakBrowser binary (never downloading at runtime),
 * starts the HTTP server, and shuts down gracefully.
 */
import { loadConfig, ConfigError } from "./config.js";
import { resolveBrowser } from "./browserBinary.js";
import { createBrowserRenderer } from "./extract.js";
import { createServer } from "./server.js";
import { Metrics } from "./metrics.js";
import { logEvent } from "./logging.js";
import type { RenderFn } from "./types.js";
import { AdmissionController } from "./admission.js";

async function main(): Promise<void> {
  let config;
  try {
    config = loadConfig();
  } catch (error) {
    if (error instanceof ConfigError) {
      logEvent({ requestId: "startup", status: "config_error" });
      process.exitCode = 78; // EX_CONFIG
      return;
    }
    throw error;
  }

  const browser = await resolveBrowser(config.executablePathOverride);
  const render: RenderFn = browser
    ? createBrowserRenderer(config, browser)
    : async (request) => ({ kind: "FAILURE", status: "FETCH_FAILED" as const, requestId: request.requestId } as never);

  const metrics = new Metrics();
  const admission = new AdmissionController(config.maxConcurrentRenders);
  const server = createServer({
    config,
    metrics,
    render,
    admission,
    browserReady: browser !== null,
  });

  server.listen(config.port, () => {
    logEvent({ requestId: "startup", status: "started" });
  });

  let shuttingDown = false;
  const shutdown = (): void => {
    if (shuttingDown) return;
    shuttingDown = true;
    logEvent({ requestId: "shutdown", status: "shutdown" });
    server.close(() => process.exit(0));
    // Force exit if in-flight work does not drain within the grace window.
    setTimeout(() => process.exit(0), 15000).unref();
  };
  process.on("SIGTERM", shutdown);
  process.on("SIGINT", shutdown);
}

void main();

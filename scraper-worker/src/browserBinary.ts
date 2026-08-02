/**
 * Resolve the CloakBrowser stealth-Chromium executable WITHOUT ever downloading
 * it on a request path. The binary is prepared during the controlled image
 * build (`cloakbrowser install`); at runtime we only look up an already-present
 * binary. If none is present the worker starts degraded and refuses extraction.
 */
import { existsSync } from "node:fs";

export interface ResolvedBrowser {
  executablePath: string;
  stealthArgs: string[];
  chromiumVersion: string;
}

export async function resolveBrowser(overridePath?: string): Promise<ResolvedBrowser | null> {
  let stealthArgs: string[] = [];
  let chromiumVersion = "unknown";
  let installedPath: string | undefined;

  try {
    const cloak = (await import("cloakbrowser")) as unknown as {
      binaryInfo: () => { binaryPath: string; installed: boolean; version: string };
      getDefaultStealthArgs: () => string[];
      CHROMIUM_VERSION: string;
    };
    try {
      stealthArgs = sanitizeLaunchArgs(cloak.getDefaultStealthArgs());
    } catch {
      stealthArgs = [];
    }
    chromiumVersion = cloak.CHROMIUM_VERSION ?? "unknown";
    const info = cloak.binaryInfo();
    if (info.installed && info.binaryPath) {
      installedPath = info.binaryPath;
      chromiumVersion = info.version ?? chromiumVersion;
    }
  } catch {
    // CloakBrowser API not resolvable; fall back to the explicit override only.
  }

  const candidate = overridePath && overridePath.trim() !== "" ? overridePath.trim() : installedPath;
  if (!candidate || !existsSync(candidate)) return null;
  return { executablePath: candidate, stealthArgs, chromiumVersion };
}

export const SANDBOX_DISABLING_ARGUMENTS = ["--no-sandbox", "--disable-setuid-sandbox"] as const;

/** Keep vendor stealth flags, but never weaken Chromium's process sandbox. */
export function sanitizeLaunchArgs(args: readonly string[]): string[] {
  return args.filter((argument) =>
    !SANDBOX_DISABLING_ARGUMENTS.some(
      (unsafe) => argument === unsafe || argument.startsWith(`${unsafe}=`),
    ),
  );
}

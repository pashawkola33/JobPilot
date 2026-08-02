import { strict as assert } from "node:assert";
import { test } from "node:test";
import { sanitizeLaunchArgs } from "../src/browserBinary.js";

test("removes sandbox-disabling vendor arguments and preserves safe stealth arguments", () => {
  assert.deepEqual(
    sanitizeLaunchArgs([
      "--no-sandbox",
      "--disable-setuid-sandbox",
      "--no-sandbox=true",
      "--disable-setuid-sandbox=1",
      "--disable-blink-features=AutomationControlled",
      "--window-size=1280,720",
    ]),
    ["--disable-blink-features=AutomationControlled", "--window-size=1280,720"],
  );
});

import { strict as assert } from "node:assert";
import { test } from "node:test";
import { isAuthorized } from "../src/auth.js";

const secret = "0123456789abcdef0123456789abcdef"; // 32 bytes

test("accepts the exact shared secret", () => {
  assert.equal(isAuthorized(secret, secret), true);
});

test("rejects a wrong secret of equal length", () => {
  assert.equal(isAuthorized("f".repeat(secret.length), secret), false);
});

test("rejects a missing secret", () => {
  assert.equal(isAuthorized(undefined, secret), false);
  assert.equal(isAuthorized("", secret), false);
});

test("rejects a too-short and a too-long secret without length leaking", () => {
  assert.equal(isAuthorized(secret.slice(0, 16), secret), false);
  assert.equal(isAuthorized(secret + "extra", secret), false);
});

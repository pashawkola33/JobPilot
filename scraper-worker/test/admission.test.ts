import { strict as assert } from "node:assert";
import { test } from "node:test";
import { AdmissionController } from "../src/admission.js";

test("admission is fixed-capacity and never creates waiters", () => {
  const admission = new AdmissionController(1);
  const release = admission.tryAcquire();
  assert.equal(typeof release, "function");
  assert.equal(admission.tryAcquire(), null);
  assert.deepEqual(admission.snapshot(), { active: 1, capacity: 1, queued: 0 });

  release?.();
  release?.();
  assert.deepEqual(admission.snapshot(), { active: 0, capacity: 1, queued: 0 });
  assert.equal(typeof admission.tryAcquire(), "function");
});

import { strict as assert } from "node:assert";
import { test } from "node:test";
import {
  BasicCrawler,
  Configuration,
  RequestList,
  Snapshotter,
  type LoadSignal,
} from "@crawlee/playwright";

/** Removes host telemetry from this deterministic lifecycle-only test. */
class NoTelemetrySnapshotter extends Snapshotter {
  override getLoadSignals(): LoadSignal[] {
    return [];
  }

  override async start(): Promise<void> {}

  override async stop(): Promise<void> {}
}

/** Reproduces a missing `ps` failure before AutoscaledPool polls RequestList. */
class MissingPsSnapshotter extends NoTelemetrySnapshotter {
  override async start(): Promise<void> {
    const error = new Error("spawn ps ENOENT") as NodeJS.ErrnoException;
    error.code = "ENOENT";
    throw error;
  }
}

function fixedPool(snapshotter: Snapshotter) {
  return {
    minConcurrency: 1,
    maxConcurrency: 1,
    desiredConcurrency: 1,
    systemStatusOptions: { snapshotter },
  };
}

test("snapshotter startup failure leaves the one-item RequestList unconsumed", async () => {
  const configuration = new Configuration({ persistStorage: false, purgeOnStart: true });
  const list = await RequestList.open(null, [{ url: "https://example.com" }]);
  let handlerCalls = 0;
  const crawler = new BasicCrawler(
    {
      requestList: list,
      maxConcurrency: 1,
      maxRequestsPerCrawl: 1,
      maxRequestRetries: 0,
      autoscaledPoolOptions: fixedPool(new MissingPsSnapshotter({ config: configuration })),
      requestHandler: async () => {
        handlerCalls++;
      },
    },
    configuration,
  );

  try {
    await assert.rejects(crawler.run(), /spawn ps ENOENT/);
    assert.equal(handlerCalls, 0);
    assert.equal(list.length(), 1);
    assert.equal(list.handledCount(), 0);
    assert.equal(await list.isEmpty(), false);
  } finally {
    await crawler.teardown();
  }
});

test("a healthy lifecycle consumes the in-memory RequestList exactly once", async () => {
  const configuration = new Configuration({ persistStorage: false, purgeOnStart: true });
  const list = await RequestList.open(null, [{ url: "https://example.com" }]);
  let handlerCalls = 0;
  const crawler = new BasicCrawler(
    {
      requestList: list,
      maxConcurrency: 1,
      maxRequestsPerCrawl: 1,
      maxRequestRetries: 0,
      autoscaledPoolOptions: fixedPool(new NoTelemetrySnapshotter({ config: configuration })),
      requestHandler: async () => {
        handlerCalls++;
      },
    },
    configuration,
  );

  try {
    const statistics = await crawler.run();
    assert.equal(handlerCalls, 1);
    assert.equal(statistics.requestsTotal, 1);
    assert.equal(statistics.requestsFinished, 1);
    assert.equal(statistics.requestsFailed, 0);
    assert.equal(list.handledCount(), 1);
    assert.equal(await list.isFinished(), true);
  } finally {
    await crawler.teardown();
  }
});

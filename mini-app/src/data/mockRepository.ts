import type { JobPilotRepository, Snapshot, WorkflowStatus } from './types';
import { JobPilotError } from './types';
import { snapshot } from './sample';

/**
 * In-memory stand-in for the Spring backend. Latency is deliberate and small: enough
 * to make the loading state real, short enough that review never waits on it.
 *
 * `?mock=fail` makes load() reject and `?mock=fail-write` makes mutations reject, so both
 * error paths are reachable without a backend.
 */
export const mockRepository: JobPilotRepository = {
  async load(): Promise<Snapshot> {
    await delay(420);
    if (flag() === 'fail') throw new JobPilotError('unavailable');
    return snapshot();
  },

  async setWorkflowStatus(_jobId: number, _status: WorkflowStatus): Promise<void> {
    await delay(120);
    if (flag() === 'fail-write') throw new JobPilotError('conflict');
  },
};

const flag = () => new URLSearchParams(location.search).get('mock');
const delay = (ms: number) => new Promise((resolve) => setTimeout(resolve, ms));

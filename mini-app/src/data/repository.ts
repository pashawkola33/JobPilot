import type { JobPilotRepository } from './types';
import { mockRepository } from './mockRepository';
import { createHttpRepository } from './httpRepository';

/**
 * Which backend the build talks to. Mock is the default so `npm run dev` and the Playwright
 * suite never need a server; `VITE_JOBPILOT_MODE=api` opts into the real one.
 */
export const mode: 'mock' | 'api' =
  import.meta.env.VITE_JOBPILOT_MODE === 'api' ? 'api' : 'mock';

export const createWorkspaceRepository = (): JobPilotRepository =>
  mode === 'api' ? createHttpRepository() : mockRepository;

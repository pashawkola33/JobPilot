import { expect, test, type Page, type Route } from '@playwright/test';

/**
 * API-mode specs. These run against a `VITE_JOBPILOT_MODE=api` dev server, with Telegram
 * faked in the page and the JobPilot API faked at the network boundary — no Spring Boot,
 * no Telegram, no real credential anywhere.
 */

/** Shaped like real initData but signed by nobody; every request here is intercepted. */
const FAKE_INIT_DATA =
  'auth_date=1767182400&query_id=AAF_fake&user=%7B%22id%22%3A4242%2C%22first_name%22%3A%22Test%22%7D'
  + '&hash=0000000000000000000000000000000000000000000000000000000000000000';

const USER_ID = '4242';

const SNAPSHOT = {
  jobs: [
    {
      id: 4821,
      title: 'Junior Java Backend Engineer',
      company: 'Northsail Systems',
      location: 'Bucharest, Romania',
      remoteType: 'HYBRID',
      seniority: 'JUNIOR',
      employmentType: 'Full-time',
      score: 91,
      band: 'EXCELLENT_MATCH',
      disposition: 'MATCH',
      workflowStatus: 'UNREVIEWED',
      source: 'greenhouse',
      publishedAt: new Date(Date.now() - 2 * 86_400_000).toISOString(),
      canonicalUrl: 'https://boards.example/jobs/4821',
      strengths: ['Spring Boot and PostgreSQL are the primary stack'],
      risks: ['Some on-call rotation after month six'],
    },
    {
      id: 4790,
      title: 'Backend Developer, Platform',
      company: 'Verity Labs',
      location: 'Remote — European Union',
      remoteType: 'REMOTE',
      seniority: 'ENTRY_LEVEL',
      employmentType: 'Full-time',
      score: 84,
      band: 'GOOD_MATCH',
      disposition: 'MATCH',
      workflowStatus: 'UNREVIEWED',
      source: 'ashby',
      publishedAt: new Date(Date.now() - 4 * 86_400_000).toISOString(),
      canonicalUrl: 'https://jobs.example/verity/4790',
      strengths: ['Remote across the EU'],
      risks: ['Kotlin is listed first'],
    },
  ],
  applications: [
    {
      jobId: 4688,
      title: 'Backend Engineer, Payments',
      company: 'Marlowe Pay',
      status: 'INTERVIEW',
      canonicalUrl: 'https://boards.example/jobs/4688',
      updatedAt: new Date(Date.now() - 9 * 86_400_000).toISOString(),
      appliedAt: new Date(Date.now() - 16 * 86_400_000).toISOString(),
      nextFollowUpDate: null,
    },
  ],
};

/** Injects a Telegram host that exposes only what the adapter is allowed to read. */
async function withTelegram(page: Page, initData: string = FAKE_INIT_DATA) {
  await page.addInitScript((value) => {
    (window as unknown as { Telegram: unknown }).Telegram = {
      WebApp: {
        initData: value,
        colorScheme: 'light',
        ready() {},
        expand() {},
        openLink() {},
        onEvent() {},
        offEvent() {},
        BackButton: { show() {}, hide() {}, onClick() {}, offClick() {} },
      },
    };
  }, initData);
}

const json = (route: Route, status: number, body: unknown) =>
  route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body) });

async function stubApi(
  page: Page,
  options: {
    snapshot?: { status: number; body: unknown };
    workflow?: { status: number; body: unknown };
    onRequest?: (headers: Record<string, string>, body: string | null, url: string) => void;
  } = {},
) {
  await page.route('**/api/mini-app/v1/**', async (route) => {
    const request = route.request();
    options.onRequest?.(request.headers(), request.postData(), request.url());

    if (request.url().includes('/snapshot')) {
      const stub = options.snapshot ?? { status: 200, body: SNAPSHOT };
      return json(route, stub.status, stub.body);
    }
    const stub = options.workflow ?? {
      status: 200,
      body: { jobId: 4821, status: 'SAVED', changed: true, updatedAt: new Date().toISOString() },
    };
    return json(route, stub.status, stub.body);
  });
}

const review = (page: Page) => page.getByRole('button', { name: /^Review(,|$)/ });

// ------------------------------------------------------------------ authentication

test('sends the raw initData in its own header and nothing else', async ({ page }) => {
  const seen: { headers: Record<string, string>; url: string; body: string | null }[] = [];
  await withTelegram(page);
  await stubApi(page, {
    onRequest: (headers, body, url) => seen.push({ headers, body, url }),
  });

  await page.goto('/');
  await expect(page.getByRole('heading', { name: /vacancies waiting/ })).toBeVisible();

  expect(seen).not.toHaveLength(0);
  const request = seen[0]!;
  expect(request.headers['x-telegram-init-data']).toBe(FAKE_INIT_DATA);
  // Credentials belong in the header only: never the URL, never a cookie.
  expect(request.url).not.toContain('hash=');
  expect(request.url).not.toContain(USER_ID);
  expect(request.headers['cookie']).toBeUndefined();
});

test('never sends client-parsed identity alongside the signed payload', async ({ page }) => {
  const seen: { headers: Record<string, string>; body: string | null }[] = [];
  await withTelegram(page);
  await stubApi(page, { onRequest: (headers, body) => seen.push({ headers, body }) });

  await page.goto('/');
  await review(page).click();
  await page.locator('.actions').getByRole('button', { name: 'Save' }).click();
  await expect.poll(() => seen.length).toBeGreaterThan(1);

  for (const request of seen) {
    // The user id may appear only inside the opaque signed blob, never as its own field.
    for (const [name, value] of Object.entries(request.headers)) {
      if (name === 'x-telegram-init-data') continue;
      expect(value).not.toContain(USER_ID);
    }
    if (request.body) expect(request.body).not.toContain(USER_ID);
  }
});

test('fails closed outside Telegram instead of falling back to mock data', async ({ page }) => {
  // No withTelegram(): window.Telegram is absent, exactly like a plain browser tab.
  await stubApi(page);

  await page.goto('/');

  await expect(page.getByRole('heading', { name: 'Open JobPilot from Telegram' })).toBeVisible();
  await expect(page.getByText('Junior Java Backend Engineer')).toBeHidden();
});

test('fails closed when Telegram supplies no launch data', async ({ page }) => {
  await withTelegram(page, '');
  await stubApi(page);

  await page.goto('/');

  await expect(page.getByRole('heading', { name: 'Open JobPilot from Telegram' })).toBeVisible();
});

// -------------------------------------------------------------------------- reads

test('renders a snapshot served by the API', async ({ page }) => {
  await withTelegram(page);
  await stubApi(page);

  await page.goto('/');

  await expect(page.getByRole('heading', { name: /vacancies waiting/ })).toContainText('2');
  await expect(page.getByRole('button', { name: /Junior Java Backend Engineer/ })).toBeVisible();

  await review(page).click();
  await expect(page.getByRole('img', { name: /Match score 91 out of 100/ })).toBeVisible();
  await expect(page.getByText('Spring Boot and PostgreSQL are the primary stack')).toBeVisible();
});

test('shows the account message when the server rejects the user', async ({ page }) => {
  await withTelegram(page);
  await stubApi(page, {
    snapshot: { status: 403, body: { category: 'FORBIDDEN', message: 'Not allowed.' } },
  });

  await page.goto('/');

  await expect(page.getByRole('heading', { name: 'Account not allowed' })).toBeVisible();
  // Retrying cannot change who you are, so no retry is offered.
  await expect(page.getByRole('button', { name: 'Try again' })).toBeHidden();
});

test('tells the user to reopen the app when authentication has expired', async ({ page }) => {
  await withTelegram(page);
  await stubApi(page, {
    snapshot: { status: 401, body: { category: 'EXPIRED_AUTH', message: 'Expired.' } },
  });

  await page.goto('/');

  await expect(page.getByRole('heading', { name: 'Session expired' })).toBeVisible();
  await expect(page.getByText(/open it again from the bot/)).toBeVisible();
});

test('names the feature flag when the server has the Mini App switched off', async ({ page }) => {
  await withTelegram(page);
  await stubApi(page, {
    snapshot: { status: 503, body: { category: 'MINI_APP_DISABLED', message: 'Off.' } },
  });

  await page.goto('/');

  await expect(page.getByRole('heading', { name: 'Mini App is switched off' })).toBeVisible();
});

// ---------------------------------------------------------------------- mutations

test('sends a workflow change as a PUT carrying only the status', async ({ page }) => {
  const writes: { url: string; body: string | null; method: string }[] = [];
  await withTelegram(page);
  await page.route('**/api/mini-app/v1/**', async (route) => {
    const request = route.request();
    if (request.url().includes('/snapshot')) return json(route, 200, SNAPSHOT);
    writes.push({ url: request.url(), body: request.postData(), method: request.method() });
    return json(route, 200, {
      jobId: 4821, status: 'SAVED', changed: true, updatedAt: new Date().toISOString(),
    });
  });

  await page.goto('/');
  await review(page).click();
  await page.locator('.actions').getByRole('button', { name: 'Save' }).click();

  await expect.poll(() => writes.length).toBe(1);
  expect(writes[0]!.method).toBe('PUT');
  expect(writes[0]!.url).toContain('/api/mini-app/v1/jobs/4821/workflow');
  expect(JSON.parse(writes[0]!.body ?? '{}')).toEqual({ status: 'SAVED' });

  // The optimistic advance stands because the write succeeded.
  await expect(page.getByRole('heading', { name: 'Backend Developer, Platform' })).toBeVisible();
});

test('rolls the optimistic change back when the server rejects the write', async ({ page }) => {
  await withTelegram(page);
  await stubApi(page, {
    workflow: { status: 409, body: { category: 'INVALID_WORKFLOW', message: 'Nope.' } },
  });

  await page.goto('/');
  await review(page).click();
  await expect(page.getByRole('heading', { name: 'Junior Java Backend Engineer' })).toBeVisible();
  await page.locator('.actions').getByRole('button', { name: 'Save' }).click();

  // Back on the same vacancy, told what happened, and the undo prompt is gone.
  await expect(page.getByRole('alert')).toContainText('Change was rejected');
  await expect(page.getByRole('heading', { name: 'Junior Java Backend Engineer' })).toBeVisible();
  await expect(page.getByRole('button', { name: 'Undo' })).toBeHidden();

  await page.getByRole('button', { name: 'Dismiss' }).click();
  await expect(page.getByRole('alert')).toBeHidden();
});

test('keeps the vacancy out of Saved when its write failed', async ({ page }) => {
  await withTelegram(page);
  await stubApi(page, {
    workflow: { status: 503, body: { category: 'MINI_APP_DISABLED', message: 'Off.' } },
  });

  await page.goto('/');
  await review(page).click();
  await page.locator('.actions').getByRole('button', { name: 'Save' }).click();
  await expect(page.getByRole('alert')).toBeVisible();

  await page.getByRole('button', { name: 'Saved', exact: true }).click();
  await expect(page.getByRole('heading', { name: 'Nothing saved yet' })).toBeVisible();
});

// ------------------------------------------------------------------ leak checking

test('keeps the signed payload out of the DOM and the console', async ({ page }) => {
  const logs: string[] = [];
  page.on('console', (message) => logs.push(message.text()));
  page.on('pageerror', (error) => logs.push(error.message));

  await withTelegram(page);
  await stubApi(page);

  await page.goto('/');
  await review(page).click();
  await page.locator('.actions').getByRole('button', { name: 'Save' }).click();
  await expect(page.getByRole('heading', { name: 'Backend Developer, Platform' })).toBeVisible();

  const markup = await page.content();
  expect(markup).not.toContain(FAKE_INIT_DATA);
  expect(markup).not.toContain('hash=0000');
  for (const line of logs) expect(line).not.toContain(FAKE_INIT_DATA);

  // Nor is it parked anywhere a later script could read it back.
  const stored = await page.evaluate(() => ({
    local: JSON.stringify(localStorage),
    session: JSON.stringify(sessionStorage),
    cookie: document.cookie,
  }));
  expect(stored.local).not.toContain('auth_date');
  expect(stored.session).not.toContain('auth_date');
  expect(stored.cookie).not.toContain('auth_date');
});

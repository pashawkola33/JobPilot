import { expect, test, type Page } from '@playwright/test';

const review = (page: Page) => page.getByRole('button', { name: /^Review(,|$)/ });

test('discover leads with the queue count and the strongest matches', async ({ page }) => {
  await page.goto('/');

  await expect(page.getByRole('heading', { name: /vacancies waiting/ })).toContainText('6');
  await expect(page.getByRole('heading', { name: 'Strongest new matches' })).toBeVisible();
  await expect(page.getByRole('button', { name: /Junior Java Backend Engineer/ })).toBeVisible();
  await expect(page.getByRole('button', { name: 'Continue review' })).toBeVisible();
});

test('review shows one vacancy with its score and a clear action hierarchy', async ({ page }) => {
  await page.goto('/');
  await page.getByRole('button', { name: 'Continue review' }).click();

  await expect(page.getByRole('heading', { name: 'Junior Java Backend Engineer' })).toBeVisible();
  await expect(page.getByRole('img', { name: /Match score 91 out of 100. Excellent match./ })).toBeVisible();
  await expect(page.getByRole('link', { name: 'Open vacancy' })).toBeVisible();

  for (const action of ['Skip', 'Save', 'Applied']) {
    await expect(page.locator('.actions').getByRole('button', { name: action })).toBeVisible();
  }
});

test('saving advances the queue and the undo restores it', async ({ page }) => {
  await page.goto('/');
  await review(page).click();

  await expect(page.getByRole('heading', { name: 'Junior Java Backend Engineer' })).toBeVisible();
  await page.locator('.actions').getByRole('button', { name: 'Save' }).click();

  await expect(page.getByRole('heading', { name: 'Backend Developer, Platform' })).toBeVisible();
  const toast = page.getByRole('status').filter({ hasText: 'Saved' });
  await expect(toast).toContainText('Junior Java Backend Engineer');

  await toast.getByRole('button', { name: 'Undo' }).click();
  await expect(page.getByRole('heading', { name: 'Junior Java Backend Engineer' })).toBeVisible();
});

test('a saved vacancy reaches the saved list and the tracker', async ({ page }) => {
  await page.goto('/');
  await review(page).click();
  await page.locator('.actions').getByRole('button', { name: 'Save' }).click();

  await page.getByRole('button', { name: 'Saved', exact: true }).click();
  await expect(page.getByRole('button', { name: /Junior Java Backend Engineer/ })).toBeVisible();

  await page.getByRole('button', { name: 'Track' }).click();
  await expect(page.getByRole('button', { name: /Junior Java Backend Engineer/ })).toBeVisible();
});

test('details open in a modal sheet and Escape closes it', async ({ page }) => {
  await page.goto('/');
  await page.getByRole('button', { name: /Junior Java Backend Engineer/ }).click();

  const sheet = page.getByRole('dialog');
  await expect(sheet).toBeVisible();
  await expect(sheet.getByRole('heading', { name: 'Why this matched' })).toBeVisible();
  await expect(sheet.getByText('Requirements')).toBeVisible();
  // Tenants, external ids and screening codes stay out of the sheet by default.
  await expect(sheet).not.toContainText('northsail');

  await page.keyboard.press('Escape');
  await expect(sheet).not.toBeVisible();
});

test('applications filter down to a single stage and back', async ({ page }) => {
  await page.goto('/');
  await page.getByRole('button', { name: 'Track' }).click();

  await expect(page.getByRole('button', { name: /Backend Engineer, Payments/ })).toBeVisible();
  await page.getByRole('button', { name: /^Interview/ }).click();

  await expect(page.getByRole('button', { name: /Backend Engineer, Payments/ })).toBeVisible();
  await expect(page.getByRole('button', { name: /Junior Backend Engineer/ })).toBeHidden();
});

test('a failed load explains itself and offers a retry', async ({ page }) => {
  await page.goto('/?mock=fail');

  await expect(page.getByRole('heading', { name: 'Vacancies did not load' })).toBeVisible();
  await expect(page.getByRole('button', { name: 'Try again' })).toBeVisible();
});

test('the dark theme repaints the surfaces', async ({ page }) => {
  await page.goto('/');
  await page.getByRole('button', { name: 'Settings' }).click();
  await page.getByRole('button', { name: 'Dark', exact: true }).click();

  await expect(page.locator('html')).toHaveAttribute('data-theme', 'dark');
  // Assert the token itself: which element paints it varies with viewport width.
  const surface = await page
    .locator('html')
    .evaluate((node) => getComputedStyle(node).getPropertyValue('--bg').trim());
  expect(surface).toBe('#15181c');
});

test('every primary control carries an accessible name', async ({ page }) => {
  await page.goto('/');
  await review(page).click();

  const unnamed = await page.locator('button:visible, a:visible').evaluateAll((nodes) =>
    nodes
      .filter((node) => !(node.getAttribute('aria-label') ?? node.textContent ?? '').trim())
      .map((node) => node.outerHTML.slice(0, 80)),
  );
  expect(unnamed).toEqual([]);
});

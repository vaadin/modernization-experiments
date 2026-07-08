import { test, expect, Page } from '@playwright/test';

/**
 * E2E: selecting a FOLDER in the feeds tree shows the cumulative articles of all its feeds — and
 * crucially, clicking the folder's NAME (inside the expand-toggle) selects it, not just expands it.
 * Regression guard for the "folder name click did nothing" bug.
 */

const midSize = (page: Page) =>
  page.evaluate(() => (document.querySelectorAll('vaadin-grid')[1] as any).size as number);

// A tree node (feed/folder) row by its label text.
const treeNode = (page: Page, label: RegExp) =>
  page.locator('vaadin-grid-tree-toggle').filter({ hasText: label }).first();

test.beforeEach(async ({ page }) => {
  await page.goto('/');
  await expect(page.locator('input[placeholder^="Filter"]')).toBeVisible();
});

test('Clicking a folder name selects it and shows the cumulative list of all its feeds', async ({ page }) => {
  // Click the Business FOLDER by its name → selects + expands → cumulative list of all Business feeds.
  await treeNode(page, /^Business/).click();
  await expect.poll(() => midSize(page)).toBeGreaterThan(0);
  const folderSize = await midSize(page);

  // Now click a child feed (Fast Company, revealed by the expand) → just that feed's articles.
  await treeNode(page, /^Fast Company/).click();
  await expect.poll(() => midSize(page)).toBeLessThan(folderSize);
  const feedSize = await midSize(page);

  // The folder aggregates strictly more than any single child feed.
  expect(folderSize).toBeGreaterThan(feedSize);
});

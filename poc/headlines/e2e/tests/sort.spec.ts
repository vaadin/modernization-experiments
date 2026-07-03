import { test, expect, Page } from '@playwright/test';

/**
 * E2E: Vaadin multi-priority sort on the headline grid, persisted per user. (The extra Feed/Status/Sticky
 * columns and the persistence layer are covered by JUnit; here we exercise the real multi-column sort +
 * its round-trip through reload.)
 *
 * Note: the sort is persisted per user server-side, so this test normalizes alice's sort on entry and
 * resets it to the Date-only default on exit — otherwise the leftover sort would bleed into other specs.
 */

// Click a headline-grid column header's sorter to (multi-)sort by it. The sorter wrapper has no size of
// its own, so force the click past Playwright's visibility check.
const sortByColumn = (page: Page, label: string) =>
  page.locator('vaadin-grid').nth(1).locator('vaadin-grid-sorter')
    .filter({ hasText: label }).first().click({ force: true });

/** Labels of the currently active sorters (columns that are part of the sort). */
const activeSortCols = (page: Page) =>
  page.evaluate(() =>
    [...document.querySelectorAll('vaadin-grid')[1].querySelectorAll('vaadin-grid-sorter')]
      .filter((s: any) => s.direction)
      .map((s: any) => (s.textContent || '').trim()));

/** Current sort direction of a given column ('asc' | 'desc' | null). */
const sortDir = (page: Page, label: string) =>
  page.evaluate((lbl) => {
    const s: any = [...document.querySelectorAll('vaadin-grid')[1].querySelectorAll('vaadin-grid-sorter')]
      .find((x: any) => (x.textContent || '').trim() === lbl);
    return s ? s.direction || null : null;
  }, label);

/** Cycle a column's sorter (asc → desc → none) until it reaches the wanted state. */
async function setSort(page: Page, label: string, want: 'asc' | 'desc' | null) {
  for (let i = 0; i < 3; i++) {
    if ((await sortDir(page, label)) === want) return;
    await sortByColumn(page, label);
    await page.waitForTimeout(150);
  }
  expect(await sortDir(page, label)).toBe(want);
}

test.beforeEach(async ({ page }) => {
  await page.goto('/');
  await expect(page.locator('input[placeholder^="Filter"]')).toBeVisible();
  await setSort(page, 'Author', null); // normalize: no Author sort to start from a known baseline
});

test.afterEach(async ({ page }) => {
  await setSort(page, 'Author', null); // clean up so the persisted sort doesn't leak into other specs
});

test('Multi-priority sort persists per user across reload', async ({ page }) => {
  // Baseline: Date (desc) is the default primary sort; Author is not sorted.
  await expect.poll(async () => activeSortCols(page)).toEqual(['Date']);

  // Add Author as a second sort column (multi-sort append).
  await setSort(page, 'Author', 'asc');
  await expect.poll(async () => activeSortCols(page)).toEqual(expect.arrayContaining(['Date', 'Author']));

  // Reload → the multi-column sort is restored (persisted per user). Let the persist round-trip settle
  // before reloading so we test restore, not a reload-before-save race.
  await page.waitForTimeout(500);
  await page.reload();
  await expect(page.locator('input[placeholder^="Filter"]')).toBeVisible();
  await expect.poll(async () => activeSortCols(page)).toEqual(expect.arrayContaining(['Date', 'Author']));
});

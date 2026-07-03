import { test, expect, Page } from '@playwright/test';

/**
 * E2E tests for the RSSOwl-style Filter Bar: a live, LOCAL filter of the currently displayed
 * headlines, defaulting to the title, with a scope selector (Title / Entire article / Author /
 * Category). Mirrors RSSOwl's news-view FilterBar (SearchTarget = Headline by default).
 */

const FILTER = 'input[placeholder="Filter headlines…"]';
const SCOPE = 'vaadin-select[title="What the filter text matches"]';

/** Titles of the currently rendered headline rows (reads the Vaadin grid's slotted cell content). */
async function visibleTitles(page: Page): Promise<string[]> {
  return page.evaluate(() => {
    const grid = document.querySelectorAll('vaadin-grid')[1] as HTMLElement;
    const out: string[] = [];
    grid.querySelectorAll('vaadin-grid-cell-content').forEach((c) => {
      const hasStateIcon = c.querySelector('vaadin-icon[icon^="vaadin:circle"]');
      const text = (c.textContent || '').trim();
      if (hasStateIcon && text) out.push(text);
    });
    return out;
  });
}

test.beforeEach(async ({ page }) => {
  await page.goto('/');
  await expect(page.locator(FILTER)).toBeVisible();
});

test('Title scope (default) narrows the list to title matches only', async ({ page }) => {
  await page.fill(FILTER, 'Linux');

  await expect
    .poll(async () => (await visibleTitles(page)).length, { message: 'some rows match' })
    .toBeGreaterThan(0);

  // Every visible row must have "linux" in its TITLE — no body/author/feed matches leak in.
  await expect
    .poll(async () => (await visibleTitles(page)).every((t) => /linux/i.test(t)))
    .toBe(true);
});

test('Clearing the filter restores non-matching rows', async ({ page }) => {
  await page.fill(FILTER, 'Linux');
  await expect.poll(async () => (await visibleTitles(page)).every((t) => /linux/i.test(t))).toBe(true);

  await page.fill(FILTER, '');
  // With no filter, the list shows rows whose titles do NOT all contain "linux".
  await expect.poll(async () => (await visibleTitles(page)).some((t) => !/linux/i.test(t))).toBe(true);
});

test('Entire-article scope broadens matching beyond the title', async ({ page }) => {
  await page.fill(FILTER, 'Linux');
  await expect.poll(async () => (await visibleTitles(page)).every((t) => /linux/i.test(t))).toBe(true);

  // Switch scope Title -> Entire article.
  await page.locator(SCOPE).click();
  await page.locator('vaadin-select-item', { hasText: 'Entire article' }).click();

  // Now at least one visible row matches on the body/feed/author, not the title.
  await expect
    .poll(async () => (await visibleTitles(page)).some((t) => !/linux/i.test(t)), {
      message: 'entire-scope surfaces non-title (body) matches',
    })
    .toBe(true);
});

import { test as setup, expect } from '@playwright/test';

/** Log in through Keycloak once as alice and persist the session for the other tests. */
const AUTH_FILE = '.auth/alice.json';

setup('authenticate as alice', async ({ page }) => {
  await page.goto('/');

  // Redirected to the Keycloak login form (unless a session already exists).
  if (page.url().includes('/realms/')) {
    await page.fill('input[name="username"]', process.env.E2E_USER || 'alice');
    await page.fill('input[name="password"]', process.env.E2E_PASS || 'alice');
    await page.click('input[type="submit"], button[type="submit"]');
  }

  // Back in the app: the toolbar Filter Bar is present once the view has rendered.
  await expect(page.locator('input[placeholder="Filter headlines…"]')).toBeVisible({ timeout: 20_000 });
  await page.context().storageState({ path: AUTH_FILE });
});

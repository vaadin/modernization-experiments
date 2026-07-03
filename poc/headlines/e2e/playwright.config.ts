import { defineConfig, devices } from '@playwright/test';

/**
 * E2E config for the headlines PoC. Assumes the app is already running at BASE_URL (default
 * http://localhost:8080 — start it with `./run.sh`) and that Keycloak is reachable for login.
 *
 * A one-time `setup` project logs in as alice and stores the authenticated session; the real tests
 * reuse it via storageState, so each test starts already signed in.
 */
export default defineConfig({
  testDir: './tests',
  fullyParallel: false,
  timeout: 30_000,
  expect: { timeout: 10_000 },
  retries: 1, // the dev laptop sleeps/starves under load; one retry absorbs env-induced flakiness
  reporter: [['list']],
  use: {
    baseURL: process.env.BASE_URL || 'http://localhost:8080',
    viewport: { width: 1600, height: 1000 }, // wide enough for the full toolbar (Columns menu etc.)
    trace: 'on-first-retry',
  },
  projects: [
    { name: 'setup', testMatch: /auth\.setup\.ts/ },
    {
      name: 'chromium',
      testMatch: /.*\.spec\.ts/,
      use: { ...devices['Desktop Chrome'], storageState: '.auth/alice.json' },
      dependencies: ['setup'],
    },
  ],
});

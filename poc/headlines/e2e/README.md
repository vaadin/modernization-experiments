# E2E tests (Playwright)

Browser-level tests for behaviours the headless JUnit tier can't cover — starting with the
RSSOwl-style **Filter Bar** (live, local, title-by-default headline filter with a scope selector).

Most logic is unit-tested in JUnit (e.g. `HeadlineFilterTest`); these Playwright specs verify the
end-to-end wiring in a real browser.

## Prerequisites
- The app running at `http://localhost:8080` (from `poc/headlines/`: `./run.sh`).
- Keycloak reachable (login as `alice`/`alice`).

## Run
```bash
cd poc/headlines/e2e
npm install
npm run install-browser   # one-time: downloads Chromium
npm test                  # or: npm run test:headed
```
Override defaults with env vars: `BASE_URL`, `E2E_USER`, `E2E_PASS`.

## What it covers (`tests/filter.spec.ts`)
- **Title scope (default):** typing `Linux` shows only rows whose *title* contains it.
- **Clear:** emptying the box restores non-matching rows.
- **Entire-article scope:** switching scope broadens matches beyond the title (body/feed/author).

`auth.setup.ts` logs in once and stores the session (`.auth/alice.json`) so each test starts signed in.

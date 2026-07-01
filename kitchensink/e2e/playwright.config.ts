import { defineConfig, devices } from '@playwright/test';

/**
 * Playwright E2E configuration for the Net32 Dental Supply storefront.
 *
 * A real browser drives the PHP frontend (default http://127.0.0.1:8080), which
 * in turn calls the three Spring Boot services:
 *   - marketplace-service :8081 /marketplace
 *   - orders-service      :8082 /orders
 *   - users-service       :8083 /users
 *
 * Backend service health is gated by the `wait:health` / `pretest` npm script
 * (wait-on). This config additionally starts (or reuses) the PHP built-in web
 * server that serves the frontend so that `/frontend/*.php` URLs resolve.
 */

const FRONTEND_BASE_URL = process.env.FRONTEND_BASE_URL ?? 'http://127.0.0.1:8080';
const isCI = !!process.env.CI;

// Derive the PHP server bind host/port from the frontend base URL so an override
// of FRONTEND_BASE_URL keeps the managed PHP server in sync.
const frontendUrl = new URL(FRONTEND_BASE_URL);
const phpHost = frontendUrl.hostname || '127.0.0.1';
const phpPort = frontendUrl.port || '8080';

// Allow CI (or advanced users) to opt out of Playwright managing the PHP server
// (e.g. when the frontend is started by the CI workflow itself).
const manageFrontend = process.env.E2E_DISABLE_WEBSERVER !== '1';

export default defineConfig({
  testDir: './tests',
  testMatch: '**/*.spec.ts',

  // Shared physical PostgreSQL DB across services → serialize tests to avoid
  // cart / total_spend / tier state contention between specs.
  fullyParallel: false,
  workers: 1,

  forbidOnly: isCI,
  retries: isCI ? 2 : 0,
  timeout: 60_000,
  expect: { timeout: 10_000 },

  reporter: [['list'], ['html', { open: 'never' }]],

  use: {
    baseURL: FRONTEND_BASE_URL,
    actionTimeout: 15_000,
    navigationTimeout: 30_000,
    trace: 'on-first-retry',
    screenshot: 'only-on-failure',
    video: 'retain-on-failure',
  },

  projects: [
    { name: 'chromium', use: { ...devices['Desktop Chrome'] } },
    // Additional browsers can be enabled when desired:
    // { name: 'firefox', use: { ...devices['Desktop Firefox'] } },
    // { name: 'webkit',  use: { ...devices['Desktop Safari'] } },
  ],

  webServer: manageFrontend
    ? {
        // Serve the PHP storefront with kitchensink/ as the document root so that
        // absolute links like /frontend/index.php resolve. Run from e2e/ → `..` = kitchensink/.
        // `router.php` (kitchensink/router.php) returns a real 404 for missing routes
        // instead of the built-in server's walk-up index fallback (Issue 10), and
        // `-d expose_php=0` suppresses the X-Powered-By version banner (Issue 9).
        command: `php -S ${phpHost}:${phpPort} -d expose_php=0 -t .. ../router.php`,
        url: `${FRONTEND_BASE_URL}/frontend/index.php`,
        timeout: 120_000,
        reuseExistingServer: !isCI,
        stdout: 'pipe',
        stderr: 'pipe',
        env: { PHP_CLI_SERVER_WORKERS: '4' },
      }
    : undefined,
});

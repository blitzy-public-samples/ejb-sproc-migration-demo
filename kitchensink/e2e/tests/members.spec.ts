import { test, expect, Page } from '@playwright/test';

/**
 * members.spec.ts — Users-service member + tier contracts, proven end-to-end.
 *
 * Two complementary layers:
 *   1) LIVE users-service API assertions (Playwright `request` fixture) against the running
 *      users-service (:8083, context-path /users): GET /api/members, GET /api/members/{id},
 *      and GET /api/members/{id}/tier, plus an unknown-member 404 for both single-member
 *      endpoints. This proves Contract 2's producer surface (the member lookup and the
 *      {"tier":"..."} response shape) through the running system — NOT a hardcoded demo map.
 *   2) The frontend demo-login flow (page fixture) against the PHP storefront, which renders
 *      the member name + tier badge after login.
 *
 * A2 resolution: register.php does not exist; the login form is embedded in index.php and logs
 * in by member ID.
 *
 * Seed authority (db/03_seed_data.sql): member 1 = Jane Smith (GOLD), member 2 = Robert Torres
 * (SILVER), member 3 = Emily Chen (BRONZE).
 */

// Users-service base URL (:8083, context-path /users). Overridable via env for CI; the default
// host matches the wait:health convention in package.json (localhost). A trailing slash, if any,
// is stripped so path concatenation stays clean.
const USERS_BASE_URL = (process.env.USERS_BASE_URL ?? 'http://localhost:8083/users').replace(/\/+$/, '');

const SEED_MEMBERS = [
  { id: 1, name: 'Jane Smith', tier: 'GOLD' },
  { id: 2, name: 'Robert Torres', tier: 'SILVER' },
  { id: 3, name: 'Emily Chen', tier: 'BRONZE' },
];

const UNKNOWN_MEMBER_ID = 99;

// ---------------------------------------------------------------------------
// Layer 1 — LIVE users-service contract (Contract 2 producer surface).
//   These assertions hit the running users-service directly over HTTP, so a green run proves
//   the member-lookup and tier contracts end-to-end rather than echoing PHP demo state.
// ---------------------------------------------------------------------------
test.describe('Users-service — live member + tier contract', () => {
  for (const m of SEED_MEMBERS) {
    test(`GET /api/members/${m.id} returns ${m.name} (${m.tier})`, async ({ request }) => {
      const res = await request.get(`${USERS_BASE_URL}/api/members/${m.id}`);
      expect(res.status()).toBe(200);
      const body = await res.json();
      expect(body.id).toBe(m.id);
      expect(body.name).toBe(m.name);
      expect(body.tier).toBe(m.tier);
    });

    test(`GET /api/members/${m.id}/tier returns {"tier":"${m.tier}"}`, async ({ request }) => {
      const res = await request.get(`${USERS_BASE_URL}/api/members/${m.id}/tier`);
      expect(res.status()).toBe(200);
      const body = await res.json();
      // MemberTierResponse is exactly { "tier": "<BRONZE|SILVER|GOLD|PLATINUM>" }.
      expect(body).toEqual({ tier: m.tier });
    });
  }

  test('GET /api/members lists the seeded members with their tiers', async ({ request }) => {
    const res = await request.get(`${USERS_BASE_URL}/api/members`);
    expect(res.status()).toBe(200);
    const members = await res.json();
    expect(Array.isArray(members)).toBe(true);
    for (const seed of SEED_MEMBERS) {
      const found = members.find((x: { id: number; tier: string }) => x.id === seed.id);
      expect(found, `member ${seed.id} present in listing`).toBeTruthy();
      expect(found.tier).toBe(seed.tier);
    }
  });

  test(`GET /api/members/${UNKNOWN_MEMBER_ID} (unknown member) returns 404`, async ({ request }) => {
    const res = await request.get(`${USERS_BASE_URL}/api/members/${UNKNOWN_MEMBER_ID}`);
    expect(res.status()).toBe(404);
  });

  test(`GET /api/members/${UNKNOWN_MEMBER_ID}/tier (unknown member) returns 404`, async ({ request }) => {
    const res = await request.get(`${USERS_BASE_URL}/api/members/${UNKNOWN_MEMBER_ID}/tier`);
    expect(res.status()).toBe(404);
  });
});

// ---------------------------------------------------------------------------
// Layer 2 — Frontend demo-login flow (PHP storefront).
// ---------------------------------------------------------------------------
function memberIdInput(page: Page) {
  return page.locator('[data-testid="login-member-id"], [data-testid="member-id-input"], #member_id');
}

function loginSubmit(page: Page) {
  return page.locator('[data-testid="login-submit"], form button[type="submit"]').first();
}

test.describe('Members — login and tier badge', () => {
  for (const m of SEED_MEMBERS) {
    test(`member ${m.id} logs in and sees ${m.tier} tier badge`, async ({ page }) => {
      await page.goto('/frontend/index.php');
      await expect(page.locator('[data-testid="login-form"], form').first()).toBeVisible();
      await memberIdInput(page).fill(String(m.id));
      await loginSubmit(page).click();
      await expect(page.locator('[data-testid="alert-success"], .alert-success').first()).toContainText(
        `Logged in as ${m.name} (${m.tier})`,
      );
      await expect(page.locator('header')).toContainText(m.name);
      await expect(
        page.locator('[data-testid="member-tier-badge"], header span.tier-badge').first(),
      ).toContainText(m.tier);
    });
  }

  test('invalid member id is rejected and no tier badge appears', async ({ page }) => {
    await page.goto('/frontend/index.php');
    await memberIdInput(page).fill(String(UNKNOWN_MEMBER_ID));
    await loginSubmit(page).click();
    await expect(page.locator('[data-testid="alert-error"], .alert-error').first()).toContainText('Invalid member ID');
    await expect(page.locator('[data-testid="member-tier-badge"], header span.tier-badge')).toHaveCount(0);
    await expect(page.locator('header').getByRole('link', { name: 'Login' })).toBeVisible();
  });
});

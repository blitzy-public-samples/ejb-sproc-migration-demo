import { test, expect, Page } from '@playwright/test';

/**
 * members.spec.ts — Member login + tier badge.
 *
 * A2 resolution: the prompt's register.php does NOT exist; the demo login/registration form
 * is embedded in index.php (login by member ID). This spec targets index.php.
 *
 * The demo login accepts member IDs 1/2/3 and renders the tier from a demo map that mirrors
 * the seed tiers (db/03_seed_data.sql): 1 = Jane Smith GOLD, 2 = Robert Torres SILVER,
 * 3 = Emily Chen BRONZE. No assertion here depends on a live users-service tier call.
 *
 * Selector strategy: PRIMARY data-testid combined with a verified DOM/text fallback.
 */

const SEED_MEMBERS = [
  { id: 1, name: 'Jane Smith', tier: 'GOLD' },
  { id: 2, name: 'Robert Torres', tier: 'SILVER' },
  { id: 3, name: 'Emily Chen', tier: 'BRONZE' },
];

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

      // Success alert echoes "Logged in as <Name> (<TIER>)".
      await expect(page.locator('[data-testid="alert-success"], .alert-success').first()).toContainText(
        `Logged in as ${m.name} (${m.tier})`,
      );

      // Header shows the member name and the tier badge.
      await expect(page.locator('header')).toContainText(m.name);
      await expect(
        page.locator('[data-testid="member-tier-badge"], header span.tier-badge').first(),
      ).toContainText(m.tier);
    });
  }

  test('invalid member id is rejected and no tier badge appears', async ({ page }) => {
    await page.goto('/frontend/index.php');

    await memberIdInput(page).fill('99');
    await loginSubmit(page).click();

    // Error alert is shown.
    await expect(page.locator('[data-testid="alert-error"], .alert-error').first()).toContainText(
      'Invalid member ID',
    );

    // Still logged out: no tier badge, and the header shows a Login link.
    await expect(page.locator('[data-testid="member-tier-badge"], header span.tier-badge')).toHaveCount(0);
    await expect(page.locator('header').getByRole('link', { name: 'Login' })).toBeVisible();
  });
});

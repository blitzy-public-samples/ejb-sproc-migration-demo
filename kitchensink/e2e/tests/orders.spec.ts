import { test, expect, Locator } from '@playwright/test';

/**
 * orders.spec.ts — End-to-end order happy path: login -> add to cart -> preview -> submit.
 *
 * Exercises orders-service (cart / preview / submit) plus its cross-service HTTP calls to
 * marketplace-service (pricing + vendor selection) and users-service (tier discount). Single
 * sequential test; the suite runs with workers:1 / fullyParallel:false (shared PostgreSQL DB).
 *
 * A3: the cart persists only productId + quantity (no vendorId); the vendor is selected later at
 *     preview/submit time, so add-to-cart needs no vendor input.
 * A5: preview REQUIRES a ZIP — 27601 is supplied before previewing.
 *
 * Parity (member 1 + product 1 @ qty 1, ZIP 27601 -> Southeast 0.95/lb), AAP §0.6.1/§0.7.2:
 *   unit price 8.49 x 1.08 = 9.1692 -> $9.17 (markup-based, tier-independent) ;
 *   shipping GREATEST(5.99, 0.95 x 0.55) = $5.99 ;
 *   discount = tier pct x subtotal. Member 1 recalculates to BRONZE at startup (no seed orders
 *   -> 0 rolling 90-day spend; see members.spec.ts "Tier authority"), so BRONZE 2% ->
 *   ROUND(9.1692 x 0.02, 2) = $0.18 and total ~ $14.98. (Were member 1 GOLD, it would be 8% ->
 *   $0.73 and total ~ $14.43 — hence the checks below assert discount > 0 rather than an exact
 *   tier-dependent figure.)
 * Robust checks are preferred over hardcoding subtotal/discount (orders-service rounding is
 * authored in parallel): relational invariant (total ~= subtotal - discount + shipping, +/-$0.01),
 * the shipping floor ($5.99), the unit price ($9.17), and discount > 0.
 */

// Strip "$", commas, a leading "- ", and whitespace, then parseFloat ("- $1,234.56" -> 1234.56).
function parseCurrency(text: string | null | undefined): number {
  return parseFloat((text ?? '').replace(/[^0-9.]/g, ''));
}

// Resolve a money cell within the order-summary card: testid-first, positional td.price fallback.
// Order of value cells in the summary table: 0 = Subtotal, 1 = Discount, 2 = Shipping, 3 = Total.
async function moneyCell(summary: Locator, testids: string[], priceIndex: number): Promise<Locator> {
  const sel = testids.map((id) => `[data-testid="${id}"]`).join(', ');
  const byTestId = summary.locator(sel).first();
  if (await byTestId.count()) return byTestId;
  return summary.locator('td.price').nth(priceIndex);
}

test('order happy path: login, add to cart, preview, submit', async ({ page }) => {
  // 1) Login as member 1 (Jane Smith; recalculates to BRONZE at startup — see members.spec.ts).
  await page.goto('/frontend/index.php');
  await page.locator('[data-testid="login-member-id"], [data-testid="member-id-input"], #member_id').fill('1');
  await page.locator('[data-testid="login-submit"], form button[type="submit"]').first().click();
  await expect(page.locator('[data-testid="alert-success"], .alert-success').first()).toContainText(
    'Logged in as Jane Smith',
  );

  // 2) Add product 1 to the cart at the default quantity 1.
  //    CAUTION: the qty input auto-submits on change (oninput="this.form.submit()"), so we leave
  //    it at its default value of 1 and simply click Add to Cart.
  await page.goto('/frontend/product.php?id=1');
  await page
    .locator('[data-testid="add-to-cart"], [data-testid="add-to-cart-button"], button[name="add_to_cart"]')
    .first()
    .click();
  await expect(page.locator('[data-testid="alert-success"], .alert-success').first()).toContainText('added');

  // 3) Preview (A5): go to the cart, supply the ZIP, submit the preview form.
  await page.goto('/frontend/cart.php');
  await page.locator('[data-testid="cart-zip"], [data-testid="zip-input"], #zip').fill('27601');
  await page
    .locator('[data-testid="cart-preview-submit"], [data-testid="preview-submit"], #previewForm button[type="submit"]')
    .first()
    .click();

  let summary = page.locator('[data-testid="order-summary"]').first();
  if (!(await summary.count())) {
    summary = page.locator('.card').filter({ hasText: 'Order Summary' }).first();
  }
  await expect(summary).toBeVisible();

  // 4) Summary assertions.
  const subtotalCell = await moneyCell(summary, ['cart-subtotal', 'summary-subtotal'], 0);
  const discountCell = await moneyCell(summary, ['cart-discount', 'summary-discount'], 1);
  const shippingCell = await moneyCell(summary, ['cart-shipping', 'summary-shipping'], 2);
  const totalCell = await moneyCell(summary, ['cart-total', 'summary-total'], 3);

  for (const cell of [subtotalCell, discountCell, shippingCell, totalCell]) {
    await expect(cell).toBeVisible();
    await expect(cell).toContainText('$');
  }

  const subtotal = parseCurrency(await subtotalCell.innerText());
  const discount = parseCurrency(await discountCell.innerText());
  const shipping = parseCurrency(await shippingCell.innerText());
  const total = parseCurrency(await totalCell.innerText());

  // Deterministic: single light item -> shipping floor $5.99.
  expect(shipping).toBeCloseTo(5.99, 2);
  // Tier discount applied (> 0). Member 1 is BRONZE at startup -> 2% -> ~$0.18 at this subtotal
  // (would be $0.73 if GOLD); asserting > 0 keeps this robust to the recalculated tier.
  expect(discount).toBeGreaterThan(0);
  // Relational invariant (primary, robust): total ~= subtotal - discount + shipping (+/- $0.01).
  expect(Math.abs(total - (subtotal - discount + shipping))).toBeLessThanOrEqual(0.01);

  // Product-1 line unit price = $9.17 (canonical pricing parity 8.49 x 1.08; AAP §0.7.2).
  let itemRow = page.locator('[data-testid="cart-row"], [data-testid="cart-item-row"]').first();
  if (!(await itemRow.count())) {
    itemRow = page.locator('table').first().locator('tbody tr').first();
  }
  await expect(itemRow).toContainText('$9.17');

  // 5) Submit: proceed to checkout, then confirm the order.
  await page
    .locator('[data-testid="cart-checkout-link"], [data-testid="proceed-checkout-link"], a:has-text("Proceed to Checkout")')
    .first()
    .click();

  let checkoutSummary = page.locator('[data-testid="order-summary"], [data-testid="checkout-summary"]').first();
  if (!(await checkoutSummary.count())) {
    checkoutSummary = page.locator('.card').filter({ hasText: 'Order Summary' }).first();
  }
  await expect(checkoutSummary).toBeVisible();

  await page
    .locator('[data-testid="checkout-confirm"], [data-testid="confirm-order-button"], button[name="confirm_order"]')
    .first()
    .click();

  await expect(
    page.locator('[data-testid="checkout-success"], [data-testid="order-success"], .alert-success').first(),
  ).toContainText(/Order #\d+ placed successfully/);
});

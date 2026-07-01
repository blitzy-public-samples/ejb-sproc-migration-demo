import { test, expect } from '@playwright/test';

/**
 * products.spec.ts — Marketplace contract surface (catalog + product detail / vendor pricing).
 *
 * Drives catalog.php and product.php (A2 resolution: these existing pages are targeted,
 * NOT a non-existent products.php). The PHP frontend makes the server-side HTTP calls to
 * marketplace-service (:8081 /marketplace), so a green suite proves the catalog + product
 * + vendor-selection contracts are intact end-to-end.
 *
 * Seed authority (db/03_seed_data.sql):
 *   - product 1 = "Latex Exam Gloves Medium 100ct", SKU GLV-LTX-M-100, base price 8.49, PPE.
 *   - vendor 1 = "Henry Schein Dental" (rating 4.9, 1-day ship, 8% markup on product 1) = BEST.
 *
 * Selector strategy: PRIMARY data-testid (per AAP §0.3.4) combined with a VERIFIED DOM/text
 * fallback so the suite is resilient whether the frontend's testid names land exactly or not.
 */

const PRODUCT_1 = {
  id: 1,
  name: 'Latex Exam Gloves Medium 100ct',
  sku: 'GLV-LTX-M-100',
  basePrice: '$8.49',
  bestVendor: 'Henry Schein Dental',
};

test.describe('Marketplace — catalog and product detail', () => {
  test('catalog lists seeded products', async ({ page }) => {
    await page.goto('/frontend/catalog.php');

    const catalogTable = page.locator('[data-testid="catalog-table"], table').first();
    await expect(catalogTable).toBeVisible();

    // Seeded product 1 present (SKU rendered inside <code>, name in its own cell).
    await expect(catalogTable.getByText(PRODUCT_1.sku)).toBeVisible();
    await expect(catalogTable.getByText(PRODUCT_1.name)).toBeVisible();

    // A "Details" link to product 1 exists.
    const detailsLink = page
      .locator('[data-testid="product-details-link"][href*="product.php?id=1"], a[href*="product.php?id=1"]')
      .first();
    await expect(detailsLink).toBeVisible();
  });

  test('product detail shows fields and best-vendor pricing', async ({ page }) => {
    // Prefer navigating through the catalog details link; fall back to a direct goto.
    await page.goto('/frontend/catalog.php');
    const detailsLink = page
      .locator('[data-testid="product-details-link"][href*="product.php?id=1"], a[href*="product.php?id=1"]')
      .first();
    if (await detailsLink.count()) {
      await detailsLink.click();
    } else {
      await page.goto('/frontend/product.php?id=1');
    }
    await expect(page).toHaveURL(/product\.php\?id=1/);

    // Product fields (testid-first, DOM fallback). Name is rendered in the page <h2>.
    await expect(page.locator('[data-testid="product-name"], h2').first()).toContainText(PRODUCT_1.name);
    await expect(page.getByText(PRODUCT_1.sku).first()).toBeVisible();
    await expect(page.locator('[data-testid="product-base-price"], .price').first()).toContainText(
      PRODUCT_1.basePrice,
    );

    // Vendor pricing table with the best vendor ranked first.
    const vendorTable = page.locator('[data-testid="vendor-table"], table').first();
    await expect(vendorTable).toBeVisible();

    const vendorRows = page.locator('[data-testid="vendor-row"], table tbody tr');
    await expect(vendorRows.first()).toBeVisible();
    expect(await vendorRows.count()).toBeGreaterThanOrEqual(1);

    // Row 0 is the best vendor: carries the "Best" badge and is Henry Schein Dental
    // (Source-A maximization picks lowest-price/highest-rating/fastest-ship — AAP §0.6.1).
    const bestRow = vendorRows.first();
    await expect(bestRow).toContainText('Best');
    await expect(bestRow).toContainText(PRODUCT_1.bestVendor);
    await expect(bestRow.locator('[data-testid="vendor-unit-price"], td.price').first()).toContainText('$');
  });

  // Optional / minimal: category filter narrows the catalog (marketplace category contract).
  test('category filter narrows the catalog', async ({ page }) => {
    await page.goto('/frontend/catalog.php');

    // product 1 is PPE; product 9 ("High-Speed Handpiece") is Equipment.
    const ppeLink = page.getByRole('link', { name: 'PPE', exact: true });
    if (await ppeLink.count()) {
      await ppeLink.first().click();
      await expect(page).toHaveURL(/category=PPE/);
    } else {
      await page.goto('/frontend/catalog.php?category=PPE');
    }

    await expect(page.getByText(PRODUCT_1.name)).toBeVisible(); // PPE product remains
    await expect(page.getByText('High-Speed Handpiece')).toHaveCount(0); // Equipment filtered out
  });
});

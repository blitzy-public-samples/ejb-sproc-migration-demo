<?php
require_once __DIR__ . '/includes/config.php';
require_once __DIR__ . '/includes/api_client.php';

$error   = '';
$success = '';

// Handle login form submission
if ($_SERVER['REQUEST_METHOD'] === 'POST' && isset($_POST['member_id'])) {
    $memberId = (int)$_POST['member_id'];
    // QA Issue 4: fetch the member's REAL details from users-service (GET /api/members/{id}) instead
    // of a hardcoded name/tier map. users-service recalculates each member's tier from 90-day rolling
    // CONFIRMED-order spend at startup, so the tier stored in the session must come from the live API
    // — otherwise the header badge and the checkout discount label (both read $_SESSION['member_tier'])
    // drift from the tier the discount math actually uses. api_get('/members/{id}') routes to
    // users-service; a non-existent member returns null (HTTP 404) and is rejected below.
    $member = $memberId > 0 ? api_get('/members/' . $memberId) : null;
    if (is_array($member) && isset($member['id'])) {
        $name = isset($member['name']) ? $member['name'] : ('Member ' . $memberId);
        $tier = isset($member['tier']) ? $member['tier'] : 'BRONZE';
        set_current_member((int)$member['id'], $name, $tier);
        $success = 'Logged in as ' . $name . ' (' . $tier . ')';
    } else {
        $error = 'Invalid member ID. Please enter an existing member ID.';
    }
}

// API call: GET /api/products  — fetch featured products for homepage
// Returns all products; we display first 6 as "featured"
$allProducts = api_get('/products') ?? [];
$featured    = array_slice($allProducts, 0, 6);

require_once __DIR__ . '/includes/header.php';
?>

<?php if ($error): ?>
    <div class="alert alert-error" data-testid="alert-error"><?php echo htmlspecialchars($error); ?></div>
<?php endif; ?>
<?php if ($success): ?>
    <div class="alert alert-success" data-testid="alert-success"><?php echo htmlspecialchars($success); ?></div>
<?php endif; ?>

<div class="card">
    <h2 style="margin-bottom:16px;">Welcome to <?php echo htmlspecialchars(APP_NAME); ?></h2>
    <p style="margin-bottom:20px; color:#555;">Professional dental supplies, competitively priced. Log in with your member ID to see personalized pricing and loyalty discounts.</p>

    <?php if (!get_current_member_id()): ?>
    <form method="POST" style="max-width:300px;" data-testid="login-form">
        <label for="member_id">Member ID (demo: 1, 2, or 3)</label>
        <input type="number" id="member_id" name="member_id" min="1" placeholder="Enter member ID" required data-testid="login-member-id">
        <button type="submit" class="btn btn-primary" data-testid="login-submit">Log In</button>
    </form>
    <?php else: ?>
    <p><a href="/frontend/catalog.php" class="btn btn-primary">Browse Full Catalog</a></p>
    <?php endif; ?>
</div>

<h3 style="margin-bottom:16px;">Featured Products</h3>
<?php if (empty($featured)): ?>
    <p style="color:#888;" data-testid="featured-empty">No products available. Ensure the API is running.</p>
<?php else: ?>
<div style="display:grid; grid-template-columns: repeat(auto-fill, minmax(280px,1fr)); gap:16px;" data-testid="featured-products">
    <?php foreach ($featured as $product): ?>
    <div class="card" style="margin-bottom:0;" data-testid="featured-product">
        <div style="font-size:0.75rem; color:#888; text-transform:uppercase; margin-bottom:6px;">
            <?php echo htmlspecialchars($product['category'] ?? 'General'); ?>
        </div>
        <h4 style="margin-bottom:8px; font-size:1rem;" data-testid="product-name"><?php echo htmlspecialchars($product['name']); ?></h4>
        <div style="font-size:0.8rem; color:#666; margin-bottom:8px;" data-testid="product-sku">SKU: <?php echo htmlspecialchars($product['sku']); ?></div>
        <div class="price" style="font-size:1.1rem; margin-bottom:14px;" data-testid="product-price">
            <?php echo format_currency($product['basePrice']); ?>
        </div>
        <a href="/frontend/product.php?id=<?php echo (int)$product['id']; ?>" class="btn btn-primary" data-testid="product-view-details">View Details</a>
    </div>
    <?php endforeach; ?>
</div>
<?php endif; ?>

<?php require_once __DIR__ . '/includes/footer.php'; ?>

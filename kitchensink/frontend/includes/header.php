<?php
// header.php — included on every page
$currentMemberId   = get_current_member_id();
$currentMemberName = $_SESSION['member_name'] ?? null;
$currentMemberTier = $_SESSION['member_tier'] ?? null;

$tierBadgeColors = [
    'BRONZE'   => '#cd7f32',
    'SILVER'   => '#a8a9ad',
    'GOLD'     => '#ffd700',
    'PLATINUM' => '#e5e4e2',
];
$tierColor = $tierBadgeColors[$currentMemberTier] ?? '#cd7f32';
?>
<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title><?php echo htmlspecialchars(APP_NAME); ?></title>
    <!--
      Inline SVG data-URI favicon (Issue 11). Providing an explicit icon stops the
      browser from auto-requesting /favicon.ico (which previously 404'd and produced
      console/network noise) without adding a static asset to the document root.
      A navy rounded square with a white "N" matches the Net32 header branding.
    -->
    <link rel="icon" href="data:image/svg+xml,%3Csvg%20xmlns='http://www.w3.org/2000/svg'%20viewBox='0%200%2032%2032'%3E%3Crect%20width='32'%20height='32'%20rx='6'%20fill='%231a3a5c'/%3E%3Ctext%20x='16'%20y='23'%20font-size='20'%20text-anchor='middle'%20fill='%23ffffff'%20font-family='Segoe%20UI,Arial,sans-serif'%20font-weight='700'%3EN%3C/text%3E%3C/svg%3E">
    <style>
        * { box-sizing: border-box; margin: 0; padding: 0; }
        body { font-family: 'Segoe UI', Tahoma, Geneva, Verdana, sans-serif; background: #f4f6f9; color: #333; }
        header { background: #1a3a5c; color: #fff; padding: 0 24px; display: flex; align-items: center; justify-content: space-between; height: 60px; }
        header .brand { font-size: 1.4rem; font-weight: 700; letter-spacing: 0.5px; }
        nav a { color: #cce0f5; text-decoration: none; margin-left: 20px; font-size: 0.95rem; }
        nav a:hover { color: #fff; text-decoration: underline; }
        .tier-badge { display: inline-block; padding: 3px 10px; border-radius: 12px; font-size: 0.75rem; font-weight: 700; color: #333; }
        .member-info { display: flex; align-items: center; gap: 10px; font-size: 0.9rem; }
        main { max-width: 1200px; margin: 30px auto; padding: 0 20px; }
        .alert { padding: 12px 18px; border-radius: 6px; margin-bottom: 18px; }
        .alert-success { background: #d4edda; color: #155724; border: 1px solid #c3e6cb; }
        .alert-error   { background: #f8d7da; color: #721c24; border: 1px solid #f5c6cb; }
        table { width: 100%; border-collapse: collapse; background: #fff; border-radius: 8px; overflow: hidden; box-shadow: 0 1px 4px rgba(0,0,0,.08); }
        th, td { padding: 12px 16px; text-align: left; }
        thead { background: #1a3a5c; color: #fff; }
        tr:nth-child(even) { background: #f9fafc; }
        .btn { display: inline-block; padding: 8px 18px; border-radius: 5px; border: none; cursor: pointer; font-size: 0.9rem; text-decoration: none; }
        .btn-primary { background: #1a3a5c; color: #fff; }
        .btn-primary:hover { background: #254d7a; }
        .btn-danger  { background: #c0392b; color: #fff; }
        .btn-danger:hover  { background: #a93226; }
        .btn-success { background: #27ae60; color: #fff; }
        .btn-success:hover { background: #219a52; }
        .card { background: #fff; border-radius: 8px; box-shadow: 0 1px 4px rgba(0,0,0,.08); padding: 24px; margin-bottom: 20px; }
        label { display: block; margin-bottom: 5px; font-weight: 600; font-size: 0.9rem; }
        input, select { width: 100%; padding: 8px 12px; border: 1px solid #ccc; border-radius: 5px; font-size: 0.95rem; margin-bottom: 14px; }
        .price { font-weight: 700; color: #1a3a5c; }

        /*
         * Accessible keyboard focus indicator (Issue 8). :focus-visible only paints
         * for keyboard / assistive-tech focus, so mouse interactions on desktop are
         * visually unchanged while keyboard users get a clear high-contrast outline.
         */
        a:focus-visible,
        button:focus-visible,
        input:focus-visible,
        select:focus-visible,
        .btn:focus-visible {
            outline: 3px solid #ffd700;
            outline-offset: 2px;
        }

        /*
         * Responsive treatment for tablet / mobile (Issues 7 & 8).
         *
         * Issue 7 — eliminate horizontal overflow and the clipped fixed header:
         *   - The header switches from a fixed 60px single flex row to a stacked,
         *     auto-height column so the brand, nav, and member info never clip.
         *   - Wide data tables (the 6-column catalog / cart tables) become
         *     horizontally scrollable blocks, so their intrinsic ~627px width no
         *     longer forces the whole document to overflow past the viewport.
         *   - The product-detail two-column grid (inline "1fr 320px") collapses to a
         *     single column; a stylesheet !important overrides the inline declaration.
         *
         * Issue 8 — ensure ~44px touch targets:
         *   - Nav links and every .btn control get a 44px minimum height and are laid
         *     out as centered inline-flex boxes, so even small-padded buttons/pills
         *     (e.g. the catalog "Details" link) meet the recommended touch size.
         */
        @media (max-width: 768px) {
            header {
                flex-direction: column;
                align-items: flex-start;
                height: auto;
                min-height: 60px;
                padding: 12px 16px;
                gap: 10px;
            }
            header .brand { font-size: 1.2rem; line-height: 1.2; }
            nav { display: flex; flex-wrap: wrap; align-items: center; row-gap: 4px; }
            nav a {
                margin-left: 0;
                margin-right: 20px;
                min-height: 44px;
                display: inline-flex;
                align-items: center;
            }
            .member-info { gap: 10px; }

            /* Wide tables scroll within the viewport instead of overflowing the page. */
            table {
                display: block;
                width: 100%;
                max-width: 100%;
                overflow-x: auto;
                -webkit-overflow-scrolling: touch;
            }
            th, td { padding: 10px 12px; }

            /* Collapse the product-detail grid (inline "1fr 320px") to a single column. */
            [style*="grid-template-columns: 1fr 320px"] {
                grid-template-columns: 1fr !important;
            }

            /* >= 44px touch targets for all buttons / category pills / .btn links. */
            .btn {
                min-height: 44px;
                display: inline-flex;
                align-items: center;
                justify-content: center;
            }
        }
    </style>
</head>
<body>
<header>
    <div class="brand"><?php echo htmlspecialchars(APP_NAME); ?></div>
    <nav data-testid="nav">
        <a href="/frontend/index.php" data-testid="nav-home">Home</a>
        <a href="/frontend/catalog.php" data-testid="nav-catalog">Catalog</a>
        <a href="/frontend/cart.php" data-testid="nav-cart">Cart</a>
        <?php if ($currentMemberId): ?>
            <a href="/frontend/checkout.php" data-testid="nav-checkout">Checkout</a>
        <?php endif; ?>
    </nav>
    <div class="member-info">
        <?php if ($currentMemberId && $currentMemberName): ?>
            <span data-testid="member-name"><?php echo htmlspecialchars($currentMemberName); ?></span>
            <span class="tier-badge" style="background: <?php echo $tierColor; ?>" data-testid="member-tier-badge">
                <?php echo htmlspecialchars($currentMemberTier); ?>
            </span>
        <?php else: ?>
            <a href="/frontend/index.php" style="color:#cce0f5;" data-testid="nav-login">Login</a>
        <?php endif; ?>
    </div>
</header>
<main>

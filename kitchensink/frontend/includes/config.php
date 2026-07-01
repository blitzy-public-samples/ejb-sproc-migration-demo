<?php
define('MARKETPLACE_API_URL', 'http://localhost:8081/marketplace/api');
define('ORDERS_API_URL',      'http://localhost:8082/orders/api');
define('USERS_API_URL',       'http://localhost:8083/users/api');
define('APP_NAME', 'Net32 Dental Supply');

/*
 * Baseline security hardening headers (Issue 9).
 *
 * config.php is require_once'd as the very first statement of every storefront
 * page (index/catalog/product/cart/checkout), so emitting the headers here — before
 * any output — covers every entry point in one place. This mirrors the Spring Boot
 * services' SecurityHeadersFilter (X-Content-Type-Options / X-Frame-Options) so the
 * PHP tier is not the weak link.
 *
 *  - header_remove('X-Powered-By') suppresses the PHP version disclosure that the
 *    SAPI adds when expose_php is on (expose_php is PHP_INI_SYSTEM and cannot be
 *    toggled at runtime, so we strip the header instead; the documented server
 *    command additionally passes -d expose_php=0 for defense in depth).
 *  - X-Content-Type-Options: nosniff prevents MIME sniffing.
 *  - X-Frame-Options: SAMEORIGIN mitigates clickjacking.
 *  - Referrer-Policy limits referrer leakage to same-origin.
 */
if (!headers_sent()) {
    header_remove('X-Powered-By');
    header('X-Content-Type-Options: nosniff');
    header('X-Frame-Options: SAMEORIGIN');
    header('Referrer-Policy: same-origin');
}

session_start();

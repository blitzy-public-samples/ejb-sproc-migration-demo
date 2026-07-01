<?php
/**
 * API client helper for Net32 Dental Supply frontend.
 * All REST calls route through these helpers.
 */

/**
 * Resolves the correct per-domain base URL for a given API path (Ambiguity A1 routing).
 *
 * Routes by path prefix to the owning Spring Boot service:
 *   /products* -> marketplace-service (8081); /members* -> users-service (8083);
 *   /orders*|/cart* -> orders-service (8082). Falls back to marketplace.
 *
 * @param string $path  API path beginning with '/'
 * @return string       The per-domain base URL for this path
 */
function resolve_base_url(string $path): string {
    if (str_starts_with($path, '/products')) return MARKETPLACE_API_URL;
    if (str_starts_with($path, '/members'))  return USERS_API_URL;
    if (str_starts_with($path, '/orders') || str_starts_with($path, '/cart')) return ORDERS_API_URL;
    return MARKETPLACE_API_URL; // safe default (not reached by current call sites)
}

/**
 * Perform a GET request to the REST API.
 *
 * @param string $path  API path (e.g. "/products"); routed to the per-domain base URL
 * @return mixed        Decoded JSON response, or null on failure
 */
function api_get(string $path) {
    $url = resolve_base_url($path) . $path;
    $ch = curl_init($url);
    curl_setopt_array($ch, [
        CURLOPT_RETURNTRANSFER => true,
        CURLOPT_HTTPHEADER     => ['Accept: application/json'],
        CURLOPT_CONNECTTIMEOUT => 5,
        CURLOPT_TIMEOUT        => 15,
    ]);
    $response = curl_exec($ch);
    $httpCode = curl_getinfo($ch, CURLINFO_HTTP_CODE);
    curl_close($ch);

    if ($response === false || $httpCode >= 400) {
        return null;
    }
    return json_decode($response, true);
}

/**
 * Perform a POST request to the REST API.
 *
 * @param string $path  API path; routed to the per-domain base URL
 * @param array  $data  Data to JSON-encode and POST
 * @return array        ['code' => HTTP status, 'body' => decoded response]
 */
function api_post(string $path, array $data = []) {
    $url     = resolve_base_url($path) . $path;
    $payload = json_encode($data);
    $ch      = curl_init($url);
    curl_setopt_array($ch, [
        CURLOPT_RETURNTRANSFER => true,
        CURLOPT_POST           => true,
        CURLOPT_POSTFIELDS     => $payload,
        CURLOPT_HTTPHEADER     => [
            'Content-Type: application/json',
            'Accept: application/json',
            'Content-Length: ' . strlen($payload),
        ],
        CURLOPT_CONNECTTIMEOUT => 5,
        CURLOPT_TIMEOUT        => 15,
    ]);
    $response = curl_exec($ch);
    $httpCode = curl_getinfo($ch, CURLINFO_HTTP_CODE);
    curl_close($ch);

    return [
        'code' => $httpCode,
        'body' => $response ? json_decode($response, true) : null,
    ];
}

/**
 * Perform a DELETE request to the REST API.
 *
 * @param string $path  API path; routed to the per-domain base URL
 * @return array        ['code' => HTTP status, 'body' => decoded response]
 */
function api_delete(string $path) {
    $url = resolve_base_url($path) . $path;
    $ch  = curl_init($url);
    curl_setopt_array($ch, [
        CURLOPT_RETURNTRANSFER => true,
        CURLOPT_CUSTOMREQUEST  => 'DELETE',
        CURLOPT_HTTPHEADER     => ['Accept: application/json'],
        CURLOPT_CONNECTTIMEOUT => 5,
        CURLOPT_TIMEOUT        => 15,
    ]);
    $response = curl_exec($ch);
    $httpCode = curl_getinfo($ch, CURLINFO_HTTP_CODE);
    curl_close($ch);

    return [
        'code' => $httpCode,
        'body' => $response ? json_decode($response, true) : null,
    ];
}

/**
 * Returns the currently logged-in member ID from session, or null.
 *
 * @return int|null
 */
function get_current_member_id(): ?int {
    return $_SESSION['member_id'] ?? null;
}

/**
 * Sets the current member in the session.
 *
 * @param int    $memberId  the member ID
 * @param string $name      the member name
 * @param string $tier      the member tier (BRONZE, SILVER, GOLD, PLATINUM)
 */
function set_current_member(int $memberId, string $name, string $tier): void {
    $_SESSION['member_id']   = $memberId;
    $_SESSION['member_name'] = $name;
    $_SESSION['member_tier'] = $tier;
}

/**
 * Formats a numeric value as USD currency string.
 *
 * @param float|string $amount
 * @return string  e.g. "$12.99"
 */
function format_currency($amount): string {
    return '$' . number_format((float)$amount, 2);
}

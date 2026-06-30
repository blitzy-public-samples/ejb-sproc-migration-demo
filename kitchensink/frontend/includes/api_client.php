<?php
/**
 * API client helper for Net32 Dental Supply frontend.
 * All REST calls route through these helpers.
 */

/**
 * Resolves the correct per-service API base URL for a given REST path.
 *
 * The frontend was originally written against a single monolith base URL under which every
 * resource path (/products, /orders/..., /members/...) was served by one host. After the
 * monolith was decomposed into three Spring Boot services, each resource is owned by a
 * different service (its own host/port/context-path), so the base URL must be chosen per
 * call from the path's first segment. The preserved endpoint paths all sit under each
 * service's "/api" base mapping, which the per-service constants in config.php already include.
 *
 * This performs base-URL SELECTION ONLY — it changes which service host a request is sent to
 * (the permitted "repoint the API base URLs" migration concern) and alters no request payload,
 * header, response handling, or any other behavior.
 *
 * @param string $path API path beginning with the owning resource segment (e.g. "/products").
 * @return string      The owning service's API base URL with no trailing slash
 *                     (e.g. "http://localhost:8082/orders/api").
 */
function api_base_for_path(string $path): string {
    if (str_starts_with($path, '/products')) {
        return MARKETPLACE_API_BASE_URL;
    }
    if (str_starts_with($path, '/orders')) {
        return ORDERS_API_BASE_URL;
    }
    if (str_starts_with($path, '/members')) {
        return USERS_API_BASE_URL;
    }
    // Fallback for any unanticipated path: API_BASE_URL (the orders API base, see config.php).
    return API_BASE_URL;
}

/**
 * Perform a GET request to the REST API.
 *
 * @param string $path  API path relative to API_BASE_URL (e.g. "/products")
 * @return mixed        Decoded JSON response, or null on failure
 */
function api_get(string $path) {
    $url = api_base_for_path($path) . $path;
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
 * @param string $path  API path relative to API_BASE_URL
 * @param array  $data  Data to JSON-encode and POST
 * @return array        ['code' => HTTP status, 'body' => decoded response]
 */
function api_post(string $path, array $data = []) {
    $url     = api_base_for_path($path) . $path;
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
 * @param string $path  API path relative to API_BASE_URL
 * @return array        ['code' => HTTP status, 'body' => decoded response]
 */
function api_delete(string $path) {
    $url = api_base_for_path($path) . $path;
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

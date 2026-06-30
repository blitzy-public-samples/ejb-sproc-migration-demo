<?php
// Per-service Spring Boot base URLs (host:port + context-path).
// The legacy monolith WAR (http://localhost:8080/kitchensink/api) was decomposed
// into three independently deployable Spring Boot services.
define('MARKETPLACE_BASE_URL', 'http://localhost:8081/marketplace');
define('ORDERS_BASE_URL',      'http://localhost:8082/orders');
define('USERS_BASE_URL',       'http://localhost:8083/users');

// Per-service REST API base URLs. Every existing frontend call appends a path whose
// FIRST segment names the owning resource (/products, /orders/..., /members/...), and
// every preserved Spring REST endpoint lives under that service's "/api" base mapping
// (e.g. @RequestMapping("/api/products")). So each API base = <context-path> + "/api":
//   /products...  -> marketplace  http://localhost:8081/marketplace/api
//   /orders...    -> orders        http://localhost:8082/orders/api
//   /members...   -> users         http://localhost:8083/users/api
// includes/api_client.php selects the correct base per call via api_base_for_path().
define('MARKETPLACE_API_BASE_URL', MARKETPLACE_BASE_URL . '/api');
define('ORDERS_API_BASE_URL',      ORDERS_BASE_URL . '/api');
define('USERS_API_BASE_URL',       USERS_BASE_URL . '/api');

// API_BASE_URL is the fallback base for the single DIRECT consumer that does NOT go through
// the api_client.php helpers: the inline JS fetch() in cart.php, which always targets an
// "/orders/cart/{id}/preview" path. It therefore points at the orders API base so that fetch
// resolves to the orders service (http://localhost:8082/orders/api + /orders/cart/.../preview).
define('API_BASE_URL', ORDERS_API_BASE_URL);

define('APP_NAME', 'Net32 Dental Supply');
session_start();

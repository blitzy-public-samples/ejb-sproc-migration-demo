<?php
// Per-service Spring Boot base URLs (host:port + context-path).
// The legacy monolith WAR (http://localhost:8080/kitchensink/api) was decomposed
// into three independently deployable Spring Boot services.
define('MARKETPLACE_BASE_URL', 'http://localhost:8081/marketplace');
define('ORDERS_BASE_URL',      'http://localhost:8082/orders');
define('USERS_BASE_URL',       'http://localhost:8083/users');

// Retain API_BASE_URL so unchanged consumers (includes/api_client.php and the
// cart.php preview fetch) still resolve at runtime. Default to marketplace.
define('API_BASE_URL', MARKETPLACE_BASE_URL);

define('APP_NAME', 'Net32 Dental Supply');
session_start();

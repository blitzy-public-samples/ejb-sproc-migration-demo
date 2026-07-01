<?php
/**
 * PHP built-in web server router for the Net32 Dental Supply storefront (Issue 10).
 *
 * WHY THIS EXISTS
 * ---------------
 * `php -S` has a "walk-up" fallback: a request for a *missing* `*.php` path is
 * transparently served by the nearest existing directory index (here
 * `frontend/index.php`). As a result, non-existent routes such as
 * `/frontend/products.php`, `/frontend/register.php`, and
 * `/frontend/does-not-exist.php` all returned **HTTP 200 with the index page's
 * HTML** instead of a real 404. That masks broken links and page-name mismatches
 * and makes status-only smoke tests unreliable.
 *
 * WHAT THIS ROUTER DOES
 * ---------------------
 *   1. An existing concrete file (e.g. `/frontend/catalog.php`, a static asset) is
 *      served verbatim by returning `false` (the built-in server takes over and,
 *      for `.php`, executes it with the original method/body/query intact).
 *   2. A request that resolves to a real directory is served by that directory's
 *      `index.php` when present.
 *   3. Anything else returns a genuine **HTTP 404** with a small HTML body.
 *
 * A path-traversal guard ensures the resolved target stays within the document
 * root so `..` sequences cannot escape it.
 *
 * USAGE
 * -----
 *   php -S 127.0.0.1:8080 -t <kitchensink-docroot> router.php
 *
 * The document root remains `kitchensink/` (so absolute links like
 * `/frontend/index.php` resolve exactly as before); only the missing-route
 * behavior changes.
 *
 * @return bool  false  → let the built-in server serve the existing file as-is
 *               true   → this router fully handled the request (dir index or 404)
 */

$docRoot     = rtrim($_SERVER['DOCUMENT_ROOT'] ?? __DIR__, DIRECTORY_SEPARATOR);
$docRootReal = realpath($docRoot);

// Extract just the path component (drop any query string) and URL-decode it.
$uriPath = urldecode(parse_url($_SERVER['REQUEST_URI'] ?? '/', PHP_URL_PATH) ?? '/');

// Resolve the requested path within the document root.
$requested = realpath($docRoot . $uriPath);

// Only serve targets that genuinely live inside the document root.
if ($docRootReal !== false && $requested !== false && str_starts_with($requested, $docRootReal)) {
    // (1) Existing concrete file → let the built-in server serve it verbatim.
    if (is_file($requested)) {
        return false;
    }

    // (2) Existing directory → serve its index.php if one exists.
    if (is_dir($requested)) {
        $indexFile = $requested . DIRECTORY_SEPARATOR . 'index.php';
        if (is_file($indexFile)) {
            $normalizedDir              = '/' . trim(substr($requested, strlen($docRootReal)), '/');
            $scriptPath                 = rtrim($normalizedDir, '/') . '/index.php';
            $_SERVER['SCRIPT_NAME']     = $scriptPath;
            $_SERVER['PHP_SELF']        = $scriptPath;
            $_SERVER['SCRIPT_FILENAME'] = $indexFile;
            require $indexFile;
            return true;
        }
    }
}

// (3) No matching file or directory index → real 404.
http_response_code(404);
header('Content-Type: text/html; charset=UTF-8');
$safePath = htmlspecialchars($uriPath, ENT_QUOTES, 'UTF-8');
echo "<!DOCTYPE html>\n"
   . "<html lang=\"en\"><head><meta charset=\"UTF-8\"><title>404 Not Found</title></head>\n"
   . "<body style=\"font-family:'Segoe UI',Tahoma,sans-serif;background:#f4f6f9;color:#333;padding:40px;\">\n"
   . "<h1 style=\"color:#1a3a5c;\">404 Not Found</h1>\n"
   . "<p>The requested resource <code>{$safePath}</code> was not found on this server.</p>\n"
   . "<p><a href=\"/frontend/index.php\" style=\"color:#1a3a5c;\">Return to Net32 Dental Supply</a></p>\n"
   . "</body></html>";
return true;

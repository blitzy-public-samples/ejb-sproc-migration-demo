package org.jboss.as.quickstarts.kitchensink.users.config;

import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Per-client-IP rate limiter for the public member-registration endpoint ({@code POST /api/members}).
 *
 * <p>Member creation is intentionally left UNAUTHENTICATED to preserve the AAP member-create contract
 * (it returns 200 OK with no auth, which {@code RemoteMemberRegistrationIT} asserts). An open
 * write endpoint needs an abuse control, so this interceptor caps the number of registration attempts
 * a single client IP may make within a fixed 60-second window; requests beyond the cap are rejected
 * with HTTP 429 (Too Many Requests) before the controller runs.</p>
 *
 * <p>The limit is externally configurable via {@code security.registration.max-per-minute} (default
 * 20). The algorithm is a lightweight fixed-window counter held in a bounded {@link ConcurrentHashMap}
 * keyed by client IP; per-key updates use {@link ConcurrentHashMap#compute} so increments are atomic.
 * Stale entries (from previous windows) are opportunistically evicted once the map exceeds a cap, so
 * memory stays bounded without a background thread. This is deliberately in-process and best-effort
 * (sufficient for the demo's single-instance abuse control) rather than a distributed limiter, in
 * keeping with the migration's minimal-change discipline.</p>
 */
@Component
public class RegistrationRateLimitInterceptor implements HandlerInterceptor {

    /** Fixed window length in milliseconds. */
    private static final long WINDOW_MILLIS = 60_000L;

    /** Soft cap on tracked IPs; stale-window entries are pruned once exceeded. */
    private static final int MAX_TRACKED_IPS = 10_000;

    private final int maxPerMinute;
    private final ConcurrentHashMap<String, Counter> counters = new ConcurrentHashMap<>();

    public RegistrationRateLimitInterceptor(
            @Value("${security.registration.max-per-minute:20}") int maxPerMinute) {
        this.maxPerMinute = maxPerMinute;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!(handler instanceof HandlerMethod)) {
            return true;
        }
        // Only limit registration POSTs; GET /api/members (listing) is handled by the access-control
        // interceptor and must not be counted here.
        if (!"POST".equalsIgnoreCase(request.getMethod())) {
            return true;
        }

        long windowId = System.currentTimeMillis() / WINDOW_MILLIS;
        String clientIp = clientIp(request);

        // Opportunistic eviction of stale-window entries keeps the map bounded without a scheduler.
        if (counters.size() > MAX_TRACKED_IPS) {
            counters.values().removeIf(counter -> counter.windowId != windowId);
        }

        // Atomically obtain/refresh this IP's counter for the current window and increment it.
        Counter counter = counters.compute(clientIp, (ip, existing) -> {
            if (existing == null || existing.windowId != windowId) {
                return new Counter(windowId);
            }
            existing.count++;
            return existing;
        });

        if (counter.count > maxPerMinute) {
            response.setStatus(429); // 429 Too Many Requests
            return false;
        }
        return true;
    }

    /**
     * Rate-limiting client key: the direct TCP peer address ({@link HttpServletRequest#getRemoteAddr()}).
     *
     * <p><b>Trust boundary (CWE-348 hardening).</b> This service runs on embedded Tomcat with NO trusted
     * reverse proxy in front of it, and {@code server.forward-headers-strategy=none} is pinned in
     * {@code application.properties} (which prevents Spring Boot from auto-installing Tomcat's RemoteIp
     * valve on a detected cloud platform and thereby keeps {@code getRemoteAddr()} equal to the true TCP
     * peer). The {@code X-Forwarded-For} header is therefore entirely client-controlled and MUST NOT be
     * used to identify the client here. An earlier revision keyed on the first {@code X-Forwarded-For}
     * hop; because a direct attacker can set an arbitrary — and per-request different — value, that made
     * every spoofed value look like a distinct client and reset the per-window counter, defeating the
     * limiter. Keying strictly on the kernel-observed peer address, which a direct client cannot forge,
     * closes that bypass while preserving identical behavior for legitimate direct clients. Both defenses
     * are required and independent: the {@code none} strategy stops the header from rewriting
     * {@code getRemoteAddr()}, and reading only {@code getRemoteAddr()} here stops the (now pass-through)
     * header from being used as the key.</p>
     *
     * <p>If this service is later deployed behind a trusted reverse proxy and per-original-client
     * limiting is required, restore forwarded-header support the correct way — set
     * {@code server.forward-headers-strategy=framework} (or register a {@code ForwardedHeaderFilter})
     * with a trusted-proxy allowlist so the container resolves {@code getRemoteAddr()} from validated
     * hops — rather than reading the raw header in this interceptor.</p>
     */
    private static String clientIp(HttpServletRequest request) {
        String remote = request.getRemoteAddr();
        return remote != null ? remote : "unknown";
    }

    /** Mutable fixed-window counter; mutations occur only inside {@link ConcurrentHashMap#compute}. */
    private static final class Counter {
        private final long windowId;
        private int count;

        private Counter(long windowId) {
            this.windowId = windowId;
            this.count = 1;
        }
    }
}

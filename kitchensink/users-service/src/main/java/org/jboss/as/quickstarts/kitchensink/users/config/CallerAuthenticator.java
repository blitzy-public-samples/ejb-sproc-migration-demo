package org.jboss.as.quickstarts.kitchensink.users.config;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Base64;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Resolves the {@link CallerIdentity} of an inbound HTTP request from its credential headers.
 *
 * <p>This component centralizes the users-service authentication mechanics so the member access-control
 * interceptor shares one consistent, tested implementation. Two credential schemes are recognized, in
 * priority order:</p>
 *
 * <ol>
 *   <li><b>Service token</b> — header {@value InternalServiceAuthInterceptor#INTERNAL_TOKEN_HEADER}
 *       equal to {@code ${security.internal.token}}. Resolves to {@link CallerIdentity#service()} and
 *       grants admin/cross-member access (this is how a trusted peer service such as orders-service
 *       reads any member's tier, and the only way to list all members).</li>
 *   <li><b>Member access token</b> — headers {@value #AUTH_MEMBER_ID_HEADER} (the member id) and
 *       {@value #AUTH_MEMBER_TOKEN_HEADER} (a signed token). The token is verified as
 *       {@code Base64URL(HMAC-SHA256(key = ${security.internal.token}, msg = "member:" + memberId))}.
 *       A valid pair resolves to {@link CallerIdentity#member(Long)} bound to that id, which may read
 *       only its own member record.</li>
 * </ol>
 *
 * <p>Anything else resolves to {@link CallerIdentity#ANONYMOUS}. The HMAC key is the externalized
 * internal token (no default; {@code SECURITY_INTERNAL_TOKEN} must be supplied at runtime), so member
 * tokens cannot be forged without the server secret and no credential material is hard-coded. A
 * deliberately lightweight signed-token scheme is used in place of the full
 * {@code spring-boot-starter-security} stack to honor the migration's minimal-change discipline,
 * mirroring the rationale of {@link InternalServiceAuthInterceptor}.</p>
 */
@Component
public class CallerAuthenticator {

    /** Header carrying the authenticated member id for the member-token scheme. */
    public static final String AUTH_MEMBER_ID_HEADER = "X-Auth-Member-Id";

    /** Header carrying the signed member access token for the member-token scheme. */
    public static final String AUTH_MEMBER_TOKEN_HEADER = "X-Auth-Member-Token";

    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final String MEMBER_TOKEN_PREFIX = "member:";

    /** Shared secret: both the trusted-service token and the HMAC signing key for member tokens. */
    private final String internalToken;

    public CallerAuthenticator(@Value("${security.internal.token}") String internalToken) {
        this.internalToken = internalToken;
    }

    /**
     * Resolve the identity of {@code request} from its credential headers.
     *
     * @param request the inbound request
     * @return a {@link CallerIdentity}; never {@code null} (defaults to {@link CallerIdentity#ANONYMOUS})
     */
    public CallerIdentity authenticate(HttpServletRequest request) {
        // 1) Trusted peer service / admin: shared internal token.
        String serviceToken = request.getHeader(InternalServiceAuthInterceptor.INTERNAL_TOKEN_HEADER);
        if (constantTimeEquals(serviceToken, internalToken)) {
            return CallerIdentity.service();
        }

        // 2) Authenticated end user: signed member access token bound to a member id.
        String memberIdHeader = request.getHeader(AUTH_MEMBER_ID_HEADER);
        String memberTokenHeader = request.getHeader(AUTH_MEMBER_TOKEN_HEADER);
        if (memberIdHeader != null && memberTokenHeader != null) {
            Long memberId = parseMemberId(memberIdHeader);
            if (memberId != null && verifyMemberToken(memberId, memberTokenHeader)) {
                return CallerIdentity.member(memberId);
            }
        }

        // 3) No (or invalid) credentials.
        return CallerIdentity.ANONYMOUS;
    }

    /**
     * Mint the signed access token for a member. Exposed for client/test use so callers can present a
     * valid {@value #AUTH_MEMBER_TOKEN_HEADER} without duplicating the signing logic.
     *
     * @param memberId the member id to bind the token to
     * @return the Base64URL-encoded HMAC-SHA256 token
     */
    public String mintMemberToken(Long memberId) {
        return sign(MEMBER_TOKEN_PREFIX + memberId);
    }

    /** Verify that {@code providedToken} is the valid signed token for {@code memberId}. */
    private boolean verifyMemberToken(Long memberId, String providedToken) {
        String expected = sign(MEMBER_TOKEN_PREFIX + memberId);
        return constantTimeEquals(providedToken, expected);
    }

    /** Compute {@code Base64URL(HMAC-SHA256(internalToken, message))} without padding. */
    private String sign(String message) {
        if (internalToken == null) {
            // Should never happen at runtime: ${security.internal.token} is required (no default).
            throw new IllegalStateException("security.internal.token is not configured");
        }
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(internalToken.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM));
            byte[] signature = mac.doFinal(message.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(signature);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Failed to compute member access token signature", e);
        }
    }

    private static Long parseMemberId(String raw) {
        try {
            return Long.valueOf(raw.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Constant-time string comparison (via {@link MessageDigest#isEqual}) to avoid leaking secrets
     * through response-timing differences.
     */
    private static boolean constantTimeEquals(String provided, String expected) {
        if (provided == null || expected == null) {
            return false;
        }
        byte[] a = provided.getBytes(StandardCharsets.UTF_8);
        byte[] b = expected.getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(a, b);
    }
}

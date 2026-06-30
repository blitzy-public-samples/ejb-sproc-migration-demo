package org.jboss.as.quickstarts.kitchensink.users.config;

/**
 * Immutable result of authenticating an inbound HTTP request at the users-service web edge.
 *
 * <p>The users-service applies application-specific access control to its member-scoped read
 * endpoints (member lookup and tier read) and restricts the full-member listing to trusted callers.
 * A caller is resolved by {@link CallerAuthenticator} into exactly one of three kinds:</p>
 * <ul>
 *   <li>{@link Kind#SERVICE} — a trusted peer service or back-office/admin caller that presented the
 *       shared service token ({@code X-Internal-Service-Token}). A SERVICE caller is permitted to act
 *       on ANY member's data (admin policy) and is the only caller allowed to list all members.</li>
 *   <li>{@link Kind#MEMBER} — an authenticated end user, bound to a concrete {@link #memberId()} by a
 *       valid signed member access token. A MEMBER caller may only read its OWN member record
 *       (ownership policy).</li>
 *   <li>{@link Kind#ANONYMOUS} — no (or invalid) credentials; rejected with 401 by the interceptors
 *       (except on the intentionally public, rate-limited registration endpoint).</li>
 * </ul>
 *
 * <p>This is a deliberately tiny, dependency-free value type: the migration's binding rules require
 * minimal, isolated change, so access control is implemented as Spring MVC interceptors over a shared
 * token rather than pulling in the full {@code spring-boot-starter-security} stack (consistent with the
 * pre-existing {@code InternalServiceAuthInterceptor} design rationale).</p>
 */
public record CallerIdentity(Kind kind, Long memberId) {

    /** The authenticated caller category. */
    public enum Kind {
        /** Trusted peer service / admin (shared service token). May access any member and list members. */
        SERVICE,
        /** Authenticated end user, bound to {@link CallerIdentity#memberId()}. May access only itself. */
        MEMBER,
        /** No valid credentials presented. */
        ANONYMOUS
    }

    /** Shared singleton for an unauthenticated caller. */
    public static final CallerIdentity ANONYMOUS = new CallerIdentity(Kind.ANONYMOUS, null);

    /** A trusted service/admin caller (no specific member binding). */
    public static CallerIdentity service() {
        return new CallerIdentity(Kind.SERVICE, null);
    }

    /** An authenticated member caller bound to {@code memberId}. */
    public static CallerIdentity member(Long memberId) {
        return new CallerIdentity(Kind.MEMBER, memberId);
    }

    /** {@code true} unless the caller is {@link Kind#ANONYMOUS}. */
    public boolean isAuthenticated() {
        return kind != Kind.ANONYMOUS;
    }

    /** {@code true} for a trusted service/admin caller. */
    public boolean isService() {
        return kind == Kind.SERVICE;
    }

    /**
     * Ownership/admin authorization check for a member-scoped resource: a SERVICE caller may access
     * any member; a MEMBER caller may access only the member id it authenticated as.
     *
     * @param targetMemberId the member id the request is scoped to (the {@code {id}} path variable)
     * @return {@code true} if this caller is permitted to act on {@code targetMemberId}
     */
    public boolean canAccessMember(Long targetMemberId) {
        if (kind == Kind.SERVICE) {
            return true;
        }
        return kind == Kind.MEMBER && memberId != null && memberId.equals(targetMemberId);
    }
}

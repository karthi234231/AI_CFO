package com.fintech.cfo.shared.security;

/**
 * Holder for the current {@link SecurityPrincipal}.
 *
 * <p>Delegates to Spring Security's {@code SecurityContextHolder} rather than
 * maintaining a second thread-local, so there is exactly one source of
 * truth for request identity.
 *
 * <p>ScopedValue is not used here directly because Spring Security's filter
 * infrastructure populates the holder as the canonical request identity
 * before any of our code runs. The identity module's TenantContextFilter
 * bridges from this principal into a ScopedValue-bound tenant scope, so
 * identity and tenant context stay in separate, single-source holders and
 * cannot drift apart.
 */
public final class SecurityContext {

	/**
	 * Private constructor: this is a static access point over Spring Security's
	 * context, not a component and not instantiable.
	 */
	private SecurityContext() {
	}

	/**
	 * @return the caller identity, or {@code null} when the request is anonymous
	 *         or the principal is not one of ours. Null rather than a synthetic
	 *         principal so callers must decide what anonymous means to them.
	 */
	public static SecurityPrincipal currentPrincipal() {
		org.springframework.security.core.Authentication authentication = org.springframework.security.core.context
				.SecurityContextHolder.getContext().getAuthentication();
		// An Authentication that exists but is not authenticated is Spring's
		// anonymous token; it must not be mistaken for a real caller.
		if (authentication == null || !authentication.isAuthenticated()) {
			return null;
		}
		Object principal = authentication.getPrincipal();
		// Only a SecurityPrincipal counts. Anything else (a bare username string,
		// say) means the JWT filter did not run on this path.
		if (principal instanceof SecurityPrincipal securityPrincipal) {
			return securityPrincipal;
		}
		return null;
	}

	/**
	 * @return the caller identity, failing if there is none
	 * @throws com.fintech.cfo.shared.exception.AccessDeniedException when no
	 *         authenticated principal is available
	 */
	public static SecurityPrincipal requirePrincipal() {
		SecurityPrincipal principal = currentPrincipal();
		if (principal == null) {
			throw new com.fintech.cfo.shared.exception.AccessDeniedException("No authenticated principal available");
		}
		return principal;
	}

}
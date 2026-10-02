package com.fintech.cfo.shared.security;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import com.fintech.cfo.shared.domain.OrganizationId;
import com.fintech.cfo.shared.domain.UserId;

/**
 * Immutable authenticated caller identity.
 *
 * <p>Established from a verified JWT (or equivalent credential) by the identity
 * module. It states who the caller is and which organizations they may act for;
 * it is never accepted directly from client-supplied request parameters.
 *
 * @param userId         authenticated user
 * @param email          authenticated email, if present in the token
 * @param organizationId organization/tenant the request is scoped to
 * @param authorities    granted authority names
 * @param tenantVerified whether membership was confirmed server-side
 */
public record SecurityPrincipal(
		UserId userId,
		String email,
		OrganizationId organizationId,
		Set<String> authorities,
		boolean tenantVerified) {

	// Compact constructor: an absent authority set becomes an empty immutable set,
	// so hasAuthority is a plain lookup and never needs a null check at the call
	// site. Set.copyOf also prevents a caller from mutating the set afterwards and
	// changing what a cached principal appears to authorize.
	public SecurityPrincipal {
		Objects.requireNonNull(userId, "userId must not be null");
		authorities = authorities == null ? Set.of() : Set.copyOf(authorities);
	}

	/**
	 * @param authority granted authority name
	 * @return true when the caller holds it
	 */
	public boolean hasAuthority(String authority) {
		return this.authorities.contains(authority);
	}

	/**
	 * @param candidates authority names to test
	 * @return true when any one of them is held; short-circuits on the first hit
	 */
	public boolean hasAnyAuthority(String... candidates) {
		for (String candidate : candidates) {
			if (this.authorities.contains(candidate)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * The single test a tenant-scoped query should make before touching tenant
	 * data: an organization id alone is not enough, it must also have been
	 * verified server-side.
 *
	 * @return true when the request is scoped to a confirmed organization
	 */
	public boolean isTenantResolved() {
		return this.organizationId != null && this.tenantVerified;
	}

	/**
	 * @return the tenant scope, or {@code null} when the request is not scoped
	 * to an organization
	 */
	public UUID organizationUuid() {
		return this.organizationId == null ? null : this.organizationId.value();
	}

}
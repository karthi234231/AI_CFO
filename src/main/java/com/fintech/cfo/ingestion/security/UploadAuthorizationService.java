package com.fintech.cfo.ingestion.security;

import java.util.Objects;

import com.fintech.cfo.shared.domain.OrganizationId;
import com.fintech.cfo.shared.exception.AccessDeniedException;
import com.fintech.cfo.shared.security.SecurityPrincipal;

/**
 * Answers whether the authenticated caller may upload for a given organization.
 *
 * <p>Two checks that must not be collapsed into one: the caller must hold the
 * upload authority, and the caller's tenant must equal the tenant the upload is
 * being filed under. Passing the authority without the tenant match is the
 * classic cross-tenant leak, so both are required and a failure is an
 * {@link AccessDeniedException}, never a silently empty result.
 *
 * <p>The principal is passed in rather than read from a thread-local, so the
 * decision is testable and cannot depend on ambient request state.
 */
public final class UploadAuthorizationService {

	/** Authority required to start an ingestion run. */
	public static final String UPLOAD_AUTHORITY = "ingestion:upload";

	public void authorize(SecurityPrincipal principal, OrganizationId targetOrganization) {
		if (principal == null) {
			throw new AccessDeniedException("No authenticated principal is available for this upload");
		}
		Objects.requireNonNull(targetOrganization, "targetOrganization must not be null");
		if (!principal.isTenantResolved()) {
			throw new AccessDeniedException("The caller has no verified organization scope");
		}
		if (!principal.hasAuthority(UPLOAD_AUTHORITY)) {
			throw new AccessDeniedException("The caller lacks the '" + UPLOAD_AUTHORITY + "' authority");
		}
		if (!principal.organizationId().equals(targetOrganization)) {
			throw new AccessDeniedException("The caller may not upload for a different organization");
		}
	}

	public boolean isAuthorized(SecurityPrincipal principal, OrganizationId targetOrganization) {
		try {
			authorize(principal, targetOrganization);
			return true;
		}
		catch (AccessDeniedException ex) {
			return false;
		}
	}

}
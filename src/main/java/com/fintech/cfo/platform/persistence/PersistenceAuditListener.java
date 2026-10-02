package com.fintech.cfo.platform.persistence;

import java.time.Instant;
import java.util.Optional;

import org.springframework.data.domain.AuditorAware;
import org.springframework.stereotype.Component;

import com.fintech.cfo.shared.security.SecurityContext;
import com.fintech.cfo.shared.security.SecurityPrincipal;

/**
 * Resolves the acting user for JPA {@code @CreatedBy} / {@code @LastModifiedBy}.
 *
 * <p>Separated from {@link JpaConfiguration} because it is a collaborator that
 * may be overridden or tested in isolation.
 *
 * <p>Behavioural note: for scheduled and asynchronous work there is no
 * authenticated principal, so this returns empty. Those records are attributed
 * to the system rather than to a user, which keeps "who ran the nightly
 * calculation" answerable.
 */
@Component
public class PersistenceAuditListener implements AuditorAware<java.util.UUID> {

	/**
	 * @return the acting user's identifier, or empty when there is no authenticated
	 *         principal. Empty rather than a synthetic "system" id, so JPA leaves
	 *         the auditing column null and the record is honestly unattributed.
	 */
	@Override
	public Optional<java.util.UUID> getCurrentAuditor() {
		SecurityPrincipal principal = SecurityContext.currentPrincipal();
		if (principal == null) {
			return Optional.empty();
		}
		return Optional.of(principal.userId().value());
	}

	/**
	 * @return true when the current request is attributable to a real user
	 */
	public boolean hasAuthenticatedActor() {
		return SecurityContext.currentPrincipal() != null;
	}

	/**
	 * @return UTC timestamp for auditing fields, kept here so tests can reason
	 * about one clock source
	 */
	public Instant currentTimestamp() {
		return Instant.now();
	}

}
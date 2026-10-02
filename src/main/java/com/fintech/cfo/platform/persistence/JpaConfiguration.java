package com.fintech.cfo.platform.persistence;

import java.time.Instant;
import java.util.Optional;

import org.springframework.data.domain.AuditorAware;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

import com.fintech.cfo.shared.security.SecurityContext;
import com.fintech.cfo.shared.security.SecurityPrincipal;

/**
 * JPA auditing configuration.
 *
 * <p>Supplies the auditor for {@code @CreatedBy}/{@code @LastModifiedBy}. The
 * auditor resolves to the authenticated caller's identifier and deliberately
 * returns empty for system work (scheduled jobs, async calculation runs) so
 * that automated activity is recorded as "system" rather than being attributed
 * to whichever user happened to trigger the job.
 *
 * <p><b>Why a lambda and not a named class.</b> The resolution is three lines and
 * reads the same as {@code PersistenceAuditListener}, which is the same logic
 * exposed as a component for direct testing. Registering the bean by
 * {@code auditorAwareRef} rather than letting Spring find an {@code AuditorAware}
 * by type is what keeps the choice explicit: two {@code AuditorAware<UUID>}
 * beans exist in this codebase, and an unqualified reference would be ambiguous.
 *
 * <p><b>Why empty is the right answer for system work.</b> A scheduled job runs
 * with no security context, so returning empty leaves the auditing column null.
 * Filling it with a sentinel user id would be worse: the row would appear in a
 * user's activity history, and "who ran the nightly calculation" — a question
 * this design is meant to keep answerable — would become impossible to
 * distinguish from a person clicking a button.
 *
 * <p>Note this class duplicates {@code PersistenceAuditListener} by design: that
 * one is a testable collaborator, this one is the wiring the framework actually
 * invokes. They must be kept behaviourally identical.
 */
@org.springframework.context.annotation.Configuration
@EnableJpaAuditing(auditorAwareRef = "auditorAware")
public class JpaConfiguration {

	/**
	 * Resolves the current auditor from the Spring Security context.
	 *
	 * @return an auditor yielding the caller id, or empty when there is no
	 *         authenticated principal
	 */
	@org.springframework.context.annotation.Bean
	public AuditorAware<java.util.UUID> auditorAware() {
		// Lambda rather than an inner class: the behaviour is a single lookup and
		// needs no per-instance state.
		return () -> {
			SecurityPrincipal principal = SecurityContext.currentPrincipal();
			// Null for anonymous and for scheduler/async threads, which both mean
			// "no human actor" and must not be attributed to one.
			if (principal == null) {
				return Optional.empty();
			}
			return Optional.of(principal.userId().value());
		};
	}

	/**
	 * Timestamp auditor so {@code @CreatedDate}/{@code @LastModifiedDate} use
	 * UTC consistently with {@code DateTimeUtils}.
	 *
	 * @return an auditor always yielding the current UTC instant
	 */
	@org.springframework.context.annotation.Bean
	public AuditorAware<Instant> timestampAuditor() {
		// Unlike the identity auditor this never returns empty: an event has no
		// meaningful timestamp, whereas an event may have no attributable actor.
		return () -> Optional.of(Instant.now());
	}

}
package com.fintech.cfo.platform.audit;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Read-only audit query port.
 *
 * <p>Kept separate from {@link AuditEventJpaRepository} so that consumers depend
 * on a narrow contract rather than the storage mechanism, and so an audit read
 * can be routed to a replica or archive without touching callers.
 */
public interface AuditRepository {

	/**
	 * Records one event. The only write in this contract, and the only way an audit
	 * row is ever created.
	 *
	 * <p>Take an {@link AuditEvent} rather than the entity: callers describe
	 * <i>what happened</i> in domain terms and never handle a persistence object,
	 * so the storage shape can change without touching any producer.
	 *
	 * <p>Implementations must append rather than merge or update. An audit trail
	 * that can silently overwrite a previous entry is not an audit trail.
	 */
	void append(AuditEvent event);

	/**
	 * Every event belonging to one request, oldest first.
	 *
	 * <p>Ascending order is the useful one here: the question being answered is
	 * "what sequence of steps did this request take", which is only legible in
	 * causal order. The {@code limit} bounds the result because a long-running
	 * request can accumulate many events and a diagnostic call must not itself
	 * become expensive.
	 */
	List<AuditEvent> findByCorrelationId(String correlationId, int limit);

	/**
	 * Everything that happened to one record, newest first.
	 *
	 * <p>Descending, because the overwhelmingly common question is "what happened
	 * most recently to this invoice" — usually asked while looking at the record
	 * right now, where the latest change is the relevant one.
	 */
	List<AuditEvent> findEntityHistory(String entityType, UUID entityId, int limit);

	/**
	 * One organization's events within a time window, newest first.
	 *
	 * <p>The tenant is a required parameter rather than something derived from
	 * context, so an audit read is always explicitly scoped and cannot accidentally
	 * return another organization's history.
	 */
	List<AuditEvent> findByOrganizationAndPeriod(UUID organizationId, Instant from, Instant to, int limit);

	/**
	 * How many events of one kind an organization has produced.
	 *
	 * <p>Returns a count rather than a paged list because the question behind it is
	 * always a monitoring or reconciliation one — "has any validation happened
	 * today", "did this tenant record any outcome" — where only the total matters
	 * and materialising rows would be waste.
	 */
	long countByOrganizationAndType(UUID organizationId, AuditEventType eventType);

}
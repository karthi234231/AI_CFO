package com.fintech.cfo.platform.audit;

import java.time.Instant;
import java.util.UUID;

/**
 * Immutable audit fact describing something that happened.
 *
 * <p>This is the transport type handed to the audit subsystem. It carries no
 * persistence concerns; see {@link AuditEventEntity} for the stored form.
 *
 * <p>By design there is no {@code organizationId} mutator and no setter: an
 * audit fact is fixed at creation so it cannot be silently rewritten.
 *
 * <p><b>Why a flat record rather than a per-event payload.</b> Every event shares
 * these fifteen fields so that any row can answer any of the questions a
 * compliance review asks — who, what, to which entity, when, under which request.
 * The variable part of an event goes in {@code details}, a free-text column,
 * rather than into per-event subclasses with their own tables. A reviewer can
 * then write one query over the whole history instead of one per event family.
 *
 * <p>The three id references that are not simply "an entity" —
 * {@code calculationRunId}, {@code opportunityId} and {@code dataSource} — exist
 * so that a financial number can be walked back to the run that produced it and
 * the opportunity it was attributed to, without inferring that relationship from
 * the detail text.
 *
 * @param organizationId   owning tenant, or null for system activity
 * @param actorId          acting user, or null for system activity
 * @param eventType        what happened
 * @param entityType       kind of entity affected
 * @param entityId         identifier of the affected entity
 * @param correlationId    request correlation ID joining this row to its logs
 * @param requestId        originating request ID where distinct from correlation
 * @param dataSource       source system or data feed the event relates to
 * @param calculationRunId financial calculation run involved, if any
 * @param opportunityId    economic opportunity involved, if any
 * @param outcome          short result description
 * @param details          free-text detail
 * @param ipAddress        originating client address
 * @param userAgent        originating client user agent
 * @param occurredAt       when it happened; never updated
 */
public record AuditEvent(
		UUID organizationId,
		UUID actorId,
		AuditEventType eventType,
		String entityType,
		UUID entityId,
		String correlationId,
		String requestId,
		String dataSource,
		UUID calculationRunId,
		UUID opportunityId,
		String outcome,
		String details,
		String ipAddress,
		String userAgent,
		Instant occurredAt) {

	/**
	 * Compact constructor enforcing the three fields an audit row cannot do without.
	 *
	 * <p>{@code eventType} and {@code entityType} together are what makes a row
	 * queryable ("show me everything that happened to invoices"), so a null or
	 * blank value there produces an unanswerable audit question and is rejected
	 * rather than persisted. {@code occurredAt} is required because an event with
	 * no instant cannot be ordered against any other event.
	 *
	 * <p>Deliberately <em>not</em> enforced: {@code organizationId} and
	 * {@code actorId}. System-generated activity has neither a tenant nor a human
	 * actor, and rejecting those events would leave the most interesting rows —
	 * a nightly calculation run, a scheduled report — unrecorded.
	 */
	public AuditEvent {
		if (eventType == null) {
			throw new IllegalArgumentException("eventType must not be null");
		}
		if (entityType == null || entityType.isBlank()) {
			throw new IllegalArgumentException("entityType must not be blank");
		}
		if (occurredAt == null) {
			throw new IllegalArgumentException("occurredAt must not be null");
		}
	}

	/**
	 * Creates an event for an entity inside a tenant, capturing the correlation
	 * and request identifiers of the current request when available.
	 *
	 * <p>The common factory for request-driven activity, where the actor is known
	 * and the correlation ID links this row to the rest of the request's logs.
	 *
	 * @param organizationId owning tenant, or null for system activity
	 * @param actorId        acting user, or null for system activity
	 * @param eventType      what happened
	 * @param entityType     kind of entity affected
	 * @param entityId       identifier of the affected entity
	 * @param correlationId  request correlation ID, or null outside a request
	 * @param outcome        short result description, or null
	 * @param details        free-text detail, or null
	 * @param occurredAt     when it happened
	 * @return the audit fact
	 */
	public static AuditEvent of(UUID organizationId, UUID actorId, AuditEventType eventType, String entityType,
			UUID entityId, String correlationId, String outcome, String details, Instant occurredAt) {
		// requestId, dataSource, ipAddress and userAgent are left null here; they
		// are captured by the web layer, which is the only place they exist.
		return new AuditEvent(organizationId, actorId, eventType, entityType, entityId, correlationId, null, null,
				null, null, outcome, details, null, null, occurredAt);
	}

	/**
	 * Creates a tenant-scoped event with no human actor.
	 *
	 * <p>For automated work — a batch job, a scheduled calculation — where
	 * attributing the event to whichever user happened to trigger the run would be
	 * a false record.
	 *
	 * @param organizationId owning tenant, or null
	 * @param eventType      what happened
	 * @param entityType     kind of entity affected
	 * @param entityId       identifier of the affected entity
	 * @param details        free-text detail
	 * @param occurredAt     when it happened
	 * @return the audit fact
	 */
	public static AuditEvent of(UUID organizationId, AuditEventType eventType, String entityType, UUID entityId,
			String details, Instant occurredAt) {
		return new AuditEvent(organizationId, null, eventType, entityType, entityId, null, null, null, null, null,
				null, details, null, null, occurredAt);
	}

	/**
	 * Returns a copy carrying a different correlation ID.
	 *
	 * <p>A copy method rather than a setter, for the same reason the record has no
	 * mutators: an already-recorded fact is not edited, and an event that needs a
	 * different correlation ID is a different event.
	 *
	 * @param newCorrelationId correlation ID to attach to the copy
	 * @return a new instance; this one is unchanged
	 */
	public AuditEvent withCorrelation(String newCorrelationId) {
		return new AuditEvent(this.organizationId, this.actorId, this.eventType, this.entityType, this.entityId,
				newCorrelationId, this.requestId, this.dataSource, this.calculationRunId, this.opportunityId,
				this.outcome, this.details, this.ipAddress, this.userAgent, this.occurredAt);
	}

}
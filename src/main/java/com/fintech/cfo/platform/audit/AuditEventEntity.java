package com.fintech.cfo.platform.audit;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

/**
 * Append-only persisted audit row.
 *
 * <p>Maps the {@code audit_events} table from {@code V10__create_audit.sql}.
 *
 * <p>The entity is intentionally append-only: {@code occurred_at} is never
 * updated and no column is mutable after insert. Retention and archival are
 * handled outside the transactional model rather than by rewriting history.
 *
 * <p><b>Why the mapping lives on its own class.</b> {@code AuditEvent} is the
 * in-memory record the application passes around; this is the JPA-facing shape.
 * Keeping them apart means the audit trail's persistence cannot dictate the shape
 * of the value object that services use, so a column-length or index change in a
 * migration never ripples outward as an API change. The conversion is a single
 * static factory, {@link #from}, which is the only place the two representations
 * meet.
 *
 * <p><b>Why the ID is supplied rather than generated.</b> {@code from} takes the
 * UUID as a parameter instead of relying on an {@code @GeneratedValue}. The
 * audit service already holds a generator — the same one that assigns
 * calculation-run and opportunity IDs — so reusing it keeps every identifier in a
 * single request traceable to one origin, which is the property that makes an
 * audit trail reconstructable.
 *
 * <p><b>Indexes.</b> The three declared indexes map directly onto the three
 * questions an audit is actually asked. {@code ix_audit_events_org_time} answers
 * "everything this tenant did in a period", the natural compliance query.
 * {@code ix_audit_events_entity} answers "what happened to this one record", which
 * is what a support investigation needs. {@code ix_audit_events_correlation}
 * answers "everything belonging to this one request", which is what a log
 * correlation lookup needs. The leading columns are chosen for selectivity, since
 * a column that only appears late in the key cannot narrow the scan.
 *
 * <p><b>Column length limits are deliberate.</b> {@code outcome} is capped at 1000
 * characters and {@code ip_address} at 45 — the longest possible IPv6 textual
 * form, so a v6 address is never truncated into something that still parses as
 * valid but is a different address. These bounds are the defence against an
 * audit write failing on hostile input; the table is append-only, so a rejected
 * write is an event that is permanently lost.
 */
@Entity
@Table(name = "audit_events", indexes = {
		@Index(name = "ix_audit_events_org_time", columnList = "organization_id,occurred_at"),
		@Index(name = "ix_audit_events_entity", columnList = "entity_type,entity_id,occurred_at"),
		@Index(name = "ix_audit_events_correlation", columnList = "correlation_id")
})
public class AuditEventEntity {

	/** Primary key; assigned by the caller, never by the database. */
	@Id
	private UUID id;

	/** Owning tenant. Indexed first because every query is tenant-scoped. */
	@Column(name = "organization_id")
	private UUID organizationId;

	/**
	 * Who performed the action. Null for actions performed by the system itself,
	 * such as a scheduled calculation, which is a meaningful distinction rather
	 * than missing data.
	 */
	@Column(name = "actor_id")
	private UUID actorId;

	/**
	 * What kind of event this is.
	 *
	 * <p>{@code EnumType.STRING} rather than the default ordinal: ordinals are
	 * positional, so reordering the enum would silently reinterpret every existing
	 * row against a different meaning. Stored names stay stable across deploys
	 * even if the enum is reordered or a constant is added.
	 */
	@Enumerated(EnumType.STRING)
	@Column(name = "event_type", nullable = false, length = 64)
	private AuditEventType eventType;

	/**
	 * Type of the affected record, as a free string rather than a foreign key.
	 *
	 * <p>An audit trail must be able to record events about entities that no longer
	 * exist — a deleted invoice, a retired model — and must not be constrained by
	 * the referential integrity of live tables. Storing the type as text keeps
	 * every event readable indefinitely, at the cost of losing the guarantee that
	 * {@code entity_id} points at a real row.
	 */
	@Column(name = "entity_type", nullable = false, length = 64)
	private String entityType;

	/** Identity of the affected record. Null for events with no single target. */
	@Column(name = "entity_id")
	private UUID entityId;

	/**
	 * Request-scoped correlation ID. This is the field that makes a user's report
	 * of "this looked wrong" traceable: given an ID from a response body, every
	 * audit row and every log line for that request can be retrieved together.
	 */
	@Column(name = "correlation_id", length = 64)
	private String correlationId;

	/** Platform-level request ID, retained separately from the correlation ID. */
	@Column(name = "request_id", length = 64)
	private String requestId;

	/** Which upstream system or export the data came from, when relevant. */
	@Column(name = "data_source", length = 64)
	private String dataSource;

	/**
	 * The calculation run this event belongs to, when applicable. Present so that
	 * a rupee figure quoted in the audit trail can be traced to the exact run and
	 * rule version that produced it, which is what makes the result reproducible.
	 */
	@Column(name = "calculation_run_id")
	private UUID calculationRunId;

	/** The opportunity this event relates to, when applicable. */
	@Column(name = "opportunity_id")
	private UUID opportunityId;

	/**
	 * Short description of what was decided or changed, e.g. "validated" or
	 * "rejected by finance". Capped rather than free-form so a single event cannot
	 * dominate the trail's storage.
	 */
	@Column(name = "outcome", length = 1000)
	private String outcome;

	/**
	 * Longer structured detail.
	 *
	 * <p>{@code TEXT} rather than a bounded {@code varchar} because this is where
	 * the field-level before-and-after values live, and their size depends on the
	 * record. It holds no customer payloads by convention — see
	 * {@code RequestLoggingFilter} for the parallel restriction on logs.
	 */
	@Column(name = "details", columnDefinition = "TEXT")
	private String details;

	/**
	 * Caller's IP address. Stored as {@code VARCHAR(45)} because that is the
	 * maximum length of a fully expanded IPv6 address, so the value is never
	 * truncated into a different but still valid-looking address.
	 */
	@Column(name = "ip_address", length = 45)
	private String ipAddress;

	/**
	 * Caller's user agent. Useful for spotting an unusual client during an
	 * investigation, but is user-controlled and must never be trusted for any
	 * authorisation decision.
	 */
	@Column(name = "user_agent", length = 512)
	private String userAgent;

	/**
	 * When the event happened, in UTC. Never null and never updated: it is the
	 * one column that makes the table's ordering meaningful, and mutating it would
	 * break the append-only guarantee the whole audit design rests on.
	 */
	@Column(name = "occurred_at", nullable = false)
	private Instant occurredAt;

	/**
	 * No-arg constructor required by JPA, which instantiates entities reflectively
	 * when hydrating them and cannot use a factory. It is {@code protected} so
	 * application code is still forced through {@link #from}: nothing outside the
	 * persistence layer should be able to construct an audit row directly, since
	 * every field would then be mutable by default.
	 */
	protected AuditEventEntity() {
		// required by JPA
	}

	/**
	 * Converts the in-memory audit event into its persistable row.
	 *
	 * <p>Field-for-field with no logic, which is the point: an audit row is a
	 * faithful copy of the event, and any transformation applied here would mean
	 * the stored record no longer matches what the application recorded. Each
	 * assignment is a direct field copy from the same-named accessor on the
	 * record, and the supplied {@code id} is the only value not taken from the
	 * event.
	 */
	public static AuditEventEntity from(AuditEvent event, UUID id) {
		AuditEventEntity entity = new AuditEventEntity();
		entity.id = id;
		entity.organizationId = event.organizationId();
		entity.actorId = event.actorId();
		entity.eventType = event.eventType();
		entity.entityType = event.entityType();
		entity.entityId = event.entityId();
		entity.correlationId = event.correlationId();
		entity.requestId = event.requestId();
		entity.dataSource = event.dataSource();
		entity.calculationRunId = event.calculationRunId();
		entity.opportunityId = event.opportunityId();
		entity.outcome = event.outcome();
		entity.details = event.details();
		entity.ipAddress = event.ipAddress();
		entity.userAgent = event.userAgent();
		entity.occurredAt = event.occurredAt();
		return entity;
	}

	/** Primary key. */
	public UUID getId() {
		return this.id;
	}

	/** Owning tenant, for tenant-scoped queries. */
	public UUID getOrganizationId() {
		return this.organizationId;
	}

	/** Actor; null when the system acted on its own. */
	public UUID getActorId() {
		return this.actorId;
	}

	/** What kind of event this is. */
	public AuditEventType getEventType() {
		return this.eventType;
	}

	/** Type of the affected record, as recorded at the time. */
	public String getEntityType() {
		return this.entityType;
	}

	/** Identity of the affected record. */
	public UUID getEntityId() {
		return this.entityId;
	}

	/** Correlation ID linking this event to the request and its logs. */
	public String getCorrelationId() {
		return this.correlationId;
	}

	/** Link to the calculation run, when this event concerns a computed figure. */
	public UUID getCalculationRunId() {
		return this.calculationRunId;
	}

	/** Link to the opportunity, when this event concerns one. */
	public UUID getOpportunityId() {
		return this.opportunityId;
	}

	/** Short description of the decision or change. */
	public String getOutcome() {
		return this.outcome;
	}

	/** Longer structured detail. */
	public String getDetails() {
		return this.details;
	}

	/** When it happened, in UTC. */
	public Instant getOccurredAt() {
		return this.occurredAt;
	}

}
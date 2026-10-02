package com.fintech.cfo.platform.idempotency;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Persisted record of an in-flight or completed idempotent request.
 *
 * <p>Maps the {@code idempotency_records} table from
 * {@code V10__create_audit.sql}.
 *
 * <p>The {@link #version} column provides optimistic locking so two concurrent
 * requests carrying the same key cannot both complete: the loser fails its
 * optimistic-lock check and replays the stored response.
 */
@Entity
@Table(name = "idempotency_records", indexes = {
		@Index(name = "ux_idempotency_key", columnList = "idempotency_key", unique = true),
		@Index(name = "ix_idempotency_expiry", columnList = "expires_at")
})
public class IdempotencyRecord {

	/** Lifecycle of a single idempotency claim. */
	public enum State {

		/** Claimed; the handler is still running. A concurrent replay must wait. */
		IN_PROGRESS,

		/** Completed; the stored response should be replayed to the caller. */
		COMPLETED,

		/** Handler failed; the key may be retried once the record is recycled. */
		FAILED,

		/** Past {@code expiresAt}; safe to discard and reuse the key. */
		EXPIRED

	}

	@Id
	private UUID id;

	/**
	 * The client-supplied key, unique across the table.
	 *
	 * <p>Uniqueness is global rather than per-tenant, enforced by the
	 * {@code ux_idempotency_key} index. That is the conservative choice: a
	 * per-tenant key would allow two organizations to use the same string, and a
	 * bug in tenant scoping would then be invisible rather than caught by a
	 * constraint violation.
	 */
	@Column(name = "idempotency_key", nullable = false, length = 255)
	private String idempotencyKey;

	/** Owning tenant, so a replay can never cross organizations. */
	@Column(name = "organization_id")
	private UUID organizationId;

	/**
	 * SHA-256 of the request payload. A replay presenting the same key but a
	 * different body is a client bug and must be rejected rather than served
	 * the wrong cached response.
	 *
	 * <p>This is the field that makes the mechanism safe rather than merely
	 * convenient. Without it, a client that reuses a key for a genuinely different
	 * request would silently receive the first request's response — the worst
	 * possible failure mode, because the client would report success for work that
	 * was never done.
	 *
	 * <p><b>Why the JDBC type code and {@code columnDefinition} are both set.</b>
	 * The migration is the owner of the schema and declares this column
	 * {@code CHAR(64)}, because a fixed-width hex digest is what the column is.
	 * Plain {@code @Column(length = 64)} on a String implies {@code varchar(64)},
	 * so Hibernate's schema validation - which runs in every environment, because
	 * Flyway owns the schema and drift must fail the boot rather than be silently
	 * repaired - rejects the context with "found [bpchar], but expecting
	 * [varchar(64)]". {@link SqlTypes#CHAR} is what tells the validator to expect
	 * {@code bpchar}; the {@code columnDefinition} keeps the emitted DDL text
	 * agreeing with it. Both state the type the migration actually creates - they
	 * are documentation of the schema, not a second source of truth for it, and
	 * the migration itself is unchanged.
	 */
	@Column(name = "request_fingerprint", nullable = false, columnDefinition = "char(64)")
	@JdbcTypeCode(SqlTypes.CHAR)
	private String requestFingerprint;

	/** Stored HTTP status of the original response, for verbatim replay. */
	@Column(name = "response_status")
	private Integer responseStatus;

	/**
	 * Stored response body.
	 *
	 * <p>{@code TEXT} and nullable because a record that never completed has no
	 * body. Kept as a string rather than a parsed object so a replay is a
	 * byte-for-byte reproduction of the original response, including its exact
	 * number formatting — re-serialising a parsed value could change it.
	 */
	@Column(name = "response_body", columnDefinition = "TEXT")
	private String responseBody;

	/**
	 * Lifecycle state.
	 *
	 * <p>{@code EnumType.STRING} rather than the ordinal default: ordinals are
	 * positional, so inserting a new constant would silently reinterpret every
	 * stored row against a different meaning.
	 */
	@Enumerated(EnumType.STRING)
	@Column(name = "state", nullable = false, length = 32)
	private State state;

	/** When the claim was taken. */
	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	/**
	 * When the claim may be recycled.
	 *
	 * <p>Indexed by {@code ix_idempotency_expiry} because expiry is swept in bulk
	 * on a schedule; without that index every sweep would be a full table scan
	 * against a table that only ever grows.
	 */
	@Column(name = "expires_at", nullable = false)
	private Instant expiresAt;

	/** Last state change, so a stalled claim can be identified operationally. */
	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	/**
	 * Optimistic-lock counter.
	 *
	 * <p>{@code @Version} makes JPA append this to the {@code UPDATE} predicate and
	 * throw {@link jakarta.persistence.OptimisticLockException} when no row matched.
	 * That is what prevents the double-execution race: two concurrent requests
	 * claiming the same key both read version 0, and only the first {@code UPDATE}
	 * succeeds. The loser learns it lost and replays the winner's stored response
	 * instead of running the handler a second time.
	 *
	 * <p>Deliberately a {@code long} and not a timestamp: it must change on every
	 * update, and a timestamp would not if two writes landed in the same instant.
	 */
	@Version
	@Column(name = "version", nullable = false)
	private long version;

	/** JPA requires a no-arg constructor; application code uses the one below. */
	protected IdempotencyRecord() {
		// required by JPA
	}

	/**
	 * Takes a new claim, in {@link State#IN_PROGRESS}.
	 *
	 * <p>{@code updatedAt} is seeded to {@code createdAt} rather than read from a
	 * clock here, so constructing a record has no hidden dependency on time and a
	 * test can construct one with two explicit values.
	 *
	 * <p>Starting in {@code IN_PROGRESS} and not optimistically {@code COMPLETED} is
	 * essential: until the handler has actually finished there is no response to
	 * replay, and a concurrent request must be told to wait rather than served an
	 * empty one.
	 */
	public IdempotencyRecord(UUID id, String idempotencyKey, UUID organizationId, String requestFingerprint,
			Instant createdAt, Instant expiresAt) {
		this.id = id;
		this.idempotencyKey = idempotencyKey;
		this.organizationId = organizationId;
		this.requestFingerprint = requestFingerprint;
		this.createdAt = createdAt;
		this.expiresAt = expiresAt;
		this.updatedAt = createdAt;
		this.state = State.IN_PROGRESS;
	}

	/**
	 * Records the successful response and makes the key replayable.
	 *
	 * <p>Status and body are written together with the state in one call so a record
	 * can never be {@code COMPLETED} without its response, or hold a response while
	 * still marked in progress. The {@code updatedAt} argument is supplied by the
	 * caller rather than read here, keeping the entity free of a clock dependency.
	 */
	public void complete(int responseStatus, String responseBody, Instant updatedAt) {
		this.state = State.COMPLETED;
		this.responseStatus = Integer.valueOf(responseStatus);
		this.responseBody = responseBody;
		this.updatedAt = updatedAt;
	}

	/**
	 * Marks the handler as having failed, releasing the key for retry.
	 *
	 * <p>No response is stored: a failure is not a result. A key in this state may be
	 * claimed again once the record is recycled, so a client that retries after a
	 * transient fault can succeed with the same key.
	 */
	public void fail(Instant updatedAt) {
		this.state = State.FAILED;
		this.updatedAt = updatedAt;
	}

	/**
	 * Marks the record past its lifetime.
	 *
	 * <p>A terminal state rather than a deletion, so the sweep is auditable: an
	 * operator can see that a key was reclaimed and when.
	 */
	public void expire(Instant updatedAt) {
		this.state = State.EXPIRED;
		this.updatedAt = updatedAt;
	}

	/**
	 * Whether the record has reached its expiry at the given instant.
	 *
	 * <p>Negated {@code isBefore} rather than {@code isAfter}, making the boundary
	 * inclusive: a record expiring exactly now is expired. Excluding the boundary
	 * would leave a key usable at the precise instant it should stop being so.
	 */
	public boolean isExpiredAt(Instant instant) {
		return !instant.isBefore(this.expiresAt);
	}

	public UUID getId() {
		return this.id;
	}

	public String getIdempotencyKey() {
		return this.idempotencyKey;
	}

	public UUID getOrganizationId() {
		return this.organizationId;
	}

	public String getRequestFingerprint() {
		return this.requestFingerprint;
	}

	public Integer getResponseStatus() {
		return this.responseStatus;
	}

	public String getResponseBody() {
		return this.responseBody;
	}

	public State getState() {
		return this.state;
	}

	public Instant getCreatedAt() {
		return this.createdAt;
	}

	public Instant getExpiresAt() {
		return this.expiresAt;
	}

	public Instant getUpdatedAt() {
		return this.updatedAt;
	}

}
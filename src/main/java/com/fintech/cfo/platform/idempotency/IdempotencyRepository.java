package com.fintech.cfo.platform.idempotency;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Storage access for idempotency claims.
 *
 * <p>Writes go through {@link IdempotencyService}, which owns the claim/complete/
 * abandon lifecycle and the optimistic-lock retry. This interface exists so that
 * lifecycle can be exercised against a fake, and so the JPA surface stays limited
 * to the three queries below.
 *
 * <p>The one write path worth naming is deletion by expiry, which is the only
 * operation in the whole idempotency mechanism that removes rows. It is a bulk
 * {@code DELETE} returning the count removed, so a scheduled sweep can log how
 * many records it reclaimed — useful evidence that the table is not growing without
 * bound.
 */
public interface IdempotencyRepository extends JpaRepository<IdempotencyRecord, UUID> {

	/**
	 * Looks up an existing claim by key.
	 *
	 * <p>Not scoped by tenant here, even though the record carries one. The unique
	 * index on {@code idempotency_key} is global, so a key identifies at most one
	 * record in the entire table; adding a tenant predicate would imply a
	 * per-tenant uniqueness that the schema does not enforce, and a lookup that
	 * returned empty for another tenant's key would hide a real conflict.
	 * {@code IdempotencyService} compares organizations on the returned record
	 * instead.
	 *
	 * <p>{@link Optional} because absence is the normal case — it means this key has
	 * not been used and the caller should take a fresh claim.
	 */
	Optional<IdempotencyRecord> findByIdempotencyKey(String idempotencyKey);

	/**
	 * Whether a key is already taken, without loading the record.
	 *
	 * <p>Exists as a cheap pre-check for callers that only need to know the key is
	 * in use and never need the stored response. Avoids materialising a row that
	 * would immediately be discarded.
	 */
	boolean existsByIdempotencyKey(String idempotencyKey);

	/**
	 * Deletes every record expiring strictly before {@code cutoff}, returning how
	 * many were removed.
	 *
	 * <p>Used by the expiry sweep to reclaim the table. The derived delete runs as a
	 * single statement rather than a load-then-delete loop, so the table is not
	 * fully materialised and the sweep is bounded by the number of expiring rows
	 * rather than by the table size.
	 *
	 * <p>Deliberately destructive. An expired record is one whose key is reusable,
	 * so retaining it would serve a stale response to a caller who had moved on; and
	 * audit of a transient transport concern is not worth unbounded storage. The
	 * strict {@code Before} leaves records expiring exactly at the cutoff for the
	 * next sweep, which avoids racing a request that is legitimately mid-flight at
	 * the boundary.
	 *
	 * <p>Note the contrast with {@link AuditEventEntity}, which is never deleted:
	 * an audit record is permanent evidence, whereas an idempotency record is
	 * short-lived transport state.
	 */
	long deleteByExpiresAtBefore(Instant cutoff);

}
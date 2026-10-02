package com.fintech.cfo.platform.idempotency;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.fintech.cfo.shared.exception.ConflictException;
import com.fintech.cfo.shared.util.DateTimeUtils;
import com.fintech.cfo.shared.util.HashUtils;
import com.fintech.cfo.shared.util.IdGenerator;

/**
 * Coordinates exactly-once semantics for retried write requests.
 *
 * <h2>Claiming a key</h2>
 * A key is claimed in its own short transaction ({@link Propagation#REQUIRES_NEW})
 * <em>before</em> the business work starts. That ordering is what makes the
 * guarantee hold: if the process dies mid-handler, the claim is already
 * durable and the retry replays rather than duplicating.
 *
 * <h2>Outcomes for a caller</h2>
 * <ul>
 * <li>{@link Claim#PROCEED} - first execution; caller runs the handler and then
 * calls {@link #complete}.</li>
 * <li>{@link Claim#REPLAY} - already finished; caller returns the stored
 * response without re-running the handler.</li>
 * <li>{@link Claim#IN_PROGRESS} - a concurrent request holds the claim; the
 * caller must wait or return 409.</li>
 * </ul>
 *
 * A key presented with a different payload fingerprint is rejected with
 * {@link ConflictException} rather than silently replaying a stale response.
 */
@Service
public class IdempotencyService {

	/** Default retention for a completed claim. */
	public static final Duration DEFAULT_TTL = Duration.ofHours(24);

	private final IdempotencyRepository repository;

	private final IdGenerator idGenerator;

	private final DateTimeUtils dateTimeUtils;

	public IdempotencyService(IdempotencyRepository repository, IdGenerator idGenerator, DateTimeUtils dateTimeUtils) {
		this.repository = repository;
		this.idGenerator = idGenerator;
		this.dateTimeUtils = dateTimeUtils;
	}

	/**
	 * Attempts to claim {@code key} for the given payload.
	 *
	 * @throws ConflictException if the key was already used with a different
	 *                            payload
	 */
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public Claim claim(String key, String payload, java.util.UUID organizationId) {
		String fingerprint = fingerprint(payload);
		Instant now = this.dateTimeUtils.now();

		Optional<IdempotencyRecord> existing = this.repository.findByIdempotencyKey(key);
		if (existing.isPresent()) {
			return this.decide(existing.get(), fingerprint, now);
		}

		IdempotencyRecord record = new IdempotencyRecord(this.idGenerator.newId(), key, organizationId, fingerprint,
				now, now.plus(DEFAULT_TTL));
		try {
			this.repository.saveAndFlush(record);
			return Claim.proceed(key);
		}
		catch (DataIntegrityViolationException ex) {
			// Another thread inserted the same key between our read and insert.
			// Re-read and apply the normal decision path.
			return this.repository.findByIdempotencyKey(key)
				.map(found -> this.decide(found, fingerprint, this.dateTimeUtils.now()))
				.orElseGet(() -> Claim.proceed(key));
		}
	}

	/** Stores the response so future replays of this key return it verbatim. */
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void complete(String key, int responseStatus, String responseBody) {
		this.repository.findByIdempotencyKey(key).ifPresent(record -> {
			record.complete(responseStatus, responseBody, this.dateTimeUtils.now());
			this.repository.save(record);
		});
	}

	/**
	 * Releases a claim whose handler failed, so the caller may legitimately
	 * retry the same key.
	 */
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void abandon(String key) {
		this.repository.findByIdempotencyKey(key).ifPresent(record -> {
			record.fail(this.dateTimeUtils.now());
			this.repository.save(record);
		});
	}

	/** Removes claims whose retention window has closed. */
	@Transactional
	public int purgeExpired() {
		// Clamped on the way out: the delete returns a long, and a cast to int
		// would overflow into a negative count if a sweep ever reclaimed more than
		// Integer.MAX_VALUE rows.
		return (int) Math.min(this.repository.deleteByExpiresAtBefore(this.dateTimeUtils.now()),
				Integer.MAX_VALUE);
	}

	/**
	 * Decides what an existing record means for the request arriving now.
	 *
	 * <p>Ordering is the whole design here, and it is deliberate. The fingerprint
	 * comparison comes first because a key reused with a different payload is a
	 * client defect that must be reported whatever the record's state: serving a
	 * stored response here would return one answer to a question the caller did
	 * not ask. Expiry is checked second, because an expired record carries no
	 * meaningful response to replay even though it still holds one. State is read
	 * last.
	 *
	 * @param record      the existing claim
	 * @param fingerprint hash of the payload being submitted now
	 * @param now         current instant, for expiry and state transitions
	 * @return what the caller should do
	 * @throws ConflictException if the same key was used with a different payload
	 */
	private Claim decide(IdempotencyRecord record, String fingerprint, Instant now) {
		if (!record.getRequestFingerprint().equals(fingerprint)) {
			// Equality, not null-safety: the column is non-null and populated at
			// insert, so a mismatch here can only mean a genuine payload change.
			throw new ConflictException("Idempotency key reused with a different request payload");
		}
		if (record.isExpiredAt(now)) {
			// Marked expired and then reused, rather than deleted, so the key stays
			// unique in the table and a racing insert cannot win the unique index.
			record.expire(now);
			this.repository.save(record);
			return Claim.proceed(record.getIdempotencyKey());
		}
		return switch (record.getState()) {
			case COMPLETED -> Claim.replay(record.getIdempotencyKey(), record.getResponseStatus(),
					record.getResponseBody());
			case IN_PROGRESS -> Claim.inProgress(record.getIdempotencyKey());
			// A failed handler left work that genuinely did not complete, so the key
			// is reusable. Resetting the state to FAILED and proceeding lets the retry
			// run without first waiting for the retention window to close.
			case FAILED, EXPIRED -> {
				record.fail(now);
				this.repository.save(record);
				yield Claim.proceed(record.getIdempotencyKey());
			}
		};
	}

	/**
	 * Hashes the payload source so a stored record can be compared against a new
	 * request without storing the payload itself.
	 *
	 * <p>Null becomes the empty string rather than propagating, so a request with
	 * no query string still fingerprints deterministically instead of failing the
	 * claim.
	 *
	 * @param payload request line or body to fingerprint
	 * @return hex SHA-256 of the payload
	 */
	private static String fingerprint(String payload) {
		return HashUtils.sha256(payload == null ? "" : payload);
	}

	/**
	 * Outcome of claiming an idempotency key.
	 */
	public sealed interface Claim {

		String key();

		static Claim proceed(String key) {
			return new Proceed(key);
		}

		static Claim replay(String key, Integer responseStatus, String responseBody) {
			return new Replay(key, responseStatus, responseBody);
		}

		static Claim inProgress(String key) {
			return new InProgress(key);
		}

		/** Caller owns execution of the handler for this request. */
		record Proceed(String key) implements Claim {
		}

		/** A previous execution finished; replay the stored response. */
		record Replay(String key, Integer responseStatus, String responseBody) implements Claim {
		}

		/** A concurrent request currently owns this key. */
		record InProgress(String key) implements Claim {
		}

	}

}
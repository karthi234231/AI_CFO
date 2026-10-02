package com.fintech.cfo.evidence.model;

import java.time.Instant;

import org.jspecify.annotations.Nullable;

import com.fintech.cfo.shared.exception.ValidationException;

/**
 * Pointer to an object stored outside the database that backs one {@link Evidence} row.
 *
 * <p>Evidence holds the pointer, never the bytes. The bytes live in object storage behind
 * {@code storage_key}, and {@link ContentHash} is the digest of what was written there, so a
 * reviewer can fetch the object and prove it is the same one the evidence was registered
 * against. Storing the document inline would satisfy neither: an inline copy cannot be
 * independently re-read against the source system, and cannot be independently deleted when
 * retention expires.
 *
 * <p>Both components mirror columns V7 already declares on {@code evidences}
 * ({@code storage_key VARCHAR(1024)}, {@code content_hash CHAR(64)}), so persisting this
 * later is a column write rather than a schema change.
 *
 * @param storageKey opaque object-storage key; treated as a secret-free identifier, so it is
 *                   safe to log, but never the object contents
 * @param contentHash digest of the stored bytes as written, not of the key
 * @param storedAt    when the object was written, supplied by the caller so a re-verification
 *                    can distinguish the original upload from a later restore
 */
public record EvidenceArtifact(String storageKey, ContentHash contentHash, Instant storedAt) {

	/** Width of {@code evidences.storage_key} in V7. */
	public static final int MAX_STORAGE_KEY_LENGTH = 1024;

	/**
	 * Compact constructor: the guarantee that an artifact names something and can be
	 * proven.
	 *
	 * <p>All three checks are refusals rather than defaults. A key with nothing behind
	 * it, or bytes with no digest, would both read on a report as "stored evidence"
	 * and neither could ever be checked.
	 */
	public EvidenceArtifact {
		if (storageKey == null || storageKey.isBlank()) {
			throw new ValidationException("storageKey must not be blank; evidence with no key is not stored evidence");
		}
		String trimmed = storageKey.trim();
		if (trimmed.length() > MAX_STORAGE_KEY_LENGTH) {
			throw new ValidationException(
					"storageKey exceeds the V7 column width of " + MAX_STORAGE_KEY_LENGTH);
		}
		if (contentHash == null) {
			throw new ValidationException("contentHash must not be null; a stored object that cannot be re-hashed "
					+ "cannot be proven to be the same object later");
		}
		// storedAt is the caller's instant, never Instant.now(): a snapshot of when an
		// object was written is part of a provenance claim, and a re-verification months
		// later has to be able to say whether it saw the original upload or a restore
		// from backup.
		if (storedAt == null) {
			throw new ValidationException("storedAt must be supplied; artifacts never date themselves");
		}
		// Trimmed last, so the width check measures what would actually be persisted:
		// a key that only fits because of padding would pass here and be truncated by
		// the column, leaving the stored pointer different from the in-memory one.
		storageKey = trimmed;
	}

	/**
	 * Whether this artifact points at the same stored object as {@code other}.
	 *
	 * <p>Deliberately compares the key alone: re-uploading identical bytes to the same key is
	 * the same evidence, and is not the "already points elsewhere" conflict that
	 * {@link Evidence#withArtifact} refuses.
	 *
	 * <p>Note what it does not compare: {@link #contentHash} and {@link #storedAt}.
	 * A differing digest under the same key is a tampering signal to be reported, not
	 * a difference of identity, and folding it into this method would turn a detection
	 * into a silent refusal.
	 *
	 * @param other candidate artifact, possibly null
	 * @return true when both artifacts name the same storage key
	 */
	public boolean pointsAtSameObject(@Nullable EvidenceArtifact other) {
		return other != null && this.storageKey.equals(other.storageKey);
	}

}

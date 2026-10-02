package com.fintech.cfo.evidence.model;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

import com.fintech.cfo.shared.exception.ValidationException;
import com.fintech.cfo.shared.util.HashUtils;

/**
 * A SHA-256 digest of exactly 64 lower-case hexadecimal characters.
 *
 * <p>A value type rather than a bare {@code String} because the V7 columns that hold
 * it - {@code evidences.content_hash}, {@code evidence_snapshots.content_hash} - are
 * {@code CHAR(64) NOT NULL}, and {@code CHAR} silently pads. A 60-character digest read
 * back from such a column would look like a valid hash and deduplicate nothing. Wrapping
 * the value once, at the boundary, means every later comparison is between two digests
 * that are known to be the same shape.
 *
 * <p><b>Why SHA-256.</b> The digest answers one question - "is this the same content
 * I saw before?" - for file de-duplication at ingestion, for snapshot identity, and for
 * proving a stored artifact was not altered. All three need a fast, collision-resistant
 * digest that is reproducible on every machine forever, which is the opposite of what
 * password hashing optimises for. It is deliberately unsalted: a checksum that cannot be
 * recomputed by anyone holding the file is not a checksum. The algorithm is chosen once,
 * in {@link HashUtils}, so no caller picks a digest for itself and two components can
 * never disagree about what "the same content" means.
 *
 * <p>It covers content, never the key or the name. Renaming an artifact leaves the hash
 * alone, and changing one byte anywhere in the stored object changes it, which is what
 * makes the value usable as an integrity claim rather than as an identifier.
 *
 * <p>Final class with a validated constructor rather than a record: the only state is
 * the digest, and all of the behaviour is deriving or checking one, which a record would
 * express more verbosely without expressing more safely.
 */
public final class ContentHash {

	/** {@code CHAR(64)}: the length of a hex-encoded SHA-256 digest. */
	public static final int LENGTH = 64;

	private final String value;

	/**
	 * Private so that no instance can exist outside the three factories above.
	 *
	 * <p>There is intentionally no public {@code (String)} constructor: the difference
	 * between the validating {@link #of(String)} and the two hashing factories is
	 * exactly the difference between a caller asserting a digest and this module
	 * computing one, and collapsing them would let an unverified value claim the same
	 * authority as a computed one.
	 */
	private ContentHash(String value) {
		this.value = value;
	}

	/**
	 * Wraps an already-computed digest.
	 *
	 * <p>The pre-digest entry point is for values read back out of a database column
	 * or handed over by a caller that already hashed. Anything this module hashes
	 * itself goes through {@link #ofText(String)} or {@link #ofBytes(byte[])}, so no
	 * caller is left to decide what to hash.
	 *
	 * @param hexDigest 64 hexadecimal characters, in any case, possibly padded by
	 *                  {@code CHAR} semantics with surrounding whitespace
	 * @return the normalised digest
	 * @throws ValidationException unless the value is exactly 64 hexadecimal characters,
	 *         case-insensitively
	 */
	public static ContentHash of(String hexDigest) {
		if (hexDigest == null || hexDigest.isBlank()) {
			throw new ValidationException("content hash must not be blank");
		}
		// Locale.ROOT, never a default-locale lower-case: a digest is compared by
		// value against a replay taken months later on a differently configured
		// server, so a locale that maps 'I' differently would break deduplication
		// intermittently, in production only. Trim first, because CHAR(64) pads
		// rather than truncates and the padding must not reach the comparison.
		String normalized = hexDigest.trim().toLowerCase(Locale.ROOT);
		if (normalized.length() != LENGTH) {
			throw new ValidationException(
					"content hash must be " + LENGTH + " hex characters (SHA-256), got " + normalized.length());
		}
		// Length alone is not enough: a 64-character non-digest would match nothing
		// and deduplicate everything. Checking the alphabet here is what makes every
		// later comparison a comparison between two digests this module produced or
		// accepted, so a malformed row is refused at the boundary instead of quietly
		// never equalling anything.
		for (int index = 0; index < LENGTH; index++) {
			char character = normalized.charAt(index);
			boolean hex = (character >= '0' && character <= '9') || (character >= 'a' && character <= 'f');
			if (!hex) {
				throw new ValidationException("content hash must be hexadecimal: " + hexDigest);
			}
		}
		return new ContentHash(normalized);
	}

	/**
	 * Computes the digest of UTF-8 encoded text.
	 *
	 * <p>The charset is pinned here rather than left to a caller or to the platform
	 * default, for the same reason as the normalisation above: the same snapshot text
	 * must produce the same digest on every machine that ever re-verifies it.
	 *
	 * @param content text to digest; typically a canonical rendering, not raw input
	 * @return the digest of its UTF-8 encoding
	 * @throws ValidationException if the content is null
	 */
	public static ContentHash ofText(String content) {
		if (content == null) {
			throw new ValidationException("content to hash must not be null");
		}
		// The private constructor directly, not of(...): the output of HashUtils is 64
		// lower-case hex characters by construction, so re-scanning the alphabet on
		// every snapshot would cost a full pass for no additional guarantee.
		return new ContentHash(HashUtils.sha256(content.getBytes(StandardCharsets.UTF_8)));
	}

	/**
	 * Computes the digest of raw bytes.
	 *
	 * <p>The path for an uploaded document: the bytes as written, never a
	 * re-encoding of them. A digest taken over a decoded-then-re-encoded copy would
	 * differ from the object a reviewer fetches, which is the one failure that makes
	 * an integrity check worse than none - it would report a mismatch on a file that
	 * was never altered.
	 *
	 * @param content exact bytes stored behind the artifact's storage key
	 * @return the digest of those bytes
	 * @throws ValidationException if the content is null
	 */
	public static ContentHash ofBytes(byte[] content) {
		if (content == null) {
			throw new ValidationException("content to hash must not be null");
		}
		return new ContentHash(HashUtils.sha256(content));
	}

	/**
	 * The digest as stored: exactly 64 characters, so it fits {@code CHAR(64)} with no
	 * padding and cannot be read back as a shorter value.
	 *
	 * @return the normalised lower-case hexadecimal digest
	 */
	public String value() {
		return this.value;
	}

	/**
	 * Equality on the normalised digest alone.
	 *
	 * <p>Sound only because the constructor guarantees the shape: two instances are
	 * equal exactly when they carry the same digest. That is the guarantee behind
	 * hash-based deduplication of evidence rows and snapshots.
	 *
	 * @param other candidate value
	 * @return true when the other value is a digest carrying the same characters
	 */
	@Override
	public boolean equals(Object other) {
		return other instanceof ContentHash that && this.value.equals(that.value);
	}

	/**
	 * @return a hash derived from the digest string, consistent with {@link #equals}
	 */
	@Override
	public int hashCode() {
		return this.value.hashCode();
	}

	/**
	 * @return the bare digest, so a log line or a {@code toString} chain cannot
	 *         accidentally expose the content the digest was taken from
	 */
	@Override
	public String toString() {
		return this.value;
	}

}

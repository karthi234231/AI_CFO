package com.fintech.cfo.ingestion.model;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Objects;

import com.fintech.cfo.shared.util.HashUtils;

/**
 * Content fingerprint of an uploaded file: algorithm plus hex digest.
 *
 * <p>Exists because rule 3 requires an input checksum for anything that later
 * claims to be reproducible, and because V3 makes
 * {@code (organization_id, checksum_sha256)} a unique key — the same bytes
 * uploaded twice is the same source file, not two.
 *
 * <p>SHA-256 only, for integrity and de-duplication. This is not password
 * hashing.
 */
public record FileChecksum(String algorithm, String hexDigest) {

	public static final String SHA256 = "SHA-256";

	private static final int HEX_LENGTH = 64;

	public FileChecksum {
		Objects.requireNonNull(algorithm, "algorithm must not be null");
		Objects.requireNonNull(hexDigest, "hexDigest must not be null");
		if (!SHA256.equalsIgnoreCase(algorithm)) {
			throw new IllegalArgumentException("only " + SHA256 + " is permitted for file integrity, was " + algorithm);
		}
		if (hexDigest.length() != HEX_LENGTH) {
			throw new IllegalArgumentException("expected a " + HEX_LENGTH + " character hex digest, got "
					+ hexDigest.length());
		}
		algorithm = SHA256;
		hexDigest = hexDigest.toLowerCase(Locale.ROOT);
	}

	public static FileChecksum sha256(byte[] content) {
		Objects.requireNonNull(content, "content must not be null");
		return new FileChecksum(SHA256, HashUtils.sha256(content));
	}

	public static FileChecksum sha256(String content) {
		Objects.requireNonNull(content, "content must not be null");
		return sha256(content.getBytes(StandardCharsets.UTF_8));
	}

	/**
	 * Computes the checksum without closing the caller's stream. A stream that
	 * cannot be read to the end returns {@code null} rather than a digest of the
	 * bytes seen so far, because a partial hash would claim integrity that was
	 * never verified.
	 */
	public static FileChecksum sha256Of(InputStream content) {
		Objects.requireNonNull(content, "content must not be null");
		return new FileChecksum(SHA256, HashUtils.sha256(content));
	}

	public boolean matches(FileChecksum other) {
		return other != null && this.hexDigest.equals(other.hexDigest);
	}

	/**
	 * @return the value for {@code source_files.checksum_sha256} ({@code CHAR(64)})
	 */
	public String sha256() {
		return this.hexDigest;
	}

	@Override
	public String toString() {
		return this.algorithm + ":" + this.hexDigest;
	}

}
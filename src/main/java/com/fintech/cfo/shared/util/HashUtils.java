package com.fintech.cfo.shared.util;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/**
 * Cryptographic hashing helpers used for file checksums and content fingerprints
 * during ingestion.
 *
 * <p>SHA-256 is used for integrity/deduplication only; it is not a password hashing
 * algorithm.
 *
 * <p><b>Why SHA-256 for this purpose.</b> These hashes answer two questions:
 * "is this byte-for-byte the same file I saw before?" (ingestion deduplication)
 * and "is this replay the same request I already answered?" (idempotency
 * fingerprinting). Both need a fast, collision-resistant digest, not a
 * password-hard one. Password hashing deliberately trades speed for cost, which is
 * exactly wrong here — and it matters to say so, because reaching for the same
 * utility to store a credential would be a serious mistake. Nothing in this class
 * is salted, because a checksum must be reproducible by design: the same file has
 * to produce the same value on every machine, forever.
 *
 * <p>Not to be confused with a checksum of the <i>name</i>. These digests cover
 * content, which is what makes them useful: renaming a file does not change its
 * hash, and changing one byte anywhere in it does.
 *
 * <p>Utility class with a private constructor and all-static methods, so it cannot
 * be instantiated or subclassed. Every method is stateless and thread-safe:
 * {@link MessageDigest} instances are created per call rather than shared in a
 * field, because a digest object holds mutable internal state and a shared one
 * would corrupt results under concurrent use.
 */
public final class HashUtils {

	/** Buffer size for the streaming digest; large enough to keep syscall overhead negligible on 25 MB uploads. */
	private static final int BUFFER_SIZE = 8192;

	/** Hex encoder, stateless and thread-safe, hoisted so the streaming path does not allocate one per call. */
	private static final HexFormat HEX = HexFormat.of();

	/** Utility class: not instantiable. */
	private HashUtils() {
	}

	/**
	 * Digest of an in-memory payload, as lower-case hex.
	 *
	 * <p>The two-argument form of {@code digest} is used rather than the streaming
	 * one because the content is already resident, so there is nothing to read in
	 * chunks and the simpler single-shot call is exact.
	 */
	public static String sha256(byte[] content) {
		return HEX.formatHex(digest("SHA-256", content));
	}

	/**
	 * Digest of text, encoded as UTF-8 first.
	 *
	 * <p>The charset is stated explicitly rather than left to the platform default.
	 * A default-dependent encoding would make the same string hash differently on
	 * two machines, which would defeat deduplication entirely — and would do it
	 * intermittently, in production, on a different server.
	 */
	public static String sha256(String content) {
		return sha256(content.getBytes(StandardCharsets.UTF_8));
	}

	/**
	 * Streaming SHA-256 for content too large to hold in memory.
	 *
	 * <p>Does not close the supplied stream. A stream that cannot be read to the end throws
	 * rather than returning {@code null}: a partial read is an exceptional condition, and
	 * returning {@code null} pushed that decision onto every caller, two of which passed the
	 * result straight into a non-nullable integrity field. Callers that genuinely accept an
	 * unverifiable document should catch {@link UncheckedIOException} and decide explicitly.
	 *
	 * <p><b>Why the loop reads in fixed chunks.</b> The upload can be tens of
	 * megabytes, so it is never held whole; {@code update} is fed each chunk as it
	 * arrives and only the 32-byte digest is retained. The buffer is a local
	 * variable rather than a field so that concurrent calls cannot share it, and so
	 * a 8 KB allocation is reclaimed as soon as the call returns.
	 *
	 * <p><b>Why the two failure modes are separated.</b> A missing algorithm is an
	 * {@link IllegalStateException}: SHA-256 is guaranteed by the Java platform, so
	 * this can only mean a broken or tampered JRE, and it is not the caller's fault
	 * nor recoverable by retrying. An {@link IOException} from the stream is
	 * different in kind — the source failed partway — so it surfaces as
	 * {@link UncheckedIOException} to let the caller distinguish "the platform is
	 * broken" from "this file could not be read to the end", which are different
	 * operational responses.
	 */
	public static String sha256(InputStream content) {
		Objects.requireNonNull(content, "content must not be null");
		try {
			// A fresh digest per call: MessageDigest is stateful and not thread-safe.
			MessageDigest md = MessageDigest.getInstance("SHA-256");
			byte[] buffer = new byte[BUFFER_SIZE];
			int read;
			// Read to end-of-stream; -1 is the only terminator, since read may
			// legally return fewer bytes than requested without meaning EOF.
			while ((read = content.read(buffer)) != -1) {
				// Feed exactly the bytes just read, never the whole buffer: the tail
				// of the buffer still holds data from the previous chunk.
				md.update(buffer, 0, read);
			}
			// digest() finalises and resets; the hex form is lower-case and fixed-width.
			return HEX.formatHex(md.digest());
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException("Digest algorithm unavailable: SHA-256", ex);
		}
		catch (IOException ex) {
			throw new UncheckedIOException("SHA-256 stream ended before the content was fully read", ex);
		}
	}

	/**
	 * Single-shot digest helper shared by the byte-array entry points.
	 *
	 * <p>Wraps the checked {@link NoSuchAlgorithmException} that
	 * {@code MessageDigest.getInstance} declares. As above, the algorithm is
	 * platform-guaranteed, so the exception is genuinely exceptional and is
	 * translated to an unchecked one rather than forcing every caller to catch a
	 * checked exception that can never occur.
	 */
	private static byte[] digest(String algorithm, byte[] content) {
		try {
			return MessageDigest.getInstance(algorithm).digest(content);
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException("Digest algorithm unavailable: " + algorithm, ex);
		}
	}

}

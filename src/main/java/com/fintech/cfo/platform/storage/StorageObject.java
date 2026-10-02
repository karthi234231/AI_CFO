package com.fintech.cfo.platform.storage;

import java.time.Instant;

/**
 * Metadata for a stored blob. Carries no content: payloads are streamed by the
 * port implementation so large evidence documents never pass through the heap
 * as a single byte array.
 *
 * <p><b>Why a record with no content field.</b> This type travels back from the
 * store on the request path, and a variant carrying the bytes would put every
 * uploaded document on the heap at least twice — once in the upload buffer and
 * once here. Keeping content out makes the object a cheap handle: it can be
 * stored alongside an evidence row, cached, and logged without pulling in a
 * payload.
 *
 * <p>{@code sizeHuman} exists for display and must never be parsed. It is
 * produced by truncating division, so "1 MB" can mean 1 MB or 1.9 MB; the
 * authoritative size is {@code contentLength}.
 *
 * @param storageKey    opaque, stable object key, tenant-prefixed
 * @param contentLength size in bytes
 * @param contentType   MIME type
 * @param checksum      SHA-256 of the content, used for integrity and dedup
 * @param sizeHuman     operator-facing size, never used for logic
 * @param createdAt     upload time
 * @throws IllegalArgumentException if the key is blank or the length is negative
 */
public record StorageObject(String storageKey, long contentLength, String contentType, String checksum,
		String sizeHuman, Instant createdAt) {

	// Compact constructor. A blank key would make the object unaddressable and a
	// negative length would make integrity checks compare against nonsense; both
	// are rejected here rather than surfacing later as a failed retrieve.
	public StorageObject {
		if (storageKey == null || storageKey.isBlank()) {
			throw new IllegalArgumentException("storageKey must not be blank");
		}
		if (contentLength < 0) {
			throw new IllegalArgumentException("contentLength must not be negative");
		}
	}

}
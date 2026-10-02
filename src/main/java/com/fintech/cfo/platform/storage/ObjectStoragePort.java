package com.fintech.cfo.platform.storage;

import java.io.InputStream;
import java.nio.file.Path;

/**
 * Object storage abstraction.
 *
 * <p>Exists so evidence and source documents can be kept in S3-compatible or
 * local storage without the financial modules depending on either. The
 * hexagonal boundary is deliberate: financial logic must never be coupled to a
 * storage vendor SDK.
 */
public interface ObjectStoragePort {

	/**
	 * Stores a stream and returns its metadata. Implementations must not close
	 * the supplied stream.
	 *
	 * <p>Streaming rather than accepting a byte array because the largest callers
	 * are document uploads: an evidence PDF held in memory would be a
	 * multi-megabyte allocation per concurrent request on the ingestion path.
	 *
	 * @param key            object key, already tenant-prefixed by the caller
	 * @param content        payload stream; ownership stays with the caller
	 * @param contentType    MIME type to record as metadata
	 * @param declaredLength expected length, or {@code -1} when unknown
	 * @return metadata including the key actually written and a checksum
	 */
	StorageObject store(String key, InputStream content, String contentType, long declaredLength);

	/**
	 * Returns the content as a stream; the caller owns closing it.
	 *
	 * <p>Ownership is the caller's because the caller may need to read only part
	 * of the object; a port that closed the stream on return would make selective
	 * reads impossible.
	 *
	 * @param key object key
	 * @return a stream over the content
	 */
	InputStream retrieve(String key);

	/**
	 * Whether an object exists under this key.
	 *
	 * <p>Implemented by attempting a retrieve rather than by a dedicated
	 * {@code exists} call, because a remote store's existence check and a get cost
	 * about the same. The retrieved stream is not closed: an implementation that
	 * opened one is responsible for it, and closing a foreign stream here could
	 * truncate content the caller never sees.
	 *
	 * <p>Any runtime failure is reported as "does not exist". A store that is
	 * unreachable is not evidence that an object is absent, but callers use this
	 * to decide whether to skip work, and treating an outage as a definitive
	 * "absent" would cause them to skip processing that should have happened. The
	 * conservative direction is to let the later retrieve surface the real error.
	 *
	 * @param key object key
	 * @return true when the object is retrievable
	 */
	default boolean exists(String key) {
		try {
			return retrieve(key) != null;
		}
		catch (RuntimeException ex) {
			return false;
		}
	}

	/**
	 * Removes an object.
	 *
	 * @param key object key
	 */
	void delete(String key);

	/**
	 * Filesystem path for an object, for the few callers that genuinely need
	 * random access such as PDF parsing.
	 *
	 * <p>A default that throws rather than a required method: a remote
	 * implementation cannot honour it, and making it abstract would force every
	 * remote port to declare a method it must always refuse. Callers that use it
	 * are explicitly local-only.
	 *
	 * @param key object key
	 * @return the path on the local filesystem
	 * @throws UnsupportedOperationException always, on a non-local store
	 */
	default Path localPath(String key) {
		throw new UnsupportedOperationException("this store has no local filesystem representation");
	}

}
package com.fintech.cfo.ingestion.parser;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Objects;

import com.fintech.cfo.ingestion.enums.FileType;
import com.fintech.cfo.ingestion.model.IngestionLimits;

/**
 * One parse request: the bytes, who they came from, what they were declared to
 * be, and the fixed point in time they are being interpreted at.
 *
 * <p>{@code asOfDate} is required, never defaulted. Parsing and validation must
 * not read the system clock (rule 3): a run re-executed in a year has to reach
 * the same conclusion, which is impossible if "is this date in the future?"
 * silently changes answer between runs. The caller injects it.
 *
 * <p>Stream ownership: the parser consumes this stream to the end and closes it.
 * There is exactly one reader per upload, and making the contract explicit is
 * cheaper than every caller guessing.
 */
public final class ParseRequest {

	private final InputStream content;
	private final String sourceFileId;
	private final String fileName;
	private final String declaredContentType;
	private final LocalDate asOfDate;
	private final IngestionLimits limits;

	private ParseRequest(InputStream content, String sourceFileId, String fileName, String declaredContentType,
			LocalDate asOfDate, IngestionLimits limits) {
		this.content = Objects.requireNonNull(content, "content must not be null");
		this.sourceFileId = requireText(sourceFileId, "sourceFileId");
		this.fileName = requireText(fileName, "fileName");
		this.declaredContentType = declaredContentType == null ? "" : declaredContentType.trim();
		this.asOfDate = Objects.requireNonNull(asOfDate, "asOfDate must not be null (never read the system clock)");
		this.limits = Objects.requireNonNull(limits, "limits must not be null");
	}

	public static ParseRequest of(InputStream content, String sourceFileId, String fileName, String declaredContentType,
			LocalDate asOfDate, IngestionLimits limits) {
		return new ParseRequest(content, sourceFileId, fileName, declaredContentType, asOfDate, limits);
	}

	/** Convenience for tests and callers that accept the default ceilings. */
	public static ParseRequest of(InputStream content, String sourceFileId, String fileName, String declaredContentType,
			LocalDate asOfDate) {
		return new ParseRequest(content, sourceFileId, fileName, declaredContentType, asOfDate,
				IngestionLimits.defaults());
	}

	public InputStream content() {
		return this.content;
	}

	public String sourceFileId() {
		return this.sourceFileId;
	}

	public String fileName() {
		return this.fileName;
	}

	public String declaredContentType() {
		return this.declaredContentType;
	}

	public FileType declaredFileType() {
		return FileType.fromContentType(this.declaredContentType);
	}

	public LocalDate asOfDate() {
		return this.asOfDate;
	}

	public IngestionLimits limits() {
		return this.limits;
	}

	public Charset defaultCharset() {
		return StandardCharsets.UTF_8;
	}

	/**
	 * Buffers the stream into memory, refusing anything over the configured
	 * ceiling instead of growing until the heap gives out.
	 *
	 * <p>The buffer is read one byte past the limit so "exactly at the limit" and
	 * "one byte over the limit" cannot be confused, which is the difference
	 * between a deterministic rejection and an {@code OutOfMemoryError}.
	 */
	public byte[] readContent() throws IOException {
		long ceiling = this.limits.maxFileBytes();
		byte[] buffer = new byte[(int) ceiling + 1];
		int total = 0;
		int read;
		while (total < buffer.length && (read = this.content.read(buffer, total, buffer.length - total)) != -1) {
			total += read;
		}
		if (total > ceiling) {
			throw new ContentTooLargeException(ceiling);
		}
		byte[] content = new byte[total];
		System.arraycopy(buffer, 0, content, 0, total);
		return content;
	}

	private static String requireText(String value, String field) {
		Objects.requireNonNull(value, field + " must not be null");
		String trimmed = value.trim();
		if (trimmed.isEmpty()) {
			throw new IllegalArgumentException(field + " must not be blank");
		}
		return trimmed;
	}

	/**
	 * Signals that the upload exceeded {@code IngestionLimits.maxFileBytes}.
	 *
	 * <p>Carries no file content in its message, per rule 5.
	 */
	public static final class ContentTooLargeException extends IOException {

		private static final long serialVersionUID = 1L;

		private final long limitBytes;

		ContentTooLargeException(long limitBytes) {
			super("upload exceeds the configured maximum of " + limitBytes + " bytes");
			this.limitBytes = limitBytes;
		}

		public long limitBytes() {
			return this.limitBytes;
		}

	}

}
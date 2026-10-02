package com.fintech.cfo.ingestion.model;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

import com.fintech.cfo.shared.domain.OrganizationId;
import com.fintech.cfo.shared.domain.UserId;
import com.fintech.cfo.shared.security.SecurityPrincipal;

/**
 * Everything the ingestion orchestrator needs for one upload, in one immutable
 * value.
 *
 * <p>Buffered bytes rather than a stream: the security gate, the checksum and the
 * parser all need the same content, and an upload at the configured ceiling is
 * small enough to hold once. Passing a stream would make three passes over it
 * impossible without re-reading.
 *
 * <p>The clock is injected as {@code uploadedAt} for the same reason the as-of
 * date is: a run replayed later must produce the same record.
 */
public record IngestionRequest(UUID ingestionRunId, OrganizationId organizationId, UserId uploadedBy,
		String sourceFileId, String originalFilename, String contentType, byte[] content, IngestionSchema schema,
		LocalDate asOfDate, Instant uploadedAt, IngestionLimits limits, SecurityPrincipal principal,
		String sourceSystem) {

	public IngestionRequest {
		Objects.requireNonNull(organizationId, "organizationId must not be null");
		Objects.requireNonNull(sourceFileId, "sourceFileId must not be null");
		Objects.requireNonNull(originalFilename, "originalFilename must not be null");
		Objects.requireNonNull(asOfDate, "asOfDate must not be null (never read the system clock)");
		Objects.requireNonNull(uploadedAt, "uploadedAt must not be null (never read the system clock)");
		schema = schema == null ? IngestionSchema.empty() : schema;
		limits = limits == null ? IngestionLimits.defaults() : limits;
		content = content == null ? new byte[0] : content.clone();
		ingestionRunId = ingestionRunId == null ? UUID.randomUUID() : ingestionRunId;
		contentType = contentType == null ? "" : contentType;
		sourceSystem = sourceSystem == null ? "" : sourceSystem;
	}

	@Override
	public byte[] content() {
		return this.content.clone();
	}

	/**
	 * @return the raw backing array for callers inside the pipeline that must not
	 * copy it again; never mutate it
	 */
	public byte[] contentUnsafe() {
		return this.content;
	}

}
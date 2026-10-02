package com.fintech.cfo.ingestion.model;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import com.fintech.cfo.shared.domain.OrganizationId;

/**
 * Immutable value mirroring one {@code source_records} row of
 * {@code V3__create_ingestion.sql}: a row exactly as read, with its validity and
 * the reasons it failed.
 *
 * <p>Not a JPA entity; see {@link SourceFile}. This type is the storage shape of
 * rule 4 — evidence is a row plus a checksum, never a copy of the document.
 *
 * <p>V3 puts {@code UNIQUE (source_file_id, row_number)}, which is why
 * {@link #rowNumber()} must be the 1-based coordinate from {@link RowCoordinate}
 * and not a sequence number. A re-upload of the same bytes therefore collides
 * deliberately: the original evidence is immutable and nothing overwrites an
 * uploaded row.
 */
public record SourceRecord(UUID id, OrganizationId organizationId, UUID sourceFileId, UUID ingestionRunId,
		long rowNumber, String rawPayload, boolean valid, List<String> validationErrors, Instant createdAt) {

	public SourceRecord {
		Objects.requireNonNull(id, "id must not be null");
		Objects.requireNonNull(organizationId, "organizationId must not be null (organization_id is the tenant boundary)");
		Objects.requireNonNull(sourceFileId, "sourceFileId must not be null");
		Objects.requireNonNull(ingestionRunId, "ingestionRunId must not be null");
		Objects.requireNonNull(rawPayload, "rawPayload must not be null (V3 declares it NOT NULL)");
		if (rowNumber < 1) {
			throw new IllegalArgumentException("row_number is 1-based in V3 and must be positive");
		}
		validationErrors = validationErrors == null ? List.of() : List.copyOf(validationErrors);
		createdAt = createdAt == null ? Instant.EPOCH : createdAt;
	}

	public static SourceRecord accepted(UUID id, OrganizationId organizationId, UUID sourceFileId, UUID runId,
			ParsedRow row, Instant createdAt) {
		Objects.requireNonNull(row, "row must not be null");
		return new SourceRecord(id, organizationId, sourceFileId, runId, row.coordinate().rowNumber(), row.rawPayload(),
				true, List.of(), createdAt);
	}

	public static SourceRecord rejected(UUID id, OrganizationId organizationId, UUID sourceFileId, UUID runId,
			RejectedRow row, Instant createdAt) {
		Objects.requireNonNull(row, "row must not be null");
		RejectedRow rejectedRow = row;
		if (rejectedRow.coordinate() == null) {
			throw new IllegalArgumentException("a rejected source record must carry a row coordinate");
		}
		return new SourceRecord(id, organizationId, sourceFileId, runId, rejectedRow.coordinate().rowNumber(),
				rejectedRow.rawPayload(), false, List.of(rejectedRow.reason().name() + ": " + rejectedRow.detail()),
				createdAt);
	}

}
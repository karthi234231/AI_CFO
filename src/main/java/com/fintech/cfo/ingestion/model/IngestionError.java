package com.fintech.cfo.ingestion.model;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import com.fintech.cfo.ingestion.enums.IngestionErrorType;
import com.fintech.cfo.ingestion.enums.ValidationSeverity;
import com.fintech.cfo.shared.domain.OrganizationId;

/**
 * Immutable value mirroring one {@code ingestion_errors} row of
 * {@code V3__create_ingestion.sql}.
 *
 * <p>Not a JPA entity; see {@link SourceFile}. Built from a {@link ValidationFinding}
 * so the structured finding and the persistable record cannot describe different
 * problems.
 *
 * <p>{@code row_number} and {@code field_name} are nullable in V3, which is how a
 * file-level finding (bad extension, corrupt container) is stored without
 * inventing a row it never had.
 */
public record IngestionError(UUID id, OrganizationId organizationId, UUID ingestionRunId, IngestionErrorType errorType,
		ValidationSeverity severity, Long rowNumber, String fieldName, String message, Instant createdAt) {

	public static final int MAX_FIELD_NAME_LENGTH = 255;
	public static final int MAX_MESSAGE_LENGTH = 2000;

	public IngestionError {
		Objects.requireNonNull(id, "id must not be null");
		Objects.requireNonNull(organizationId, "organizationId must not be null (organization_id is the tenant boundary)");
		Objects.requireNonNull(ingestionRunId, "ingestionRunId must not be null");
		Objects.requireNonNull(errorType, "errorType must not be null");
		Objects.requireNonNull(severity, "severity must not be null");
		message = message == null ? "" : message;
		if (message.isBlank()) {
			throw new IllegalArgumentException("message must not be blank (V3 declares it NOT NULL)");
		}
		if (message.length() > MAX_MESSAGE_LENGTH) {
			message = message.substring(0, MAX_MESSAGE_LENGTH);
		}
		fieldName = fieldName == null ? "" : fieldName;
		if (fieldName.length() > MAX_FIELD_NAME_LENGTH) {
			fieldName = fieldName.substring(0, MAX_FIELD_NAME_LENGTH);
		}
		if (rowNumber != null && rowNumber < 1) {
			throw new IllegalArgumentException("row_number is 1-based in V3 and must be positive");
		}
		createdAt = createdAt == null ? Instant.EPOCH : createdAt;
	}

	public static IngestionError from(UUID id, OrganizationId organizationId, UUID runId, ValidationFinding finding,
			Instant createdAt) {
		Objects.requireNonNull(finding, "finding must not be null");
		return new IngestionError(id, organizationId, runId, finding.errorType(), finding.severity(),
				finding.coordinate() == null ? null : finding.coordinate().rowNumber(), finding.columnName(),
				finding.toPersistableMessage(), createdAt);
	}

	public boolean isFileLevel() {
		return this.rowNumber == null;
	}

}
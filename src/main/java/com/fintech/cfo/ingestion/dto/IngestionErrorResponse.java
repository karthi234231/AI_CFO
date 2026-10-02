package com.fintech.cfo.ingestion.dto;

import java.util.Objects;
import java.util.UUID;

import com.fintech.cfo.ingestion.enums.IngestionErrorType;
import com.fintech.cfo.ingestion.enums.ValidationSeverity;
import com.fintech.cfo.ingestion.model.IngestionError;

/**
 * One structured ingestion error, as a client sees it.
 *
 * <p>{@code rowNumber} is nullable, and that is the point: a file-level problem —
 * a corrupt container, a traversal filename — has no row, and inventing one would
 * put a false row number into an audit record.
 *
 * <p>The client filters on {@code errorType} and {@code severity}, so both are
 * enums serialised by name rather than ordinals, whose meaning would change if
 * someone reordered the enum.
 */
public record IngestionErrorResponse(UUID id, UUID ingestionRunId, IngestionErrorType errorType,
		ValidationSeverity severity, Long rowNumber, String fieldName, String message) {

	public IngestionErrorResponse {
		Objects.requireNonNull(id, "id must not be null");
		Objects.requireNonNull(ingestionRunId, "ingestionRunId must not be null");
		errorType = errorType == null ? IngestionErrorType.INTERNAL : errorType;
		severity = severity == null ? ValidationSeverity.ERROR : severity;
		fieldName = fieldName == null ? "" : fieldName;
		message = message == null ? "" : message;
		if (rowNumber != null && rowNumber < 1) {
			throw new IllegalArgumentException("rowNumber is 1-based and must be positive");
		}
	}

	public static IngestionErrorResponse from(IngestionError error) {
		Objects.requireNonNull(error, "error must not be null");
		return new IngestionErrorResponse(error.id(), error.ingestionRunId(), error.errorType(), error.severity(),
				error.rowNumber(), error.fieldName(), error.message());
	}

	/** @return true when the problem is the whole file rather than one row */
	public boolean isFileLevel() {
		return this.rowNumber == null;
	}

	/** @return true when this error alone caused its row to be refused */
	public boolean isBlocking() {
		return this.severity == ValidationSeverity.ERROR;
	}

}
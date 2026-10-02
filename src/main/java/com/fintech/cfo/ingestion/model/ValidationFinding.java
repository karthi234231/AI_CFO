package com.fintech.cfo.ingestion.model;

import java.util.Objects;

import com.fintech.cfo.ingestion.enums.IngestionErrorType;
import com.fintech.cfo.ingestion.enums.RejectionReason;
import com.fintech.cfo.ingestion.enums.ValidationSeverity;

/**
 * A single structured validation observation.
 *
 * <p>A boolean is never returned anywhere in this module. "Invalid" cannot be
 * acted on; "row 417, column {@code amount}, INVALID_AMOUNT, expected a decimal
 * with at most 4 fraction digits, found {@code 12.3.4}" can. Findings are also
 * what a future {@code ingestion_errors} insert writes verbatim.
 *
 * <p>{@code coordinate} is null for file-level findings, which is how a caller
 * distinguishes "this upload is untrustworthy" from "row 12 is untrustworthy".
 */
public record ValidationFinding(IngestionErrorType errorType, ValidationSeverity severity, RejectionReason reason,
		RowCoordinate coordinate, String columnName, String message) {

	public ValidationFinding {
		Objects.requireNonNull(errorType, "errorType must not be null");
		Objects.requireNonNull(severity, "severity must not be null");
		Objects.requireNonNull(reason, "reason must not be null");
		columnName = columnName == null ? "" : columnName;
		message = message == null ? "" : message;
	}

	public static ValidationFinding file(IngestionErrorType errorType, RejectionReason reason, String message) {
		return new ValidationFinding(errorType, ValidationSeverity.ERROR, reason, null, null, message);
	}

	public static ValidationFinding file(ValidationSeverity severity, IngestionErrorType errorType,
			RejectionReason reason, String message) {
		return new ValidationFinding(errorType, severity, reason, null, null, message);
	}

	public static ValidationFinding row(IngestionErrorType errorType, RejectionReason reason, RowCoordinate coordinate,
			String message) {
		return new ValidationFinding(errorType, ValidationSeverity.ERROR, reason, coordinate, null, message);
	}

	public static ValidationFinding field(IngestionErrorType errorType, RejectionReason reason, RowCoordinate coordinate,
			String columnName, String message) {
		return new ValidationFinding(errorType, ValidationSeverity.ERROR, reason, coordinate, columnName, message);
	}

	public ValidationFinding withSeverity(ValidationSeverity newSeverity) {
		return new ValidationFinding(this.errorType, newSeverity, this.reason, this.coordinate, this.columnName,
				this.message);
	}

	public ValidationFinding withMessage(String newMessage) {
		return new ValidationFinding(this.errorType, this.severity, this.reason, this.coordinate, this.columnName,
				newMessage);
	}

	public boolean isFileLevel() {
		return this.coordinate == null;
	}

	public boolean isError() {
		return this.severity == ValidationSeverity.ERROR;
	}

	public boolean isColumnScoped() {
		return !this.columnName.isEmpty();
	}

	/**
	 * The persisted form for {@code ingestion_errors} (V3). The message is capped
	 * at the column width so a long diagnostic can never fail the insert and lose
	 * the error record entirely.
	 */
	public String toPersistableMessage() {
		int limit = 2000;
		return this.message.length() <= limit ? this.message : this.message.substring(0, limit);
	}

	@Override
	public String toString() {
		return "[" + this.severity + "/" + this.errorType + "/" + this.reason + "] "
				+ (isFileLevel() ? "file" : String.valueOf(this.coordinate))
				+ (isColumnScoped() ? "." + this.columnName : "") + ": " + this.message;
	}

}
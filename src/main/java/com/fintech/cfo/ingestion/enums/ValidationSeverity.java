package com.fintech.cfo.ingestion.enums;

/**
 * Severity of a validation finding.
 *
 * <p>Persisted to {@code ingestion_errors.severity} (V3, {@code VARCHAR(16)}).
 * Only {@link #ERROR} rejects a row; warnings are recorded and surfaced but do
 * not by themselves stop a row from being processed, because an audit trail that
 * refuses to record a suspicious-but-parseable value is an audit trail with holes.
 */
public enum ValidationSeverity {

	/** Recorded for context; changes nothing. */
	INFO,
	/** Recorded and surfaced, but the row is still processed. */
	WARNING,
	/** The only severity that blocks a row or refuses a file. */
	ERROR

}
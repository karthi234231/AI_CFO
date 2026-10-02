package com.fintech.cfo.ingestion.enums;

/**
 * Outcome of the file-security gate, before any parsing happens.
 *
 * <p>Persisted to {@code source_files.security_status} (V3, default
 * {@code PENDING}). A run may only start from a file that is {@link #PASSED};
 * there is no "proceed anyway" state, because the gate exists to keep an
 * unreviewed upload out of the parser.
 */
public enum FileSecurityStatus {

	/** Not yet screened. The V3 default, and the only state a run may not start from. */
	PENDING,
	/** Every pre-parse gate agreed; the only state that may reach a parser. */
	PASSED,
	/** Refused by the gate. Carries the findings explaining which one disagreed. */
	REJECTED;

	public boolean allowsParsing() {
		return this == PASSED;
	}

}
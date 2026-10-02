package com.fintech.cfo.ingestion.enums;

/**
 * Lifecycle of an ingestion run.
 *
 * <p>Persisted to {@code ingestion_runs.status} (V3, default {@code PENDING}).
 * {@link #COMPLETED_WITH_REJECTIONS} exists so that a run which processed every
 * row but refused some of them can never be confused with a clean run: an audit
 * that reports "ingestion succeeded" must be able to say whether rows were
 * dropped, and why.
 */
public enum IngestionStatus {

	/** Accepted for processing, not yet started. The V3 default. */
	PENDING,
	/** A stage is executing. */
	RUNNING,
	/** Every row was processed and every row was accepted. */
	COMPLETED,
	/** Every row was processed and at least one was refused. Distinct from {@link #COMPLETED} on purpose. */
	COMPLETED_WITH_REJECTIONS,
	/** Refused by the pre-parse gate; no parser ever saw the file. */
	REJECTED,
	/** The run stopped at a stage without producing a usable result. */
	FAILED;

	public boolean isTerminal() {
		return this == COMPLETED || this == COMPLETED_WITH_REJECTIONS || this == REJECTED || this == FAILED;
	}

	public boolean isFailure() {
		return this == REJECTED || this == FAILED;
	}

}
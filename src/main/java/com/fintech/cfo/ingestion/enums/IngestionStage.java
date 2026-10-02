package com.fintech.cfo.ingestion.enums;

/**
 * The pipeline stage an ingestion run has reached.
 *
 * <p>Persisted to {@code ingestion_runs.stage} (V3, default {@code UPLOAD}). The
 * order below mirrors the agreed flow: upload, file security validation, file
 * type/content validation, checksum, run creation, parse, schema validation,
 * data-quality validation, completion. A stalled run therefore names the exact
 * gate it stalled at.
 */
public enum IngestionStage {

	/** The bytes arrived; nothing has been checked yet. The V3 default. */
	UPLOAD,
	/** Filename traversal, dangerous signatures, authorisation. */
	FILE_SECURITY_VALIDATION,
	/** Extension, declared content type and sniffed bytes must agree. */
	FILE_TYPE_VALIDATION,
	/** SHA-256 of the accepted bytes. */
	CHECKSUM,
	/** The {@code ingestion_runs} row exists. */
	RUN_CREATED,
	/** Bytes are being turned into rows. */
	PARSING,
	/** Header checked against the declared schema. */
	SCHEMA_VALIDATION,
	/** Per-row sanitisation, required fields, types, plausibility, duplicates. */
	DATA_QUALITY_VALIDATION,
	/** Terminal: the run produced usable data. */
	COMPLETED,
	/** Terminal: the run did not complete. */
	FAILED;

	public boolean isTerminal() {
		return this == COMPLETED || this == FAILED;
	}

}
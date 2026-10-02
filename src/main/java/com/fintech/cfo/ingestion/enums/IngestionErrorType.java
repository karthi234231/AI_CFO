package com.fintech.cfo.ingestion.enums;

/**
 * Classification of an ingestion problem.
 *
 * <p>Persisted to {@code ingestion_errors.error_type} (V3, {@code VARCHAR(32)}),
 * so every constant must fit in 32 characters and stay stable.
 *
 * <p>The type is what a client filters on; it deliberately says nothing about
 * the file's contents, because rule 5 forbids logging uploaded file contents.
 */
public enum IngestionErrorType {

	/** Filename traversal, unsafe name, or executable/archive content. */
	FILE_SECURITY,
	/** Extension / declared content type / sniffed content disagreement. */
	FILE_TYPE,
	/** The file could not be tokenised at all. */
	PARSE,
	/** Header missing, required column absent, duplicate column names. */
	SCHEMA,
	/** A mandatory cell was empty on a specific row. */
	REQUIRED_FIELD,
	/** A cell could not be read as the declared date / decimal / currency. */
	DATA_TYPE,
	/** A value is well-typed but implausible: too long, or outside the reporting window. */
	DATA_QUALITY,
	/** A cell began with a spreadsheet formula trigger and was neutralised. */
	FORMULA_INJECTION,
	/** The same logical row appeared more than once in the file. */
	DUPLICATE,
	/** A configured size / row / column / archive limit was exceeded. */
	LIMIT_EXCEEDED,
	/** Unexpected internal failure. */
	INTERNAL

}
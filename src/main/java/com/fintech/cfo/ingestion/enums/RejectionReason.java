package com.fintech.cfo.ingestion.enums;

/**
 * Why a row, or a whole file, was refused.
 *
 * <p>This is the vocabulary an operator sees. It is intentionally exhaustive
 * rather than generic: "row 4, column amount, INVALID_AMOUNT" is actionable in a
 * way that "invalid file" never is. Constants are the values written to
 * {@code source_records.validation_errors} and reported through
 * {@code ingestion_errors}.
 */
public enum RejectionReason {

	/* ---- file level ---- */
	/** The upload had no bytes, or held nothing but whitespace. */
	EMPTY_FILE,
	/** A recognised format this milestone refuses, or an extension nothing routes on. */
	UNSUPPORTED_FILE_TYPE,
	/** The extension and the declared content type named different formats. */
	EXTENSION_CONTENT_TYPE_MISMATCH,
	/** The bytes are neither readable text nor a spreadsheet package. */
	CONTENT_TYPE_MISMATCH,
	/** The upload exceeded {@code IngestionLimits.maxFileBytes}. */
	SIZE_LIMIT_EXCEEDED,
	/** The bytes are not a readable file of the declared type. */
	CORRUPT_FILE,
	/** The workbook is password protected; this milestone does not open encrypted files. */
	ENCRYPTED_FILE,
	/** An OOXML ceiling was crossed: entry count or total inflated bytes. */
	ARCHIVE_LIMIT_EXCEEDED,
	/** The container inflates by more than the permitted ratio. */
	ZIP_BOMB_SUSPECTED,
	/** The content carries a native executable, class, OLE2, PDF or script signature. */
	EXECUTABLE_CONTENT,
	/** The filename had to be normalised; the original is retained for the audit trail. */
	UNSAFE_FILENAME,
	/** The filename carried a directory component, a drive letter or a {@code ..} segment. */
	PATH_TRAVERSAL_ATTEMPT,
	/** The caller may not upload for this organization. Distinct from any file problem. */
	ACCESS_DENIED,
	/** Every sheet in the workbook was filtered out or unreadable. */
	NO_READABLE_COLUMNS,
	/** The file parsed cleanly and held no data rows. */
	NO_DATA_ROWS,
	/** Reading stopped at {@code IngestionLimits.maxRowsPerFile}; the rest was not read. */
	MAX_ROWS_EXCEEDED,

	/* ---- schema level ---- */
	/** No header row could be located, or generated column names are in use. */
	MISSING_HEADER,
	/** A column the schema marks required is absent from the file header. */
	MISSING_REQUIRED_COLUMN,
	/** The same column name appears more than once in the header. */
	DUPLICATE_COLUMN,

	/* ---- row level ---- */
	/** The row's field count does not match the header, or exceeds the column limit. */
	FIELD_COUNT_MISMATCH,
	/** A cell could not be read at all. */
	UNREADABLE_CELL,
	/** The CSV lexer could not be resumed, so the rest of the file was not read. */
	TRUNCATED_RECORD,
	/** The reader failed unexpectedly while reading this row. */
	ROW_READ_FAILED,
	/** The cell holds a formula with no cached result; the formula is never evaluated. */
	FORMULA_WITHOUT_CACHED_RESULT,

	/* ---- field level ---- */
	/** A column the schema marks required has no value in this row. */
	MISSING_REQUIRED_VALUE,
	/** The value is not an accepted date layout, or names a date that does not exist. */
	INVALID_DATE,
	/** The value is not a plain decimal, or exceeds the {@code NUMERIC(20,4)} contract. */
	INVALID_AMOUNT,
	/** The value is not a three-letter ISO-4217 code. */
	INVALID_CURRENCY,
	/** The value cannot be read as its declared type. */
	INVALID_FIELD_FORMAT,
	/** The value exceeds {@code IngestionLimits.maxCellTextLength}. */
	VALUE_TOO_LONG,
	/** The value is well-typed but outside the plausible or permitted range. */
	VALUE_OUT_OF_RANGE,

	/* ---- security ---- */
	/** The cell began with a spreadsheet formula trigger and was escaped, not refused. */
	FORMULA_INJECTION_NEUTRALISED,

	/* ---- quality ---- */
	/** Every column in the row was blank. */
	BLANK_ROW,
	/** An earlier row on the same sheet carries the same canonical values. */
	DUPLICATE_ROW

}
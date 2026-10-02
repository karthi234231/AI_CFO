package com.fintech.cfo.ingestion.enums;

/**
 * How a parser decides where the header is.
 *
 * <p>{@link #AUTO} is a heuristic and is documented as one on
 * {@code HeaderDetector}. When the guess is wrong for a given export, the caller
 * states the answer instead of relying on it — a pipeline that guesses
 * differently for two runs of the same ERP export is not reproducible (rule 3).
 */
public enum HeaderMode {

	/** Detect by inspecting the first few rows. */
	AUTO,
	/** The first non-blank row is the header; nothing is inferred. */
	FIRST_RECORD,
	/** There is no header; columns are named {@code column_1..column_n}. */
	NONE

}
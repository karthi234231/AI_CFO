package com.fintech.cfo.ingestion.enums;

/**
 * The type a parser observed for a single cell.
 *
 * <p>Recorded rather than collapsed into a string because the difference between
 * a genuine negative number ({@code -1000}) and an injected formula payload
 * ({@code -2+3+cmd|'/c calc'!A0}) is only visible if the reader kept the type it
 * actually saw. Formula cells are kept separate again: their cached result is
 * used as the value and the formula text is retained as the raw text, but the
 * formula is never evaluated.
 */
public enum FieldType {

	/** Text as stored. */
	STRING,
	/** A numeric cell, carried as a {@code BigDecimal} plain string, never a {@code double}. */
	NUMBER,
	/** A date-formatted numeric cell. */
	DATE,
	/** A date-formatted numeric cell with a non-midnight time component. */
	DATETIME,
	/** A boolean cell. */
	BOOLEAN,
	/** A formula cell. The cached result is the value; the expression is the raw text and is never evaluated. */
	FORMULA,
	/** No cell at this position. */
	BLANK,
	/** A cell holding a spreadsheet error such as {@code #REF!}; the row is kept so the error stays visible. */
	ERROR;

	public boolean isNumeric() {
		return this == NUMBER || this == DATE || this == DATETIME;
	}

}
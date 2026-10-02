package com.fintech.cfo.ingestion.enums;

/**
 * The declared type of a column in an {@code IngestionSchema}.
 *
 * <p>Deliberately not the same vocabulary as {@link FieldType}. {@code FieldType}
 * is what a reader observed in a cell; {@code ColumnType} is what the destination
 * expects it to be. Keeping them apart is what allows the data-type validator to
 * report "the workbook says this is text, the schema says it must be an amount"
 * instead of quietly coercing one into the other.
 */
public enum ColumnType {

	/** Free text; only length limits and control-character checks apply. */
	TEXT,
	/** A plain decimal that is not money, so the {@code NUMERIC(20,4)} scale does not apply. */
	DECIMAL,
	/** A decimal that also carries a currency; ISO-4217 checked separately. */
	AMOUNT,
	/** A calendar date. Only unambiguous day-first or year-first layouts are accepted. */
	DATE,
	/** A date with a time of day. */
	DATETIME,
	/** A three-letter ISO-4217 code such as {@code INR}. */
	CURRENCY,
	/** A true/false value; several spellings are tolerated on input. */
	BOOLEAN

}
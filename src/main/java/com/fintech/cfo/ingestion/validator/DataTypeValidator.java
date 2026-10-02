package com.fintech.cfo.ingestion.validator;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import com.fintech.cfo.ingestion.enums.ColumnType;
import com.fintech.cfo.ingestion.enums.IngestionErrorType;
import com.fintech.cfo.ingestion.model.ColumnSchema;
import com.fintech.cfo.ingestion.model.IngestionSchema;
import com.fintech.cfo.ingestion.model.ParsedRow;
import com.fintech.cfo.ingestion.model.ValidationFinding;

/**
 * Checks that each value can actually be read as the type its column declares.
 *
 * <p>Every conversion goes through {@link TypedValueParser}, so the rules about
 * exact decimals, unambiguous dates and ISO currency codes live in exactly one
 * place. A blank optional column is skipped; a blank required column is left for
 * {@link RequiredFieldValidator} so the two do not both report the same absence.
 *
 * <p>The validator never coerces. If a value cannot be read as its declared type
 * the finding states the column, the reason and what was expected, and the row is
 * refused (rule 3) — no defaulting, no truncation, no "close enough".
 */
public final class DataTypeValidator {

	public List<ValidationFinding> validate(ParsedRow row, IngestionSchema schema) {
		Objects.requireNonNull(row, "row must not be null");
		Objects.requireNonNull(schema, "schema must not be null");
		List<ValidationFinding> findings = new ArrayList<>();
		for (ColumnSchema column : schema.columns()) {
			check(row, column, row.text(column.name())).ifPresent(findings::add);
		}
		return findings;
	}

	/**
	 * @return a finding when the non-blank value cannot be read as the column
	 * type, otherwise an empty optional
	 */
	public Optional<ValidationFinding> check(ParsedRow row, ColumnSchema column, String value) {
		Objects.requireNonNull(row, "row must not be null");
		Objects.requireNonNull(column, "column must not be null");
		if (value == null || value.isBlank()) {
			return Optional.empty();
		}
		try {
			parse(column, value);
			return Optional.empty();
		}
		catch (TypedValueParser.ValueFormatException ex) {
			return Optional.of(ValidationFinding.field(IngestionErrorType.DATA_TYPE, ex.reason(), row.coordinate(),
					column.name(),
					"column '" + column.name() + "' expected " + describe(column.type()) + ": " + ex.getMessage()));
		}
	}

	/**
	 * @throws TypedValueParser.ValueFormatException when the value cannot be read
	 * as the column type
	 */
	public static Object parse(ColumnSchema column, String value) {
		return switch (column.type()) {
			case TEXT -> value;
			case DECIMAL -> TypedValueParser.decimal(value);
			case AMOUNT -> TypedValueParser.amount(value);
			case DATE -> TypedValueParser.date(value);
			case DATETIME -> TypedValueParser.dateTime(value);
			case CURRENCY -> TypedValueParser.currency(value);
			case BOOLEAN -> TypedValueParser.booleanValue(value);
		};
	}

	private static String describe(ColumnType type) {
		return switch (type) {
			case TEXT -> "text";
			case DECIMAL -> "a decimal number";
			case AMOUNT -> "an amount with at most " + TypedValueParser.AMOUNT_SCALE + " fraction digits";
			case DATE -> "a date such as 2024-01-31 or 31/01/2024";
			case DATETIME -> "a timestamp";
			case CURRENCY -> "a three-letter ISO-4217 currency code";
			case BOOLEAN -> "a boolean";
		};
	}

}
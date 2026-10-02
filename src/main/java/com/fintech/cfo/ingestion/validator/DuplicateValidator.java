package com.fintech.cfo.ingestion.validator;

import java.math.BigDecimal;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import com.fintech.cfo.ingestion.enums.ColumnType;
import com.fintech.cfo.ingestion.enums.RejectionReason;
import com.fintech.cfo.ingestion.model.ColumnSchema;
import com.fintech.cfo.ingestion.model.IngestionSchema;
import com.fintech.cfo.ingestion.model.ParsedCell;
import com.fintech.cfo.ingestion.model.ParsedRow;
import com.fintech.cfo.ingestion.model.RejectedRow;

/**
 * Refuses a row whose business content has already appeared earlier in the same
 * sheet.
 *
 * <p>Stateful by design, and deliberately so: a duplicate is a property of the
 * set of rows, not of one row. Callers obtain one instance per file and feed it
 * rows in reading order, so "the first occurrence wins and later ones are
 * refused" is reproducible.
 *
 * <p>The comparison is canonicalised rather than textual. {@code 100} and
 * {@code 100.00} are the same amount, {@code 31/01/2024} and {@code 2024-01-31}
 * are the same date, and repeated spaces inside a narration are not a different
 * narration. This avoids the more common and more damaging false negative, where
 * the same entry exported twice with different formatting slips through.
 *
 * <p>Duplicates are scoped per sheet. The same transaction legitimately appears
 * on two sheets of an export broken down by location, so a cross-sheet match is
 * not a duplicate.
 */
public final class DuplicateValidator {

	/**
	 * Unit Separator (ASCII 31). Chosen because it cannot occur in cell text that
	 * survived sanitisation, so no two different rows can collide by accident — the
	 * one way a duplicate key could produce a false rejection.
	 */
	private static final char COMPONENT_SEPARATOR = '\u001F';

	private final IngestionSchema schema;

	private final Set<String> seenInFile = new LinkedHashSet<>();

	private DuplicateValidator(IngestionSchema schema) {
		this.schema = schema;
	}

	public static DuplicateValidator forSchema(IngestionSchema schema) {
		return new DuplicateValidator(Objects.requireNonNull(schema, "schema must not be null"));
	}

	/**
	 * @return the rejection when this row repeats an earlier row on the same
	 * sheet, otherwise an empty optional
	 */
	public Optional<RejectedRow> check(ParsedRow row) {
		Objects.requireNonNull(row, "row must not be null");
		// The sheet name is part of the key, so the same transaction on two tabs of a
		// location breakdown is not treated as a duplicate.
		String key = row.coordinate().sheetName() + COMPONENT_SEPARATOR + canonicalKey(row);
		// add() returning false means the key was already present. Because rows arrive
		// in reading order, that makes the first occurrence the winner and every later
		// one the rejection — the rule is positional, so a replay reproduces it.
		if (!this.seenInFile.add(key)) {
			return Optional.of(RejectedRow.of(row.coordinate(), RejectionReason.DUPLICATE_ROW,
					"an earlier row in sheet '" + row.coordinate().sheetName() + "' has the same values"));
		}
		return Optional.empty();
	}

	/**
	 * The canonical form two rows must share to be considered duplicates.
	 */
	public String canonicalKey(ParsedRow row) {
		StringBuilder key = new StringBuilder();
		if (this.schema.isEmpty()) {
			for (ParsedCell cell : row.cells()) {
				append(key, canonicalise(cell.text()));
			}
			return key.toString();
		}
		for (ColumnSchema column : this.schema.columns()) {
			append(key, canonicalise(column, row.text(column.name())));
		}
		return key.toString();
	}

	private static void append(StringBuilder key, String value) {
		if (key.length() > 0) {
			key.append(COMPONENT_SEPARATOR);
		}
		key.append(value);
	}

	private static String canonicalise(ColumnSchema column, String value) {
		String trimmed = value == null ? "" : value.trim();
		return switch (column.type()) {
			case DECIMAL, AMOUNT -> canonicalNumber(trimmed);
			case DATE -> canonicalDate(trimmed);
			case DATETIME -> canonicalDateTime(trimmed);
			case CURRENCY -> trimmed.toUpperCase(java.util.Locale.ROOT);
			case TEXT, BOOLEAN -> canonicalise(trimmed);
		};
	}

	private static String canonicalNumber(String value) {
		try {
			BigDecimal parsed = new BigDecimal(value);
			return parsed.stripTrailingZeros().toPlainString();
		}
		catch (NumberFormatException ex) {
			return canonicalise(value);
		}
	}

	private static String canonicalDate(String value) {
		try {
			return TypedValueParser.date(value).toString();
		}
		catch (TypedValueParser.ValueFormatException ex) {
			return canonicalise(value);
		}
	}

	private static String canonicalDateTime(String value) {
		try {
			return TypedValueParser.dateTime(value).toString();
		}
		catch (TypedValueParser.ValueFormatException ex) {
			return canonicalise(value);
		}
	}

	private static String canonicalise(String value) {
		if (value == null) {
			return "";
		}
		return value.trim().replaceAll("\\s+", " ");
	}

}
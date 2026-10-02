package com.fintech.cfo.ingestion.model;

import java.util.Objects;

import com.fintech.cfo.ingestion.enums.FieldType;

/**
 * One cell as the reader actually saw it.
 *
 * <p>{@code text} is the value downstream consumers use; {@code rawText} is what
 * the file literally contained. They differ for exactly one case — a formula
 * cell — where {@code text} is the cached result and {@code rawText} is the
 * formula. Keeping both is what lets an auditor prove which expression produced a
 * number without the module ever evaluating untrusted spreadsheet code.
 *
 * <p>No {@code double} survives this type: numeric cells arrive as {@code NUMBER}
 * with a {@code BigDecimal} plain string, so no binary floating-point error can
 * reach the money layer (rule 2).
 */
public record ParsedCell(String columnName, int columnIndex, FieldType type, String text, String rawText) {

	public ParsedCell {
		Objects.requireNonNull(columnName, "columnName must not be null");
		Objects.requireNonNull(type, "type must not be null");
		if (columnIndex < 0) {
			throw new IllegalArgumentException("columnIndex must not be negative");
		}
		text = text == null ? "" : text;
		rawText = rawText == null ? text : rawText;
	}

	public static ParsedCell text(String columnName, int columnIndex, String value) {
		return new ParsedCell(columnName, columnIndex, FieldType.STRING, value, value);
	}

	public static ParsedCell number(String columnName, int columnIndex, String plainDecimal) {
		return new ParsedCell(columnName, columnIndex, FieldType.NUMBER, plainDecimal, plainDecimal);
	}

	public static ParsedCell blank(String columnName, int columnIndex) {
		return new ParsedCell(columnName, columnIndex, FieldType.BLANK, "", "");
	}

	public static ParsedCell formula(String columnName, int columnIndex, FieldType cachedType, String cachedValue,
			String formula) {
		Objects.requireNonNull(formula, "formula must not be null");
		return new ParsedCell(columnName, columnIndex, FieldType.FORMULA, cachedValue, formula);
	}

	public boolean isBlank() {
		return this.type == FieldType.BLANK || this.text.isBlank();
	}

	/**
	 * @return {@code true} when the value must never be handed to a spreadsheet
	 * or CSV consumer as-is because it starts with a formula trigger
	 */
	public boolean isFormulaDerived() {
		return this.type == FieldType.FORMULA;
	}

}